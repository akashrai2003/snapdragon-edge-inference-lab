# Snapdragon Edge Inference Lab — Documentation Index

Welcome to the technical documentation and learning repository for the Snapdragon Edge Inference Lab.

These guides are written to build senior-level expertise in edge AI systems engineering, mobile accelerator profiling, and low-level runtime optimization.

---

## 📚 Learning Curriculum & Master Guide

### 1. [System Architecture & Software Design](architecture/system-design.md)
- Complete design breakdown of the Python orchestrator.
- Subsystem deep dives: `AdbClient`, `DeviceDetector`, `SnapdragonRuntime`, `TelemetryMonitor`, `parser.py`, `stats.py`.
- Architectural decision records (ADRs) explaining design rationale.

### 2. [Edge AI Inference on Qualcomm Snapdragon](methodology/edge-inference-deep-dive.md)
- **Unified Memory Architecture (UMA) vs. Discrete GPU VRAM:** Why mobile memory is shared physically but separated virtually.
- **Hardware Engine Comparison:** Qualcomm Oryon CPU vs Adreno 840 GPU vs Hexagon HTP v81 NPU.
- **The Roofline Model:** Arithmetic intensity ($I = \text{FLOPs} / \text{Byte}$) and why text prefill (`pp512`) is compute-bound while decode (`tg128`) is memory-bound.
- **Liquid AI LFM3 Architecture:** Hybrid convolution + selective recurrent state + attention mechanics.

### 3. [The Vision Projector Anomaly: Technical Root-Cause Analysis](findings/vision-projector-anomaly-analysis.md)
- Physical benchmark evidence: Why GPU vision takes 2517 ms vs 1632 ms on CPU.
- The 4 core engineering hypotheses:
  1. Kernel dispatch overhead on small tensor batches.
  2. Buffer ping-ponging & CPU fallback synchronization.
  3. OpenCL context & JIT initialization.
  4. Arithmetic intensity scaling across image resolutions (the Crossover Hypothesis).

### 4. [Android Perfetto Systems Profiling Guide](profiling/perfetto-systems-guide.md)
- How Linux kernel `ftrace` captures `sched_switch`, `sched_wakeup`, and `cpu_frequency`.
- Android 16 permission model: Zero-root tracing using `/data/misc/perfetto-*`.
- Automated TraceProcessor SQL analysis in Python.
- Visualizing traces at [https://ui.perfetto.dev/](https://ui.perfetto.dev/).

### 5. [Thermal Throttling, DVFS Governors, and Concurrent Edge Inference](profiling/thermal-throttling-and-concurrency.md)
- Physical testing of Snapdragon 8 Elite under sustained multimodal document load.
- Multi-zone thermal dynamics: Adreno 840 climbing to 97.4°C vs Oryon cores at 79.5°C vs battery insulation.
- The 50-second thermal cliff: Latency stretch from 19.1s to 28.6s (+49.7%) under thermal soak.
- Multi-client continuous batching (`--cont-batching`, `-np 2`, `--kv-unified`) in `llama-server`.
- Engineering mitigations: thermal duty cycling, adaptive resolution routing, and asymmetric offload.

### 6. [Upstream Contribution & C++ Instrumentation Guide](contributing-upstream/fork-and-instrumentation-guide.md)
- Source code roadmap: where multimodal code lives in `llama.cpp` (`tools/mtmd/` and `ggml-opencl.cpp`).
- Setting up a fork and adding it as a Git submodule.
- Compiling instrumented binaries using the Snapdragon Docker toolchain without breaking working builds.
- Adding custom C++ timing markers and submitting an upstream PR.

### 6. Operational & Project Tracking
- [Project Status](project-status.md): Live component status and verified physical evidence.
- [Architecture Decisions](decisions.md): Formal record of key decisions.
- [Experiment Execution Log](experiment-log.md): Chronological ledger of physical hardware benchmarks.
- [AI Coding Agent Guidelines](../AGENTS.md): Operating instructions for future AI pair programmers.
