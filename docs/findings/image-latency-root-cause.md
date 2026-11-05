# Why Image Processing Feels Slow on the Phone (and How to Fix It)

**Status:** code-reading analysis plus a model fitted to the existing measurements. **No new hardware runs.**
Each claim below is marked as one of: *measured* (already in `results/`), *derived* (calculated from measured data),
*code fact* (read from llama.cpp source at fork `akashrai2003/llama.cpp`, master `3259ab6`), or *hypothesis*
(needs a device run to confirm).

---

## 1. TL;DR

1. **The biggest cost in the phone UI is the number of image tokens, not CPU vs GPU.** *(code fact)*
   LFM3-VL ships `size.longest_edge = 16,777,216` pixels (16.7 MP). llama.cpp turns that into a limit of
   `min(4096, 16.7M / 1024) = 4096` image tokens. A 12 MP phone photo therefore becomes **~4096 LM tokens**
   from **~16,384 encoder patches**. Nothing caps it by default: `--image-max-tokens` defaults to -1, and the
   web UI's "Maximum image resolution (megapixels)" setting defaults to 0 (off).
2. **The "CPU beats GPU below ~620px" result is very likely a measurement artifact.** *(derived + hypothesis)*
   Every Workstream A trial runs `llama-mtmd-cli --no-warmup` in a fresh process. With warmup off, the
   projector's one-time setup runs **inside** the timed `mtmd batch encoding` window. That setup is compute-buffer
   reservation and allocation, the flash-attention probe, and lazily compiled OpenCL flash-attention kernels.
   Fitting the 256px and 512px points gives the GPU a **~1.1 s fixed cost** and the CPU **~0.35 s**. The GPU's
   marginal throughput is higher than the CPU's at every size measured.
3. **The lab has never measured a warm encode.** *(code fact)* `llama-server` does not log encode time, and
   the existing warm-server run sends the *same* image three times. Prompt caching then skips re-encoding
   after the first request.

---

## 2. What the model actually computes

From `LiquidAI/LFM3-VL-2B` `config.json` / `processor_config.json` and `tools/mtmd/models/lfm3vl.cpp`:

| Property | Value |
|---|---|
| Vision tower | 14 layers: 8 gated short-conv (3x3 depthwise) + 6 full-attention, hidden 1024, FFN 2560 |
| Attention | 16 heads, 8 KV heads (GQA), head dim 64 |
| Patch / merge | 16px patches, 2x2 merge, so **1 LM token = 32x32 px** |
| Resize | bicubic smart-resize, min 65,536 px (64 tokens), max 16.7 MP (capped to 4096 tokens in llama.cpp) |
| Projector | LayerNorm, then 4096->4096 GELU MLP->LM dim |

Per-resolution work *(derived; the FLOP model is in the appendix)*:

| Longest edge (square) | Encoder patches | LM image tokens | Linear GFLOP | Attention GFLOP |
|---|---|---|---|---|
| 256px | 256 | 64 | 86 | 2 |
| 512px | 1,024 | 256 | 346 | 26 |
| 768px | 2,304 | 576 | 778 | 130 |
| 1024px | 4,096 | 1,024 | 1,383 | 412 |
| 12 MP photo (capped) | ~16,384 | ~4,096 | ~5,500 | **~6,600** |

At phone-photo sizes the encoder's attention, which grows with N^2, becomes larger than all the linear layers
combined. After encoding, the language model has to prefill those ~4096 tokens. At the measured pp512 rates
that alone takes **~5.5 s on Adreno** (738 t/s) or **~40 s on CPU** (104 t/s) *(derived from measured rates)*.
The thermal-stress run is consistent with this: a 1500x1200 chart produced 1,809 prompt tokens, about the
1,758 tokens that 1500*1200/1024 predicts *(measured vs derived)*.

## 3. Re-reading the resolution sweep

Measured (n = 1 per cell, cold process, `--no-warmup`):

| px | CPU ms | GPU ms |
|---|---|---|
| 256 | 600 | 1281 |
| 512 | 1391 | 1814 |
| 768 | 5271 | 3338 |
| 1024 | 10475 | 2878 |

Model `t = fixed + GFLOP / throughput` fitted on 256/512 *(derived)*:

| Path | Fixed cost | Marginal throughput |
|---|---|---|
| Oryon CPU (4 threads, Q8_0) | ~0.35 s | ~360 GFLOP/s, dropping to ~170 GFLOP/s at 1024px (N^2 attention on CPU) |
| Adreno 840 (OpenCL) | **~1.1 s** | ~530-620 GFLOP/s |

A ~1.1 s constant cannot come from per-kernel dispatch. The graph has roughly 400-500 nodes, so even 50 us
per launch adds up to only ~25 ms. It matches one-time costs that `--no-warmup` moves into the timed window
*(code facts)*:

* `clip_encode()`: `if (!ctx->is_allocated) clip_model_loader::warmup(...)` runs the flash-attention support
  probe and `ggml_backend_sched_reserve`, then allocates the compute buffers for that image size.
* `ggml-opencl.cpp`: flash-attention kernels for a given (dk, dv) are **lazily compiled** on first use
  (`ggml_opencl_ensure_fa_pre_kernels`, `ggml_opencl_ensure_fa_variant`). The vision tower's head dim 64 is
  compiled during the first image encode, unless the on-disk program cache already holds it.
* The harness's "warmup trials" are separate processes, so they warm only the disk/program caches. They do
  not warm the measured process.

Also note that the GPU's 1024px time is lower than its 768px time. That points to measurement noise
(thermal state, single sample) more than to a real crossover at ~620px.

**Hypothesis to test:** in a warm `llama-server` process, GPU encode beats CPU encode at 512px and probably
at 256px too. If that holds, the "adaptive CPU/GPU projector dispatch" item on the roadmap is not needed.
If a per-encode fixed GPU cost remains when warm, that cost is the real upstream issue to chase.

## 4. Op coverage on OpenCL (code fact)

Every op the LFM3-VL graph uses passes `ggml_opencl_supports_op` on both the pinned `ac8e169` and the
current master:

* im2col + F16 matmul (patchifier)
* RMS_NORM / NORM
* Q8_0 matmuls (dp4a GEMM path, all M dimensions divisible by 64)
* MUL on strided views
* CONT / permute
* CONV_2D_DW (F32 kernel)
* ROPE vision
* FLASH_ATTN_EXT with dk = dv = 64
* GELU_ERF, ADD

So no CPU fallback ping-pong is expected (Hypothesis 2 in the anomaly doc). Confirm on device by checking for
`graph splits = 1` and the absence of "CLIP graph uses unsupported operators" in the log. The parser now
extracts both.

---

## 5. What to change (ordered by impact for the phone UI)

| # | Change | Where | Expected effect |
|---|---|---|---|
| 1 | `--image-max-tokens 256` (about a 512x512 image) or `576` (768x768) | llama-server flags | 12 MP photo goes from ~4096 to 256 tokens: **~16x less LM prefill and ~30x+ less encoder work** (*derived*) |
| 2 | Web UI, Settings, General, **Maximum image resolution (megapixels)** = `0.3`-`1` | browser on the phone | Downscales before upload. Avoids sending a ~5 MB base64 payload, decoding a 12 MP JPEG with stb_image on the phone, and running a single-threaded bicubic resize |
| 3 | Run a persistent `llama-server` with warmup on (never `--no-warmup`) | deployment | One-time projector setup and FA kernel compile happen at startup, not on the first photo |
| 4 | Keep the projector on the GPU in the server (default `--mmproj-offload`), pending the warm benchmark | deployment | Uses the higher marginal throughput |
| 5 | `-t 6` (or 8) when the projector runs on CPU | flags | The lab uses 4 of 8 Oryon cores |
| 6 | Keep `GGML_OPENCL_KERNEL_CACHE_DIR` on a persistent path | env | Removes kernel compile time from restarts |

Example server line for the phone:

```bash
LD_LIBRARY_PATH=/data/local/tmp/llama.cpp/lib GGML_OPENCL_KERNEL_CACHE_DIR=/data/local/tmp/llama.cpp/cl-cache \
  /data/local/tmp/llama.cpp/bin/llama-server -m LFM3-VL-2B-Q4_K_M.gguf --mmproj mmproj-LFM3-VL-2B-Q8_0.gguf \
  -ngl 99 --device GPUOpenCL --mmproj-offload --image-max-tokens 256 -c 4096 -t 6
```

Trade-off: capping tokens lowers the effective input resolution. Use 576-1024 for OCR or dense documents
and 256 for "what is in this photo".

---

## 6. How to verify on device

1. Build the fork. It now logs `mtmd batch encoded in N ms` per request in `llama-server`, and
   `lazy warmup (buffers not allocated) took N ms` in `clip_encode`.
2. Warm, per-image encode latency (unique image per request, `cache_prompt=false`):
   ```bash
   edge-lab benchmark warm-encode --image assets/test-512.jpg --resolutions 256,512,768,1024 --requests 5
   ```
   Compare `first_request_encode_ms` (cold-in-process) against `warm_encode_ms.median` for `gpu_all` and
   `gpu_lm_cpu_vision`.
3. Same sweep with an image cap to quantify the UX win:
   ```bash
   edge-lab benchmark warm-encode --image <phone-photo.jpg> --resolutions 4000 --image-max-tokens 256
   ```
4. Re-run Workstream A with warmup on: `configs/experiments/resolution-crossover-warm.yaml`
   (`trials.process_warmup: true`).

## Appendix: FLOP model

Per patch (MACs):
* conv layer: `D*3D + D*D + 3*D*I`
* attention layer: `D*D + 2*D*(KV*dh) + D*D + 3*D*I`
* 8 conv + 6 attention layers

Attention: `6 layers * 2 * N^2 * dh * H` MACs.

Projector: `(N/4) * (4D*4096 + 4096*2048)`, assuming LM dim 2048.

FLOP = 2 * MAC. D = 1024, I = 2560, H = 16, KV = 8, dh = 64.
