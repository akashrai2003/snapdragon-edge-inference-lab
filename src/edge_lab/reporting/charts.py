"""Publication-quality chart generation for benchmarks and profiling analysis."""

from pathlib import Path
from typing import Any

import matplotlib.pyplot as plt
import numpy as np


def generate_baseline_throughput_chart(baseline_data: dict[str, Any], output_path: Path) -> Path:
    """Generate bar chart comparing CPU vs GPU throughput for pp512 and tg128."""
    plt.style.use(
        "seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default"
    )
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4.8), dpi=200)

    # Data
    categories = [
        "Termux CPU\n(Native)",
        "Snapdragon CPU\n(Cross-compiled)",
        "Adreno GPU\n(OpenCL -ngl 99)",
    ]
    pp512_values = [
        baseline_data["text_benchmarks"]["termux_cpu"]["pp512_tokens_per_sec"],
        baseline_data["text_benchmarks"]["snapdragon_cpu"]["pp512_tokens_per_sec"],
        baseline_data["text_benchmarks"]["snapdragon_gpu"]["pp512_tokens_per_sec"],
    ]
    pp512_errors = [
        baseline_data["text_benchmarks"]["termux_cpu"]["pp512_std"],
        baseline_data["text_benchmarks"]["snapdragon_cpu"]["pp512_std"],
        baseline_data["text_benchmarks"]["snapdragon_gpu"]["pp512_std"],
    ]

    tg128_values = [
        baseline_data["text_benchmarks"]["termux_cpu"]["tg128_tokens_per_sec"],
        baseline_data["text_benchmarks"]["snapdragon_cpu"]["tg128_tokens_per_sec"],
        baseline_data["text_benchmarks"]["snapdragon_gpu"]["tg128_tokens_per_sec"],
    ]
    tg128_errors = [
        baseline_data["text_benchmarks"]["termux_cpu"]["tg128_std"],
        baseline_data["text_benchmarks"]["snapdragon_cpu"]["tg128_std"],
        baseline_data["text_benchmarks"]["snapdragon_gpu"]["tg128_std"],
    ]

    colors = ["#4A5568", "#3182CE", "#38A169"]

    # PP512 Plot
    bars1 = ax1.bar(
        categories, pp512_values, yerr=pp512_errors, capsize=5, color=colors, alpha=0.9, width=0.55
    )
    ax1.set_ylabel("Throughput (Tokens / sec)", fontsize=11, fontweight="bold")
    ax1.set_title("Prompt Processing (pp512) Throughput", fontsize=12, fontweight="bold", pad=12)
    for bar in bars1:
        yval = bar.get_height()
        ax1.text(
            bar.get_x() + bar.get_width() / 2.0,
            yval + 15,
            f"{yval:.1f} t/s",
            ha="center",
            va="bottom",
            fontsize=10,
            fontweight="bold",
        )
    ax1.set_ylim(0, max(pp512_values) * 1.18)

    # TG128 Plot
    bars2 = ax2.bar(
        categories, tg128_values, yerr=tg128_errors, capsize=5, color=colors, alpha=0.9, width=0.55
    )
    ax2.set_ylabel("Throughput (Tokens / sec)", fontsize=11, fontweight="bold")
    ax2.set_title("Text Generation (tg128) Throughput", fontsize=12, fontweight="bold", pad=12)
    for bar in bars2:
        yval = bar.get_height()
        ax2.text(
            bar.get_x() + bar.get_width() / 2.0,
            yval + 1.2,
            f"{yval:.1f} t/s",
            ha="center",
            va="bottom",
            fontsize=10,
            fontweight="bold",
        )
    ax2.set_ylim(0, max(tg128_values) * 1.22)

    plt.suptitle(
        "LFM3-VL-2B (Q4_K_M) on Snapdragon 8 Elite (SM8850)", fontsize=14, fontweight="bold", y=1.02
    )
    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, bbox_inches="tight")
    plt.close()
    return output_path


def generate_vision_placement_chart(projector_data: dict[str, Any], output_path: Path) -> Path:
    """Generate chart comparing the 3 vision projector placement configurations at 512px."""
    plt.style.use(
        "seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default"
    )
    fig, ax = plt.subplots(figsize=(8.5, 5.2), dpi=200)

    labels = [
        "CPU LM +\nCPU Vision",
        "GPU LM +\nCPU Vision (Mixed)",
        "GPU LM +\nGPU Vision",
    ]
    medians = [
        projector_data["cpu_lm_cpu_vision"]["median_ms"],
        projector_data["gpu_lm_cpu_vision"]["median_ms"],
        projector_data["gpu_lm_gpu_vision"]["median_ms"],
    ]
    raw_runs = [
        projector_data["cpu_lm_cpu_vision"]["runs_ms"],
        projector_data["gpu_lm_cpu_vision"]["runs_ms"],
        projector_data["gpu_lm_gpu_vision"]["runs_ms"],
    ]

    colors = ["#4A5568", "#2B6CB0", "#E53E3E"]
    bars = ax.bar(
        labels, medians, color=colors, alpha=0.85, width=0.45, edgecolor="black", linewidth=1
    )

    # Overlay scatter points for individual trials
    for i, runs in enumerate(raw_runs):
        x = np.random.normal(i, 0.04, size=len(runs))
        ax.scatter(x, runs, color="black", s=30, alpha=0.7, zorder=3)

    ax.set_ylabel("Vision Encoding Latency (ms) — Lower is Better", fontsize=11, fontweight="bold")
    ax.set_title(
        "512px Vision Projector Latency: CPU vs Adreno GPU Offloading",
        fontsize=13,
        fontweight="bold",
        pad=14,
    )

    for bar, med in zip(bars, medians):
        ax.text(
            bar.get_x() + bar.get_width() / 2.0,
            med + 60,
            f"{med:.0f} ms",
            ha="center",
            va="bottom",
            fontsize=11,
            fontweight="bold",
        )

    # Add speedup annotations
    mixed_med = medians[1]
    gpu_med = medians[2]
    slowdown_pct = ((gpu_med / mixed_med) - 1.0) * 100
    ax.text(
        1.5,
        2100,
        f"GPU Projector is +{slowdown_pct:.1f}% SLOWER\nthan Mixed Execution (1.54×)",
        ha="center",
        bbox=dict(boxstyle="round,pad=0.6", facecolor="#FFF5F5", edgecolor="#FEB2B2", alpha=0.9),
        fontsize=10,
        fontweight="bold",
        color="#C53030",
    )

    ax.set_ylim(1000, 2900)
    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, bbox_inches="tight")
    plt.close()
    return output_path


def generate_resolution_scaling_chart(res_data: dict[str, Any], output_path: Path) -> Path:
    """Generate line chart for resolution scaling."""
    plt.style.use(
        "seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default"
    )
    fig, ax = plt.subplots(figsize=(8.5, 5.0), dpi=200)

    resolutions = sorted(
        [int(k.replace("res_", "")) for k in res_data.keys() if k.startswith("res_")]
    )
    cpu_latencies = []
    gpu_latencies = []

    for r in resolutions:
        key = f"res_{r}"
        cfg_cpu = res_data[key].get("gpu_lm_cpu_vision", {})
        cfg_gpu = res_data[key].get("gpu_all", {})
        cpu_latencies.append(cfg_cpu.get("median", np.nan))
        gpu_latencies.append(cfg_gpu.get("median", np.nan))

    ax.plot(
        resolutions,
        cpu_latencies,
        marker="o",
        linewidth=2.5,
        markersize=8,
        label="CPU Vision Projector",
        color="#2B6CB0",
    )
    ax.plot(
        resolutions,
        gpu_latencies,
        marker="s",
        linewidth=2.5,
        markersize=8,
        label="Adreno GPU Vision Projector",
        color="#E53E3E",
    )

    ax.set_xlabel("Input Image Max Dimension (px)", fontsize=11, fontweight="bold")
    ax.set_ylabel("Vision Encoding Latency (ms)", fontsize=11, fontweight="bold")
    ax.set_title(
        "Resolution Scaling: Vision Projector Latency (LFM3-VL-2B)",
        fontsize=13,
        fontweight="bold",
        pad=12,
    )
    ax.legend(frameon=True, fontsize=10)
    plt.tight_layout()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_path, bbox_inches="tight")
    plt.close()
    return output_path
