# System Architecture & Software Design Deep Dive

The **Snapdragon Edge Inference Lab** is an automated, reproducible research and profiling platform designed specifically for edge AI workloads on mobile SoCs. This document explains the complete software architecture, design principles, data flow, and underlying abstractions.

---

## 1. High-Level Architecture & Design Principles

The platform follows a decoupled, host-orchestrated architecture:

```
┌───────────────────────────────────────────────────────────────────────────┐
│ HOST WORKSTATION (Ubuntu 22.04 LTS x86_64)                                │
│                                                                           │
│   CLI Interface (`edge-lab`)                                              │
│   ├── doctor         (Host, ADB, Device, Runtime diagnostic engine)       │
│   ├── results        (Baseline import, reporting, HTML/chart generation)  │
│   ├── benchmark      (Resolution crossover, cold vs. warm runners)        │
│   └── profile        (Perfetto trace capture, TraceProcessor SQL)         │
│                                                                           │
│   Core Orchestration Subsystems:                                          │
│   ├── `edge_lab.config`     (Pydantic V2 schema validation & YAML loader) │
│   ├── `edge_lab.devices`    (AdbClient, DeviceDetector, Oryon Topology)   │
│   ├── `edge_lab.runtimes`   (Snapdragon llama.cpp execution adapter)      │
│   ├── `edge_lab.telemetry`  (Real-time battery, current, thermal monitor) │
│   ├── `edge_lab.metrics`    (Log parsers & statistical distribution engine│
│   ├── `edge_lab.profiling`  (Android Perfetto capture & SQL analytics)    │
│   └── `edge_lab.reporting`  (Matplotlib charts, Markdown & static HTML)   │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ USB Debugging / Port Forwarding
                                      │ (Array-based ADB execution)
┌─────────────────────────────────────▼─────────────────────────────────────┐
│ PHYSICAL ANDROID DEVICE (Realme GT 8 Pro / Snapdragon 8 Elite SM8850)     │
│                                                                           │
│   Target File System:                                                     │
│   ├── Runtime Binaries:    `/data/local/tmp/llama.cpp/bin/`               │
│   │   ├── `llama-mtmd-cli` (Multimodal vision CLI binary)                │
│   │   ├── `llama-bench`    (Throughput microbenchmarking binary)          │
│   │   └── `llama-server`   (Persistent HTTP serving daemon)               │
│   ├── Shared Libraries:    `/data/local/tmp/llama.cpp/lib/`               │
│   │   ├── `libggml-cpu.so`       (ARM Oryon CPU backend, NEON/SVE/SVE2)   │
│   │   ├── `libggml-opencl.so`    (Adreno 840 GPU backend)                 │
│   │   ├── `libggml-hexagon.so`   (Hexagon HTP NPU backend, v81)           │
│   │   └── `libmtmd.so`           (Multimodal tensor processing library)   │
│   ├── Model Weights:       `/sdcard/Download/lfm3/`                       │
│   │   ├── `LFM3-VL-2B-Q4_K_M.gguf`    (Language weights, ~1.1 GB)         │
│   │   ├── `mmproj-LFM3-VL-2B-Q8_0.gguf` (Vision projector, ~194 MB)      │
│   │   └── `test-512.jpg`              (512px Samoyed evaluation image)    │
│   └── Tracing Facilities:  `/data/misc/perfetto-*`                        │
│       ├── Config Directory: `/data/misc/perfetto-configs/`                │
│       └── Trace Output:     `/data/misc/perfetto-traces/`                 │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Key Architectural Decisions (Why Not Just Bash?)

### ADR-1: Python Orchestration Over Shell Scripts
- **The Problem:** Many edge AI benchmarks rely on shell scripts filled with `adb shell "LD_LIBRARY_PATH=... llama-cli ..."` strings. When quoting breaks, spaces exist in arguments, or device connectivity drops, shell scripts fail silently or produce corrupted logs.
- **The Solution:** The `AdbClient` uses `subprocess.run(cmd_array)` without shell interpolation. Every command preserves exact return codes, execution timestamps, stdout, and stderr.

### ADR-2: Immutable Run Records & Reproducibility
- Every experiment execution generates an independent output directory:
  ```
  results/runs/<run-id>/
  ├── metadata.json   # Exact Git commit, device serial, model hashes, CLI parameters
  ├── trials.csv      # Trial-by-trial logs: timing, battery temp, thermal state, success
  ├── summary.json    # Statistical summary: median, mean, std, CV, P10, P90, speedups
  └── trace.pftrace   # Optional raw Perfetto trace synchronized to the workload
  ```
- No experiment overwrites past results. A benchmark without hardware telemetry and exact parameters is not scientifically reproducible.

### ADR-3: Offline-First Validation
- All parsers, statistical calculations, and configuration models are covered by unit tests in `tests/unit/`.
- CI runs 100% of the unit test suite without needing an Android phone attached. Physical hardware tests are marked with `@pytest.mark.hardware` and run on-demand.

---

## 3. Subsystem Breakdown

### 3.1 Device Detection & Diagnostics (`src/edge_lab/devices/`)
- **`AdbClient`:** Low-level bridge. Implements retry logic, timeout enforcement, path existence validation, and TCP port forwarding (`adb forward`).
- **`DeviceDetector`:** Probes Android system properties (`getprop`), CPU topology (`/sys/devices/system/cpu/`), memory stats (`/proc/meminfo`), battery details (`dumpsys battery`), and thermal status (`dumpsys thermalservice`).
- **Oryon CPU Topology Discovery:** Dynamically reads `cpuinfo_max_freq` across all CPU cores to construct physical core clusters. This proved that the Snapdragon 8 Elite has **zero efficiency cores**, consisting of 6 Performance cores (3.63 GHz) and 2 Prime cores (4.61 GHz).

### 3.2 Runtime Execution Adapter (`src/edge_lab/runtimes/`)
- **`SnapdragonRuntime`:** Encapsulates the deployed `llama.cpp` tools on Android.
- Configures environment variables:
  - `LD_LIBRARY_PATH`: points to `/data/local/tmp/llama.cpp/lib` so the dynamic linker finds `libggml-opencl.so` and `libmtmd.so`.
  - `GGML_OPENCL_KERNEL_CACHE_DIR`: points to `/data/local/tmp/llama.cpp/cl-cache` ensuring compiled OpenCL program binaries persist across runs.
- Controls hardware placement flags:
  - `-ngl 0 --no-mmproj-offload`: CPU language model + CPU vision projector.
  - `-ngl 99 --no-mmproj-offload --device GPUOpenCL`: Mixed execution (GPU LM + CPU projector).
  - `-ngl 99 --mmproj-offload --mmproj-device GPUOpenCL --device GPUOpenCL`: Full GPU offloading.

### 3.3 Output Parsing & Statistical Engine (`src/edge_lab/metrics/`)
- **`parser.py`:** Version-aware regular expression engine. Extracts:
  - Vision encoding latency (`mtmd batch encoding done in %d ms`)
  - Prompt processing throughput (`prompt eval time = %f ms / %d tokens`)
  - Generation throughput (`eval time = %f ms / %d runs`)
  - Device placement confirmations (`ggml_opencl: device: 'QUALCOMM Adreno(TM) 840'`)
  - CPU fallback warnings (`fallback to CPU detected`)
- **`stats.py`:** Calculates rigorous sample metrics:
  - **Median:** Primary metric for latency (resistant to outliers from GC pauses or OS scheduling spikes).
  - **Mean & Standard Deviation:** Variance indicator.
  - **Coefficient of Variation (CV = $\sigma / \mu$):** Measures benchmark stability. A CV < 0.05 indicates high stability.
  - **Speedup Ratios:** $\text{Speedup} = \text{Latency}_{\text{baseline}} / \text{Latency}_{\text{target}}$.
  - **Bootstrap Confidence Intervals:** 95% non-parametric bootstrap resampling over medians.

### 3.4 Hardware Telemetry & Safety (`src/edge_lab/telemetry/`)
- Mobile SoCs thermally throttle when pushed continuously.
- **`TelemetryMonitor`:** Takes snapshots before and after every trial.
- Monitors:
  - Battery temperature in tenths of a degree Celsius.
  - Android Thermal Throttling Status:
    - `0`: NONE (normal operation)
    - `1`: LIGHT
    - `2`: MODERATE
    - `3`: SEVERE (thermal abort triggered)
    - `4+`: CRITICAL / EMERGENCY
- If battery temperature exceeds safety limits (default 40.0°C–42.0°C), the experiment safely aborts and logs the reason.

### 3.5 System Tracing (`src/edge_lab/profiling/`)
- Integrates with Android 16's `/system/bin/perfetto`.
- Generates protobuf text (`.pbtx`) configurations selecting low-overhead kernel tracepoints:
  - `sched/sched_switch`, `sched/sched_wakeup`: CPU scheduling and thread migration.
  - `power/cpu_frequency`, `power/cpu_idle`: CPU DVFS transitions.
  - `linux.process_stats`: Thread names and process hierarchy.
- Trace analysis is automated via Python's `perfetto.trace_processor.TraceProcessor`, allowing SQL queries directly over `.pftrace` captures.
