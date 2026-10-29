# AGENTS.md — Snapdragon Edge Inference Lab

This document defines operating instructions, architectural guidelines, safety rules, and reproducibility standards for coding agents working on the `snapdragon-edge-inference-lab` repository.

---

## 1. Environment & Paths

### Host Machine (Ubuntu 22.04 LTS x86_64)
- **Repository root:** `/home/akash-ai/Desktop/Projects/snapdragon-edge-inference-lab`
- **Virtual environment:** `/home/akash-ai/Desktop/Projects/snapdragon-edge-inference-lab/.venv` (Python 3.11.16)
- **Upstream llama.cpp source:** `/home/akash-ai/edge-inference/llama.cpp` (pinned commit `ac8e169`)
- **ADB binary:** `/usr/bin/adb`
- **Termux SSH script helper:** `/home/akash-ai/bin/phone-ssh`

### Android Hardware Target (Realme GT 8 Pro)
- **Model / Product:** `RMX5210` / `RMX5210IN`
- **SoC:** Qualcomm SM8850 / Snapdragon 8 Elite Gen 5 (canoe platform)
- **OS:** Android 16 (64-bit ARM / `arm64-v8a`)
- **CPU Topology:** 8 Qualcomm Oryon cores
  - Cores 0–5 (Performance cluster): max 3.63 GHz (3628800 kHz)
  - Cores 6–7 (Prime cluster): max 4.61 GHz (4608000 kHz)
- **GPU:** Qualcomm Adreno 840 (OpenCL 3.0)
- **NPU:** Qualcomm Hexagon HTP (Arch v81, FastRPC `nsp1000`)
- **RAM:** 16 GB physical (~14.76 GiB reported, ~7.0 GiB available)
- **Root Status:** **NOT rooted**. Do NOT attempt rooting or modifying system partitions.
- **Device Paths:**
  - Runtime binaries: `/data/local/tmp/llama.cpp/bin/` (`llama-bench`, `llama-mtmd-cli`, `llama-server`)
  - Runtime libraries: `/data/local/tmp/llama.cpp/lib/` (`libggml-cpu.so`, `libggml-opencl.so`, `libggml-hexagon.so`, `libmtmd.so`)
  - OpenCL cache: `/data/local/tmp/llama.cpp/cl-cache/`
  - Model weights: `/sdcard/Download/lfm3/` (`LFM3-VL-2B-Q4_K_M.gguf`, `mmproj-LFM3-VL-2B-Q8_0.gguf`, `test-512.jpg`)
  - Perfetto traces: `/data/misc/perfetto-traces/` (standard Android permission path)
  - Perfetto configs: `/data/misc/perfetto-configs/`

---

## 2. Safety Constraints

1. **Thermal Protection:** Never override Android thermal protections or CPU governors. Default safety abort is 40.0°C–42.0°C battery temperature or `dumpsys thermalservice` status >= 3.
2. **Docker Safety:** Do NOT prune Docker images, delete containers, or mutate Docker daemons on the host (the host runs unrelated critical workloads).
3. **Repository Boundaries:** Do NOT copy the upstream llama.cpp source tree into this repository. Do NOT commit multi-gigabyte `.gguf` weights or proprietary `.so` libraries to Git.
4. **Process Cleanup:** Every ADB process, background server, or Perfetto trace must be cleanly terminated upon completion or failure.

---

## 3. Benchmark Integrity & Scientific Standards

- **Strict Separation of Data:**
  - *Historical imported baseline:* Measured on physical phone prior to repository initialization.
  - *Newly measured result:* Generated and validated by physical execution via `edge-lab`.
  - *Derived statistic:* Clearly calculated from measured samples (mean, median, CV, speedup).
  - *Hypothesis:* Clearly stated open research question.
- **Never fabricate results:** If a metric is not exposed by a runtime log, mark it `None` / unavailable.
- **Controlled Trials:** Always perform warmup trials separately from measured trials. Randomize or alternate configuration order to minimize thermal bias.

---

## 4. How to Run & Validate

### Activate Virtual Environment
```bash
source .venv/bin/activate
```

### Run Tests and Linting
```bash
pytest
ruff check .
ruff format --check .
```

### Validate Hardware Environment
```bash
edge-lab doctor
edge-lab profile doctor
```

### Run Baseline Reports
```bash
edge-lab results import-baseline
edge-lab results report
```

### Run Controlled Hardware Smoke Test
```bash
edge-lab benchmark resolution --config configs/experiments/smoke-test.yaml --output results/runs/smoke-test
```
