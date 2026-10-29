# Changelog

All notable changes to the Snapdragon Edge Inference Lab will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-10-29

### Added
- **Repository Architecture:** Initialized Python project structure with Typer CLI, Pydantic configuration schemas, and offline pytest validation.
- **Hardware Doctor (`edge-lab doctor`):** Automated diagnosis of host, ADB, Realme GT 8 Pro (Qualcomm SM8850), 8-core Oryon topology (6 Perf @ 3.63 GHz, 2 Prime @ 4.61 GHz), memory, battery, thermal status, and deployed Snapdragon libraries.
- **Baseline Results & Reporting:** Imported physical baseline measurements for CPU vs GPU text throughput (pp512 and tg128) and 512px vision projector placement anomaly. Automated generation of static HTML report, Markdown summary, and Matplotlib comparison charts.
- **Workstream A Runner (`ResolutionCrossoverExperiment`):** Automated resolution scaling runner with warmup trials, randomized scheduling, cooldown intervals, and thermal abort.
- **Workstream B Runner (`ColdVsWarmExperiment`):** Runner comparing cold-process invocation (`llama-mtmd-cli`) and persistent HTTP serving (`llama-server`) with port forwarding.
- **Workstream C Profiler (`PerfettoProfiler`):** Integration with stock Android 16 Perfetto tracing (`/data/misc/perfetto-*`) and automated SQL analytics using Perfetto TraceProcessor.
- **Physical Hardware Smoke Test:** Verified live execution on Realme GT 8 Pro, validating Mixed (CPU vision: 1391 ms) vs Full GPU (GPU vision: 1814 ms) paths on device.
