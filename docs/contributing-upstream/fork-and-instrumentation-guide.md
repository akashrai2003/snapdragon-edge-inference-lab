# Upstream Contribution & C++ Instrumentation Guide

This guide walks you through forking `llama.cpp`, navigating the C++ multimodal and OpenCL backend codebases, instrumenting kernel execution times, and preparing upstream contributions to GGML and llama.cpp.

---

## 1. Upstream llama.cpp Source Map

The upstream repository has evolved from legacy `llava` scripts to the new unified **`mtmd` (Multimodal Multi-Device)** subsystem:

```
~/edge-inference/llama.cpp/
├── tools/
│   ├── mtmd/                       # ◄── MULTIMODAL SUBSYSTEM
│   │   ├── mtmd-cli.cpp            # Entry point for llama-mtmd-cli
│   │   ├── mtmd.cpp                # Multimodal graph generation & patch tokenization
│   │   ├── mtmd.h                  # C/C++ public API for mtmd_batch_encode()
│   │   ├── clip.cpp                # Vision transformer backbone implementation
│   │   └── models/                 # Model-specific projector implementations
│   └── server/
│       ├── server-context.cpp      # llama-server multimodal request handler
│       └── server-http.cpp         # HTTP endpoints (/v1/chat/completions)
│
├── ggml/
│   ├── src/
│   │   ├── ggml.c                  # Core tensor definitions & graph evaluation
│   │   ├── ggml-backend.cpp        # Backend registration (CPU, OpenCL, Hexagon)
│   │   ├── ggml-cpu/               # ARM NEON / SVE / SVE2 CPU kernels
│   │   │   └── ggml-cpu.c
│   │   ├── ggml-opencl/            # ◄── ADRENO GPU OPENCL BACKEND
│   │   │   ├── ggml-opencl.cpp     # OpenCL host code, buffer mapping, kernel dispatch
│   │   │   └── ggml-opencl.cl      # OpenCL C kernel source code (GEMM, GEMV, Norm)
│   │   └── ggml-hexagon/           # Qualcomm Hexagon HTP NPU backend
│   │       ├── ggml-hexagon.cpp    # FastRPC host bridge
│   │       └── hexagon/            # HVX / HMX vector kernels
│
└── scripts/
    └── snapdragon/
        ├── build.py                # Toolchain Docker compiler wrapper
        └── run.py                  # On-device ADB deployment & runner
```

---

## 2. Forking and Linking into Your Lab

### Step 2.1: Fork `llama.cpp` on GitHub
1. Go to [https://github.com/ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp).
2. Click **Fork** $\rightarrow$ fork it to your account (e.g., `akashrai2003/llama.cpp`).

### Step 2.2: Add Your Fork as a Git Submodule
In your `snapdragon-edge-inference-lab` repository:
```bash
cd /home/akash-ai/Desktop/Projects/snapdragon-edge-inference-lab
mkdir -p third_party

# Add your fork as a submodule tracking the working commit
git submodule add https://github.com/akashrai2003/llama.cpp third_party/llama.cpp
cd third_party/llama.cpp
git checkout ac8e1694d837b11ed0aab944a6a2eee34b9c4d80
git checkout -b fix/adreno-vision-projector
```

---

## 3. How to Cross-Compile Without Modifying Working Builds

To preserve your existing working installation at `/data/local/tmp/llama.cpp/`, compile into a **separate experimental build directory**:

```bash
cd third_party/llama.cpp

# Compile using the official Snapdragon Android container
python3 scripts/snapdragon/build.py \
    --target adb \
    --build-dir build-dev \
    --install-dir pkg-dev \
    --jobs 8 \
    --cmake-args "-DGGML_OPENCL_PROFILING=ON"
```

### What `--cmake-args "-DGGML_OPENCL_PROFILING=ON"` Does:
- It enables OpenCL event profiling in `ggml-opencl.cpp`.
- For every kernel launched, the OpenCL driver records `CL_PROFILING_COMMAND_START` and `CL_PROFILING_COMMAND_END`, reporting exact GPU hardware execution durations in nanoseconds.

---

## 4. Where the Vision Projector Bottleneck Lives

### Code Location 1: Vision Encoding Timing (`tools/mtmd/mtmd-cli.cpp`)
Look at lines 480–495 in `mtmd-cli.cpp`:
```cpp
int64_t time_start = ggml_time_ms();
res = mtmd_batch_encode(mbatch.get());
LOG_INF("mtmd batch encoding done in %d ms\n", (int)(ggml_time_ms() - time_start));
```
This function calls `mtmd_batch_encode()` in `tools/mtmd/mtmd.cpp`.

### Code Location 2: Graph Offloading Filter in `ggml/src/ggml-opencl/ggml-opencl.cpp`
In `ggml-opencl.cpp`, inspect:
```cpp
bool ggml_cl_can_mul_mat(const struct ggml_tensor * src0, const struct ggml_tensor * src1, struct ggml_tensor * dst)
```
- For every matrix multiplication in the vision projector, GGML asks: *Can OpenCL handle this tensor shape and quantization format?*
- If the tensor size is smaller than a certain threshold, or if the quantization format (e.g., `Q8_0` or `FP16`) is not specialized for Adreno, GGML either:
  1. Enqueues a generic un-optimized OpenCL kernel.
  2. Falls back to CPU, requiring GPU buffer readbacks!

### Code Location 3: OpenCL Command Queue Synchronization
In `ggml-opencl.cpp`, look at `clFinish()` and `clWaitForEvents()` calls:
- If `clFinish()` is invoked between every small layer in the vision projector, the CPU stalls waiting for the GPU pipeline to drain completely before dispatching the next node.

---

## 5. Adding Custom Instrumentation (Step-by-Step)

To see the exact latency breakdown inside the vision projector:

### Step 5.1: Instrumenting Phase Breakdown in `mtmd.cpp`
Add high-precision timers around:
1. Image preprocessing & patch extraction.
2. Tensor upload to OpenCL buffer (`clEnqueueWriteBuffer`).
3. Vision transformer forward pass.
4. Projection MLP execution.
5. Tensor readback / handoff to language model context.

```cpp
// Example C++ instrumentation snippet:
int64_t t_prep = ggml_time_us();
// ... image patch extraction ...
LOG_INF("[PERF] Patch extraction: %.2f ms\n", (ggml_time_us() - t_prep) / 1000.0);

int64_t t_gpu_upload = ggml_time_us();
// ... buffer transfer ...
LOG_INF("[PERF] OpenCL buffer upload: %.2f ms\n", (ggml_time_us() - t_gpu_upload) / 1000.0);
```

### Step 5.2: Push and Benchmark with `edge-lab`
Push your newly compiled binary to `/data/local/tmp/llama.cpp-dev/bin/llama-mtmd-cli`, update `configs/runtimes/`, and immediately run:
```bash
edge-lab benchmark resolution --config configs/experiments/smoke-test.yaml
```

---

## 6. How to Submit an Upstream Pull Request

Once you find and fix the bottleneck (e.g., fusing small projector layers, using zero-copy ION buffers, or keeping the vision projection on CPU automatically when $N_{\text{tokens}} < 512$):

1. **Keep Changes Minimal:** Upstream maintainers (like Georgi Gerganov) strongly favor small, focused, clean PRs over massive refactors.
2. **Provide Reproducible Evidence:** Include:
   - Device: Qualcomm Snapdragon 8 Elite (SM8850 / Adreno 840).
   - Before: 2517 ms vision encoding.
   - After: Your measured speedup.
   - Exact CLI reproduction command using `llama-mtmd-cli`.
3. **Format Code:**
   ```bash
   git diff
   # Ensure no trailing whitespaces or unrelated file edits
   ```
4. **Push Branch & Open PR:**
   ```bash
   git push origin fix/adreno-vision-projector
   ```
   Open a PR at `ggml-org/llama.cpp` with your technical findings and benchmark numbers.
