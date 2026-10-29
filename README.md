# Snapdragon Edge Inference Lab

[![Tests](https://github.com/akashrai2003/snapdragon-edge-inference-lab/actions/workflows/tests.yml/badge.svg)](https://github.com/akashrai2003/snapdragon-edge-inference-lab/actions/workflows/tests.yml)
[![Lint](https://github.com/akashrai2003/snapdragon-edge-inference-lab/actions/workflows/lint.yml/badge.svg)](https://github.com/akashrai2003/snapdragon-edge-inference-lab/actions/workflows/lint.yml)
[![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)](https://www.python.org/downloads/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A research-grade edge AI inference benchmarking, profiling, and optimization platform designed for Qualcomm Snapdragon mobile platforms.

---

## 🔬 Core Mission

This project provides an automated, reproducible platform to investigate and optimize edge LLM and VLM inference across ARM Oryon CPUs, Qualcomm Adreno GPUs, and Qualcomm Hexagon NPUs.

### The Immediate Scientific Question
When running Liquid AI's **LFM3-VL-2B** on a **Snapdragon 8 Elite (SM8850)**, why does CPU vision-projector execution outperform requested Adreno 840 GPU projector offloading by up to **54% at a 512px image resolution**, and does this relationship change under scaling resolution or persistent-server execution?

```
┌────────────────────────────────────────────────────────────────────────┐
│  512px Vision Projector Latency (Snapdragon 8 Elite / Adreno 840)      │
├────────────────────────────────┬───────────────────┬──────────────────┤
│ Configuration                  │ Median Latency    │ Comparison       │
├────────────────────────────────┼───────────────────┼──────────────────┤
│ Mixed (GPU LM + CPU Vision)    │ 1632 ms           │ 1.00× (Fastest)  │
│ CPU Only (CPU LM + CPU Vision) │ 1952 ms           │ 1.20× slower     │
│ Full GPU (GPU LM + GPU Vision) │ 2517 ms           │ 1.54× slower     │
└────────────────────────────────┴───────────────────┴──────────────────┘
```

---

## 📱 Hardware & Environment

- **Target Device:** Realme GT 8 Pro (`RMX5210` / `RMX5210IN`)
- **SoC:** Qualcomm SM8850 / Snapdragon 8 Elite Gen 5 (`canoe` platform)
- **CPU:** 8 Qualcomm Oryon cores
  - 6 Performance cores (up to 3.63 GHz)
  - 2 Prime cores (up to 4.61 GHz)
- **GPU:** Qualcomm Adreno 840 (OpenCL 3.0, 7555 MiB capacity)
- **NPU:** Qualcomm Hexagon HTP (v81 architecture, FastRPC `nsp1000`)
- **RAM:** 16 GB (~14.76 GiB reported, ~7.0 GiB available)
- **OS:** Android 16 (Non-rooted, stock retail)
- **Primary Model:** [Liquid AI LFM3-VL-2B](https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF) (Language: `Q4_K_M` ~1.1GB, Vision Projector: `Q8_0` ~194MB)

---

## 📊 Established Baseline Findings

### 1. Text Component Throughput (pp512 vs. tg128)
![Throughput Comparison](results/baseline/throughput_comparison.png)

- **Prompt Processing (pp512):** Adreno 840 GPU OpenCL delivers **738.10 ± 4.67 tokens/sec**, representing a **7.10× speedup** over Snapdragon cross-compiled CPU inference (103.98 t/s).
- **Text Generation (tg128):** Adreno 840 GPU achieves **44.48 ± 0.52 tokens/sec**, a **1.43× speedup** over CPU decode (31.12 t/s).

### 2. Vision Projector Placement Anomaly (512px Input)
![Vision Projector Placement](results/baseline/vision_projector_placement.png)

Explicitly offloading the vision projector to the Adreno GPU (`--mmproj-offload --mmproj-device GPUOpenCL`) resulted in **2517 ms median latency**, whereas keeping the vision projector on CPU took only **1632 ms median latency**.

---

## 🛠️ Research Workstreams

- **Workstream A (Resolution Crossover):** Evaluate image encoding latency scaling from 256px to 1024px to determine if memory bandwidth vs. compute density produces a crossover point where GPU vision becomes faster.
- **Workstream B (Cold-Start vs. Persistent Server):** Disentangle process startup, OpenCL context creation, and kernel compilation by comparing standalone binary invocations (`llama-mtmd-cli`) against a persistent in-memory server (`llama-server`) via HTTP completions.
- **Workstream C (Perfetto Hardware Profiling):** Capture kernel CPU scheduling slices, frequency transitions, context switches, and memory states on stock Android 16 using native Perfetto tracing and TraceProcessor SQL analytics.

---

## 🚀 Quickstart

### Prerequisites
- Ubuntu 22.04+ workstation with Python 3.11+ and `uv` or `pip`
- Android phone with USB debugging enabled (`adb devices -l`)

### Installation
```bash
git clone https://github.com/akashrai2003/snapdragon-edge-inference-lab.git
cd snapdragon-edge-inference-lab

# Setup virtual environment with uv
uv venv --python 3.11 .venv
source .venv/bin/activate
uv pip install -e ".[dev]"
```

### 1. Run Hardware Doctor
Verify connected phone, Snapdragon SoC, Oryon CPU topology, battery, thermals, and deployed binaries:
```bash
edge-lab doctor
```

### 2. Inspect Perfetto Capabilities
Check stock Android tracing data sources (`ftrace`, `process_stats`, `power`, `gpu.memory`):
```bash
edge-lab profile doctor
```

### 3. Generate Baseline Reports
Import physical baseline results and render Markdown, HTML, and PNG charts:
```bash
edge-lab results import-baseline
edge-lab results report
```

### 4. Execute a Safe Hardware Smoke Test
Run a single controlled 512px trial comparing Mixed and Full GPU vision paths:
```bash
edge-lab benchmark resolution --config configs/experiments/smoke-test.yaml --output results/runs/smoke-test
```

### 5. Record & Analyze a Perfetto Trace
Capture a 5-second CPU scheduling trace and query core frequency distribution:
```bash
edge-lab profile record --duration 5 --output results/profiling/trace.pftrace
edge-lab profile analyze --trace results/profiling/trace.pftrace
```

---

## 🧪 Testing

The repository includes a comprehensive offline unit test suite that validates configuration schemas, log parsers, and statistical calculations without requiring physical Android hardware:

```bash
pytest
ruff check .
ruff format --check .
```

---

## 📄 Repository Structure

```
snapdragon-edge-inference-lab/
├── configs/
│   ├── devices/realme-gt8-pro.yaml      # Device hardware & Oryon CPU topology
│   ├── models/lfm3-vl-2b.yaml           # LFM3-VL-2B model definitions & paths
│   ├── runtimes/                        # CPU, OpenCL, and Hexagon configurations
│   └── experiments/                     # Resolution, cold-warm, and smoke test configs
├── src/edge_lab/
│   ├── cli.py                           # Typer CLI application (edge-lab)
│   ├── config/                          # Pydantic schemas and YAML loaders
│   ├── devices/                         # Robust ADB wrapper and hardware detector
│   ├── runtimes/                        # Snapdragon llama.cpp execution adapter
│   ├── experiments/                     # Workstreams A & B runners
│   ├── profiling/                       # Perfetto tracing and TraceProcessor SQL
│   ├── telemetry/                       # Battery, thermal, and memory monitor
│   ├── metrics/                         # Output parsers and statistical engine
│   └── reporting/                       # Markdown, HTML, and Matplotlib charts
├── perfetto/
│   ├── configs/                         # Valid protobuf text (.pbtx) trace profiles
│   └── sql/                             # TraceProcessor SQL queries
├── tests/
│   ├── unit/                            # Unit tests
│   └── fixtures/                        # Real benchmark log fixtures
├── docs/
│   ├── project-status.md                # Live project status and milestone tracking
│   ├── decisions.md                     # Architectural Decision Records (ADRs)
│   └── experiment-log.md                # Ledger of physical hardware runs
├── results/
│   ├── baseline/                        # Imported physical baseline measurements & charts
│   └── runs/                            # Immutable run outputs (trials.csv, summary.json)
└── AGENTS.md                            # Operational guide for AI coding agents
```

---

## 📜 License & Attribution

- **License:** MIT License. See [LICENSE](LICENSE) for details.
- **Model Attribution:** [Liquid AI LFM3-VL-2B](https://www.liquid.ai/blog/lfm3).
- **Runtime Attribution:** [llama.cpp](https://github.com/ggml-org/llama.cpp) (Pinned Snapdragon package commit `ac8e169`).
- **Notice:** This repository contains no proprietary Qualcomm SDK binaries or copyrighted model weights.
