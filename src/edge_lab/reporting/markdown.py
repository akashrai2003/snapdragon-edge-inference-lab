"""Markdown technical report generation."""

from pathlib import Path
from typing import Any


def generate_baseline_markdown_report(data: dict[str, Any], output_path: Path) -> Path:
    """Generate Markdown technical baseline report."""
    md = []
    md.append("# Snapdragon Edge Inference Lab — Baseline Engineering Report\n")
    md.append("**Device:** Realme GT 8 Pro (RMX5210 / SM8850 Snapdragon 8 Elite)")
    md.append("**Host:** Ubuntu 22.04.5 LTS (x86_64)")
    md.append(
        "**Model:** Liquid AI LFM3-VL-2B (Language: Q4_K_M ~1.73B params, Projector: Q8_0 ~194MB)"
    )
    md.append(
        "**Runtimes Evaluated:** llama.cpp native Termux (b11967) vs Snapdragon cross-compiled package (ac8e169)\n"
    )
    md.append("## 1. Executive Summary\n")
    md.append(
        "- **Prefill Throughput:** Adreno 840 GPU OpenCL delivers **738.10 ± 4.67 tokens/sec**, which is **7.10× faster** than cross-compiled ARM CPU inference (103.98 t/s)."
    )
    md.append(
        "- **Decode Throughput:** Adreno 840 GPU achieves **44.48 ± 0.52 tokens/sec**, representing a **1.43× speedup** over CPU decode (31.12 t/s)."
    )
    md.append(
        "- **Vision Projector Latency Anomaly:** For 512px image vision encoding, explicitly offloading the vision projector to the Adreno GPU (`--mmproj-offload --mmproj-device GPUOpenCL`) resulted in **2517 ms median latency**, whereas keeping the vision projector on CPU while language decoding on GPU took only **1632 ms median latency**."
    )
    md.append(
        "- **GPU Projector Penalty:** GPU projector offloading took **54% longer (+885 ms)** than mixed execution.\n"
    )

    md.append("## 2. Text Benchmark Measurements (Physical Device)\n")
    md.append(
        "| Runtime / Backend | Device Target | Threads | pp512 Throughput (t/s) | tg128 Throughput (t/s) | Repetitions |"
    )
    md.append("|---|---|---|---|---|---|")

    tb = data.get("text_benchmarks", {})
    t_cpu = tb.get("termux_cpu", {})
    s_cpu = tb.get("snapdragon_cpu", {})
    s_gpu = tb.get("snapdragon_gpu", {})

    md.append(
        f"| Termux Native (b11967) | CPU (Oryon) | {t_cpu.get('threads', 4)} | {t_cpu.get('pp512_tokens_per_sec')} ± {t_cpu.get('pp512_std')} | {t_cpu.get('tg128_tokens_per_sec')} ± {t_cpu.get('tg128_std')} | {t_cpu.get('repetitions')} |"
    )
    md.append(
        f"| Snapdragon Cross (ac8e169) | CPU (Oryon) | {s_cpu.get('threads', 4)} | {s_cpu.get('pp512_tokens_per_sec')} ± {s_cpu.get('pp512_std')} | {s_cpu.get('tg128_tokens_per_sec')} ± {s_cpu.get('tg128_std')} | {s_cpu.get('repetitions')} |"
    )
    md.append(
        f"| Snapdragon Cross (ac8e169) | Adreno 840 GPUOpenCL | {s_gpu.get('threads', 4)} | {s_gpu.get('pp512_tokens_per_sec')} ± {s_gpu.get('pp512_std')} | {s_gpu.get('tg128_tokens_per_sec')} ± {s_gpu.get('tg128_std')} | {s_gpu.get('repetitions')} |\n"
    )

    md.append("## 3. Multimodal Vision Projector Latency (512px Input)\n")
    md.append(
        "| Configuration | Parameters | Measured Trials (ms) | Median (ms) | Relative to Mixed |"
    )
    md.append("|---|---|---|---|---|")

    vp = data.get("vision_projector_512px", {})
    c1 = vp.get("cpu_lm_cpu_vision", {})
    c2 = vp.get("gpu_lm_cpu_vision", {})
    c3 = vp.get("gpu_lm_gpu_vision", {})

    c1_runs = " ".join([str(x) for x in c1.get("runs_ms", [])])
    c2_runs = " ".join([str(x) for x in c2.get("runs_ms", [])])
    c3_runs = " ".join([str(x) for x in c3.get("runs_ms", [])])

    md.append(
        f"| CPU LM + CPU Vision | `-ngl 0 --no-mmproj-offload` | {c1_runs} | **{c1.get('median_ms')} ms** | 1.20× slower |"
    )
    md.append(
        f"| GPU LM + CPU Vision (Mixed) | `-ngl 99 --no-mmproj-offload` | {c2_runs} | **{c2.get('median_ms')} ms** | **1.00× (Baseline)** |"
    )
    md.append(
        f"| GPU LM + GPU Vision | `-ngl 99 --mmproj-offload --mmproj-device GPUOpenCL` | {c3_runs} | **{c3.get('median_ms')} ms** | **1.54× slower (+54%)** |\n"
    )

    md.append("## 4. Scientific Hypotheses Under Investigation\n")
    md.append(
        "1. **Workstream A (Resolution Scaling):** At smaller image token counts (e.g. 256px), CPU kernel launch and memory bandwidth may dominate, but does GPU matmul crossover at higher resolutions (768px, 1024px)?"
    )
    md.append(
        "2. **Workstream B (Persistent vs Cold):** Is the +885 ms GPU penalty due to cold OpenCL program compilation / kernel caching / buffer allocation in separate process execution (`llama-mtmd-cli`), or does it persist in steady-state serving (`llama-server`)?"
    )
    md.append(
        "3. **Workstream C (Hardware Profiling):** Does Perfetto system tracing reveal CPU thread synchronization stalls, memory copy bottlenecks over FastRPC/shared buffers, or thermal frequency drops?\n"
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    return output_path
