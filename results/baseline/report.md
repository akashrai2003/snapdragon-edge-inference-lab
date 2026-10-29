# Snapdragon Edge Inference Lab — Baseline Engineering Report

**Device:** Realme GT 8 Pro (RMX5210 / SM8850 Snapdragon 8 Elite)
**Host:** Ubuntu 22.04.5 LTS (x86_64)
**Model:** Liquid AI LFM3-VL-2B (Language: Q4_K_M ~1.73B params, Projector: Q8_0 ~194MB)
**Runtimes Evaluated:** llama.cpp native Termux (b11967) vs Snapdragon cross-compiled package (ac8e169)

## 1. Executive Summary

- **Prefill Throughput:** Adreno 840 GPU OpenCL delivers **738.10 ± 4.67 tokens/sec**, which is **7.10× faster** than cross-compiled ARM CPU inference (103.98 t/s).
- **Decode Throughput:** Adreno 840 GPU achieves **44.48 ± 0.52 tokens/sec**, representing a **1.43× speedup** over CPU decode (31.12 t/s).
- **Vision Projector Latency Anomaly:** For 512px image vision encoding, explicitly offloading the vision projector to the Adreno GPU (`--mmproj-offload --mmproj-device GPUOpenCL`) resulted in **2517 ms median latency**, whereas keeping the vision projector on CPU while language decoding on GPU took only **1632 ms median latency**.
- **GPU Projector Penalty:** GPU projector offloading took **54% longer (+885 ms)** than mixed execution.

## 2. Text Benchmark Measurements (Physical Device)

| Runtime / Backend | Device Target | Threads | pp512 Throughput (t/s) | tg128 Throughput (t/s) | Repetitions |
|---|---|---|---|---|---|
| Termux Native (b11967) | CPU (Oryon) | 4 | 68.34 ± 5.39 | 25.93 ± 0.16 | 5 |
| Snapdragon Cross (ac8e169) | CPU (Oryon) | 4 | 103.98 ± 11.61 | 31.12 ± 0.32 | 3 |
| Snapdragon Cross (ac8e169) | Adreno 840 GPUOpenCL | 4 | 738.1 ± 4.67 | 44.48 ± 0.52 | 3 |

## 3. Multimodal Vision Projector Latency (512px Input)

| Configuration | Parameters | Measured Trials (ms) | Median (ms) | Relative to Mixed |
|---|---|---|---|---|
| CPU LM + CPU Vision | `-ngl 0 --no-mmproj-offload` | 1952 1579 1590 1953 1977 | **1952 ms** | 1.20× slower |
| GPU LM + CPU Vision (Mixed) | `-ngl 99 --no-mmproj-offload` | 1718 1632 1570 1594 1725 | **1632 ms** | **1.00× (Baseline)** |
| GPU LM + GPU Vision | `-ngl 99 --mmproj-offload --mmproj-device GPUOpenCL` | 2512 2517 2508 2550 2517 | **2517 ms** | **1.54× slower (+54%)** |

## 4. Scientific Hypotheses Under Investigation

1. **Workstream A (Resolution Scaling):** At smaller image token counts (e.g. 256px), CPU kernel launch and memory bandwidth may dominate, but does GPU matmul crossover at higher resolutions (768px, 1024px)?
2. **Workstream B (Persistent vs Cold):** Is the +885 ms GPU penalty due to cold OpenCL program compilation / kernel caching / buffer allocation in separate process execution (`llama-mtmd-cli`), or does it persist in steady-state serving (`llama-server`)?
3. **Workstream C (Hardware Profiling):** Does Perfetto system tracing reveal CPU thread synchronization stalls, memory copy bottlenecks over FastRPC/shared buffers, or thermal frequency drops?
