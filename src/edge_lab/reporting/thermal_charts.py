"""Thermal trajectory and DVFS clock frequency visualizers."""

from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


def generate_thermal_trajectory_chart(thermal_csv_path: Path, output_chart_path: Path) -> Path:
    """Generate dual-panel time-series chart of multi-zone temperatures and CPU core frequencies."""
    df = pd.read_csv(thermal_csv_path)
    if df.empty or "elapsed_sec" not in df.columns:
        raise ValueError(f"Invalid or empty thermal timeseries CSV: {thermal_csv_path}")

    plt.style.use(
        "seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default"
    )
    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(11, 8), dpi=200, sharex=True)

    t = df["elapsed_sec"]

    # 1. Upper Panel: Multi-zone Temperatures
    if "cpu_prime_temp_c" in df.columns:
        ax1.plot(
            t,
            df["cpu_prime_temp_c"],
            label="Oryon Prime Cores (6-7)",
            color="#E53E3E",
            lw=2.2,
        )
    if "cpu_perf_temp_c" in df.columns:
        ax1.plot(
            t,
            df["cpu_perf_temp_c"],
            label="Oryon Perf Cores (0-5)",
            color="#DD6B20",
            lw=2.0,
        )
    if "gpu_temp_c" in df.columns:
        ax1.plot(t, df["gpu_temp_c"], label="Adreno 840 GPU", color="#805AD5", lw=2.0)
    if "battery_temp_c" in df.columns:
        ax1.plot(
            t,
            df["battery_temp_c"],
            label="Battery Temp",
            color="#2B6CB0",
            lw=2.2,
            ls="--",
        )

    ax1.axhline(
        y=42.0,
        color="#E53E3E",
        ls=":",
        lw=1.5,
        alpha=0.8,
        label="Safety Threshold (42°C)",
    )
    ax1.set_ylabel("Temperature (°C)", fontsize=11, fontweight="bold")
    ax1.set_title(
        "Snapdragon 8 Elite Thermal Trajectory During Sustained Inference",
        fontsize=13,
        fontweight="bold",
        pad=10,
    )
    ax1.legend(loc="upper left", frameon=True, fontsize=9)
    ax1.grid(True, alpha=0.3)

    # 2. Lower Panel: Core DVFS Frequencies
    prime_cols = [c for c in df.columns if c in ("cpu6_mhz", "cpu7_mhz")]
    perf_cols = [c for c in df.columns if c in [f"cpu{i}_mhz" for i in range(6)]]

    if prime_cols:
        avg_prime_freq = df[prime_cols].mean(axis=1) / 1000.0  # to GHz
        ax2.plot(t, avg_prime_freq, label="Prime Cores Frequency (GHz)", color="#E53E3E", lw=2.0)
    if perf_cols:
        avg_perf_freq = df[perf_cols].mean(axis=1) / 1000.0  # to GHz
        ax2.plot(t, avg_perf_freq, label="Perf Cores Frequency (GHz)", color="#3182CE", lw=2.0)

    ax2.set_xlabel("Elapsed Time (Seconds)", fontsize=11, fontweight="bold")
    ax2.set_ylabel("Clock Frequency (GHz)", fontsize=11, fontweight="bold")
    ax2.set_title(
        "Dynamic Voltage & Frequency Scaling (DVFS) Active Governor Response",
        fontsize=12,
        fontweight="bold",
        pad=10,
    )
    ax2.set_ylim(0, 5.0)
    ax2.legend(loc="lower left", frameon=True, fontsize=9)
    ax2.grid(True, alpha=0.3)

    plt.tight_layout()
    output_chart_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output_chart_path, bbox_inches="tight")
    plt.close()
    return output_chart_path
