# Project Status — Snapdragon Edge Inference Lab

**Last Updated:** October 29, 2026
**Current Branch:** `main`
**Repository State:** Initialized, tested, physically validated on device.

---

## 1. Completed Components

- [x] **Repository Foundation:** Production Python package (`edge_lab`) structured with Typer CLI, Pydantic schemas, PyYAML loaders, and Ruff / Pytest tooling.
- [x] **Hardware Diagnostic Engine (`edge-lab doctor`):** Automated validation of Ubuntu host, ADB, Realme GT 8 Pro (SM8850 Snapdragon 8 Elite), 8-core Oryon CPU topology (6 Perf @ 3.63 GHz, 2 Prime @ 4.61 GHz), battery/thermals, deployed `llama.cpp` binaries, and LFM3 model files.
- [x] **ADB Abstraction Layer (`AdbClient`):** Subprocess-based execution with timeouts, returncode checking, port forwarding, push/pull, and process cleanup.
- [x] **Historical Baseline System:** Formatted physical measurements for Termux CPU (b11967), Snapdragon cross CPU (ac8e169), and Snapdragon Adreno GPU (OpenCL -ngl 99), including 512px vision projector placement data.
- [x] **Automated Reporting Subsystem:** Generation of Markdown reports, static HTML dashboards, and Matplotlib publication charts.
- [x] **Workstream A Implementation (`ResolutionCrossoverExperiment`):** Automated resolution scaling runner with warmup trials, randomized scheduling, cooldown intervals, and thermal abort.
- [x] **Workstream B Implementation (`ColdVsWarmExperiment`):** Runner comparing cold-process invocation (`llama-mtmd-cli`) and persistent HTTP serving (`llama-server`) with port forwarding.
- [x] **Workstream C Implementation (`PerfettoProfiler`):** Detection of Android 16 Perfetto service, background trace recording using Android-compatible paths (`/data/misc/perfetto-*`), and automated TraceProcessor SQL analysis.
- [x] **Physical Hardware Smoke Test:** Successfully executed live inference on Realme GT 8 Pro, validating both Mixed (CPU Vision: 1391 ms) and Full GPU (GPU Vision: 1814 ms) paths on device.
- [x] **CI & Unit Test Suite:** 100% passing Pytest suite and clean Ruff lint checks without requiring hardware in CI.

---

## 2. Current Working Configuration

- **Target Device:** Realme GT 8 Pro (`RMX5210IN`, Snapdragon 8 Elite SM8850, Android 16)
- **Model:** Liquid AI LFM3-VL-2B (Language: `LFM3-VL-2B-Q4_K_M.gguf`, Projector: `mmproj-LFM3-VL-2B-Q8_0.gguf`)
- **Runtime:** `llama.cpp` commit `ac8e1694d837b11ed0aab944a6a2eee34b9c4d80` deployed to `/data/local/tmp/llama.cpp/`
- **Backends Validated:**
  - `CPU`: 4 Oryon threads
  - `GPUOpenCL`: Qualcomm Adreno 840 (OpenCL 3.0, 7555 MiB capacity)
  - `HTP0`: Qualcomm Hexagon Architecture v81 (driver loaded, backend registered)

---

### Live Physical Benchmark Summary (Realme GT 8 Pro / Snapdragon 8 Elite)

#### 1. Resolution Scaling Crossover Discovery (Workstream A Completed)
| Resolution | CPU Vision Latency | GPU Vision Latency | Speedup | Winner |
|---|---|---|---|---|
| **256px** | **600.0 ms** | 1,281.0 ms | 2.13× faster on CPU | 🏆 **CPU** |
| **512px** | **1,391.0 ms** | 1,814.0 ms | 1.30× faster on CPU | 🏆 **CPU** |
| **768px** | 5,271.0 ms | **3,338.0 ms** | 1.58× faster on GPU | 🏆 **Adreno GPU (CROSSOVER)** |
| **1024px** | 10,475.0 ms | **2,878.0 ms** | 3.64× faster on GPU | 🏆 **Adreno GPU (DOMINATES)** |

*Crossover occurs at ~620px where arithmetic intensity overcomes OpenCL dispatch latency.*

#### 2. Thermal Stress & Concurrency Benchmark (Workstream B & C Completed)
- **Workload:** 2 parallel concurrent slots (`-np 2`, `--cont-batching`) running dense multimodal financial chart extraction (1,809 prompt tokens per query).
- **GPU Thermal Spike:** Adreno 840 GPU reached **97.4°C** peak (from 57.9°C baseline).
- **CPU Oryon Cores:** Performance cores peaked at **79.5°C**, Prime cores at **79.2°C**.
- **Chassis / Battery:** Ramped from 32.5°C to 32.9°C (effective passive vapor chamber insulation).
- **Thermal Throttling Cliff:** After ~50 seconds of continuous load, kernel thermal governance forced a GPU clock step-down, stretching query latency from **19.14s to 28.64s (+49.7% degradation)** and dropping throughput from 2.52 t/s to 1.68 t/s per slot.
- **Trace Artifact:** Synchronized 15s kernel trace recorded to `results/profiling/thermal-dvfs-15s.pftrace` and analyzed with `TraceProcessor`.

---

## 4. Upstream Fork & Contribution Roadmap

1. **Submodule Integration:** Fork `ggml-org/llama.cpp` and initialize as `third_party/llama.cpp` submodule.
2. **Adaptive Projector Dispatch Optimization:** Implement dynamic threshold offload in `tools/mtmd/mtmd.cpp`:
   - If image tokens $\le 512$ (or dimensions $< 640\text{px}$), dispatch projector graph to CPU threadpool.
   - If image tokens $> 512$, dispatch projector graph to `GPUOpenCL`.
3. **C++ Instrumentation:** Add high-resolution Nanosecond timer markers (`ggml_time_us()`) around `mtmd_batch_encode` and OpenCL queue enqueues.
