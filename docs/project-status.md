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

## 3. Most Recent Physical Benchmark Evidence

### Live Physical Run (October 29, 2026, 512px Samoyed Test Image)
| Backend / Configuration | Vision Encoding Latency (ms) | Total Duration (s) | Battery Temp |
|---|---|---|---|
| **Mixed (GPU LM + CPU Vision)** | **1391.0 ms** | 12.016 s | 32.7°C |
| **Full GPU (GPU LM + GPU Vision)** | **1814.0 ms** | 12.035 s | 32.7°C |

**Observed Ratio:** GPU vision projector is **+30.4% slower** (1.304×) than keeping vision on CPU.

---

## 4. Next Implementation Milestones

1. **Resolution Scaling Sweep (Workstream A):** Execute the full 256px, 384px, 512px, 768px, 1024px matrix across multiple repetitions to determine if a crossover point exists.
2. **Cold vs. Warm Serving Breakdown (Workstream B):** Run persistent `llama-server` multimodal HTTP requests to measure steady-state vision encoding without process startup overhead.
3. **Synchronized Perfetto Tracing (Workstream C):** Correlate CPU core residency and frequency scaling with `mtmd_batch_encode` execution slices.
