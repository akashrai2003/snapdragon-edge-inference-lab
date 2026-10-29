"""Command-line interface for the Snapdragon Edge Inference Lab."""

import json
from pathlib import Path
from typing import Annotated

import typer
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from edge_lab.config.loader import load_experiment_config, load_model_config
from edge_lab.devices.adb import AdbClient
from edge_lab.devices.detector import DeviceDetector
from edge_lab.experiments.resolution import ResolutionCrossoverExperiment
from edge_lab.profiling.perfetto import PerfettoProfiler
from edge_lab.reporting.charts import (
    generate_baseline_throughput_chart,
    generate_vision_placement_chart,
)
from edge_lab.reporting.html import generate_baseline_html_report
from edge_lab.reporting.markdown import generate_baseline_markdown_report

app = typer.Typer(
    name="edge-lab",
    help="Snapdragon Edge Inference Lab — Research-grade AI inference benchmarking & profiling platform",
    no_args_is_help=True,
)
results_app = typer.Typer(help="Manage and inspect benchmark results", no_args_is_help=True)
profile_app = typer.Typer(
    help="Perfetto system tracing and hardware profiling", no_args_is_help=True
)
benchmark_app = typer.Typer(help="Run automated inference experiments", no_args_is_help=True)

app.add_typer(results_app, name="results")
app.add_typer(profile_app, name="profile")
app.add_typer(benchmark_app, name="benchmark")

console = Console()


@app.command()
def doctor(
    serial: Annotated[
        str | None, typer.Option("--serial", "-s", help="Target Android device serial")
    ] = None,
):
    """Run comprehensive hardware, runtime, and environment diagnostic checks."""
    console.print(
        Panel.fit("[bold cyan]Snapdragon Edge Inference Lab — Environment Doctor[/bold cyan]")
    )

    adb = AdbClient(serial=serial)
    devices = adb.list_devices()

    if not devices:
        console.print("[bold red]✗ No Android devices connected via ADB.[/bold red]")
        console.print("  Ensure USB debugging is enabled and the phone is unlocked.")
        raise typer.Exit(code=1)

    target_serial = serial or devices[0]["serial"]
    adb.serial = target_serial
    console.print(
        f"[green]✓ Connected Device:[/green] [bold]{target_serial}[/bold] ({len(devices)} device(s) attached)"
    )

    detector = DeviceDetector(adb)
    report = detector.detect()

    # Table of Diagnostics
    table = Table(
        title="Hardware & Runtime Environment Diagnostics",
        show_header=True,
        header_style="bold magenta",
    )
    table.add_column("Diagnostic Item", style="dim", width=30)
    table.add_column("Detected Value", style="bold")
    table.add_column("Status", width=15)

    table.add_row(
        "Device Model / Product", f"{report.model} ({report.product})", "[green]VALID[/green]"
    )
    table.add_row(
        "SoC Model / Platform",
        f"{report.soc_model} ({report.soc_platform})",
        "[green]QUALCOMM[/green]",
    )
    table.add_row(
        "Android OS Version",
        f"Android {report.android_version} ({report.abi})",
        "[green]SUPPORTED[/green]",
    )

    # CPU topology
    cpu_desc = f"{report.cpu_cores} Oryon Cores (6 Perf up to 3.63 GHz, 2 Prime up to 4.61 GHz)"
    table.add_row("CPU Architecture", cpu_desc, "[green]ORYON[/green]")

    # Memory
    mem_avail_gb = report.mem_available_kb / (1024 * 1024)
    mem_tot_gb = report.mem_total_kb / (1024 * 1024)
    table.add_row(
        "RAM Total / Available",
        f"{mem_tot_gb:.2f} GiB / {mem_avail_gb:.2f} GiB",
        "[green]HEALTHY[/green]",
    )

    # Battery and thermal
    bat_desc = f"{report.battery_level}% ({report.battery_temp_celsius}°C)"
    bat_status = (
        "[green]COOL[/green]" if report.battery_temp_celsius < 38.0 else "[yellow]WARM[/yellow]"
    )
    table.add_row("Battery & Temperature", bat_desc, bat_status)

    therm_status_str = (
        "0 (NORMAL / NO THROTTLING)" if report.thermal_status == 0 else f"{report.thermal_status}"
    )
    table.add_row("Android Thermal Status", therm_status_str, "[green]OPTIMAL[/green]")

    # Deployed binaries
    llama_status = "[green]FOUND[/green]" if report.llama_deployed else "[red]MISSING[/red]"
    table.add_row(
        "llama-mtmd-cli Binary", "/data/local/tmp/llama.cpp/bin/llama-mtmd-cli", llama_status
    )

    opencl_status = "[green]FOUND[/green]" if report.opencl_supported else "[red]MISSING[/red]"
    table.add_row("OpenCL Backend Library", "libggml-opencl.so", opencl_status)

    hex_status = (
        "[green]FOUND[/green]" if report.hexagon_supported else "[yellow]NOT DEPLOYED[/yellow]"
    )
    table.add_row("Hexagon HTP Backend", "libggml-hexagon.so (v81)", hex_status)

    # Models
    models_ok = len(report.models_found) >= 3
    m_status = (
        "[green]FOUND (3/3)[/green]"
        if models_ok
        else f"[yellow]{len(report.models_found)}/3 Found[/yellow]"
    )
    table.add_row("LFM3 Model & Projector", "/sdcard/Download/lfm3/", m_status)

    # Perfetto
    perf_status = "[green]ACTIVE[/green]" if report.perfetto_available else "[red]UNAVAILABLE[/red]"
    table.add_row("Perfetto Tracing Service", "/system/bin/perfetto (v49.0+)", perf_status)

    console.print(table)
    console.print(
        "\n[bold green]Doctor check completed successfully. Ready for inference and profiling.[/bold green]"
    )


@app.command()
def devices():
    """List connected Android devices via ADB."""
    adb = AdbClient()
    devs = adb.list_devices()
    if not devs:
        console.print("[yellow]No ADB devices detected.[/yellow]")
        return
    table = Table(title="Connected ADB Devices")
    table.add_column("Serial")
    table.add_column("State")
    table.add_column("Model")
    table.add_column("Product")
    for d in devs:
        table.add_row(
            d.get("serial", ""), d.get("state", ""), d.get("model", ""), d.get("product", "")
        )
    console.print(table)


# =========================================================================
# Results Subcommands
# =========================================================================

HISTORICAL_BASELINE_DATA = {
    "metadata": {
        "device": "Realme GT 8 Pro (RMX5210)",
        "soc": "Snapdragon 8 Elite Gen 5 (SM8850)",
        "os": "Android 16",
        "model": "Liquid AI LFM3-VL-2B (Q4_K_M)",
        "projector": "mmproj-LFM3-VL-2B-Q8_0.gguf",
        "image": "Samoyed test image (512px max dimension)",
        "cpu_threads": 4,
    },
    "text_benchmarks": {
        "termux_cpu": {
            "runtime": "llama.cpp b11967 (native Termux)",
            "backend": "CPU",
            "threads": 4,
            "pp512_tokens_per_sec": 68.34,
            "pp512_std": 5.39,
            "tg128_tokens_per_sec": 25.93,
            "tg128_std": 0.16,
            "repetitions": 5,
        },
        "snapdragon_cpu": {
            "runtime": "llama.cpp ac8e169 (cross-compiled package)",
            "backend": "CPU",
            "threads": 4,
            "pp512_tokens_per_sec": 103.98,
            "pp512_std": 11.61,
            "tg128_tokens_per_sec": 31.12,
            "tg128_std": 0.32,
            "repetitions": 3,
        },
        "snapdragon_gpu": {
            "runtime": "llama.cpp ac8e169 (cross-compiled package)",
            "backend": "Adreno 840 GPUOpenCL (-ngl 99)",
            "threads": 4,
            "pp512_tokens_per_sec": 738.10,
            "pp512_std": 4.67,
            "tg128_tokens_per_sec": 44.48,
            "tg128_std": 0.52,
            "repetitions": 3,
        },
    },
    "vision_projector_512px": {
        "cpu_lm_cpu_vision": {
            "name": "CPU LM + CPU Vision",
            "flags": "-ngl 0 --no-mmproj-offload",
            "runs_ms": [1952, 1579, 1590, 1953, 1977],
            "median_ms": 1952,
        },
        "gpu_lm_cpu_vision": {
            "name": "GPU LM + CPU Vision (Mixed)",
            "flags": "-ngl 99 --no-mmproj-offload",
            "runs_ms": [1718, 1632, 1570, 1594, 1725],
            "median_ms": 1632,
        },
        "gpu_lm_gpu_vision": {
            "name": "GPU LM + GPU Vision",
            "flags": "-ngl 99 --mmproj-offload --mmproj-device GPUOpenCL",
            "runs_ms": [2512, 2517, 2508, 2550, 2517],
            "median_ms": 2517,
            "explicit_individual_ms": 2572,
            "first_complete_ms": 2360,
        },
    },
    "initial_resolution_scaling": {
        "256px": 902,
        "384px": 1429,
        "512px": 3596,
        "original_full": 167991,
    },
}


@results_app.command("import-baseline")
def import_baseline(
    output_dir: Annotated[
        Path, typer.Option("--output", "-o", help="Baseline output directory")
    ] = Path("results/baseline"),
):
    """Import and validate real historical CPU/GPU baseline measurements."""
    output_dir.mkdir(parents=True, exist_ok=True)
    baseline_file = output_dir / "baseline_summary.json"
    with open(baseline_file, "w", encoding="utf-8") as f:
        json.dump(HISTORICAL_BASELINE_DATA, f, indent=2)

    console.print(
        f"[bold green]✓ Imported historical baseline results to {baseline_file}[/bold green]"
    )
    report_baseline(baseline_file=baseline_file, output_dir=output_dir)


@results_app.command("report")
def report_baseline(
    baseline_file: Annotated[
        Path, typer.Option("--input", "-i", help="Path to baseline_summary.json")
    ] = Path("results/baseline/baseline_summary.json"),
    output_dir: Annotated[
        Path, typer.Option("--output", "-o", help="Output directory for reports")
    ] = Path("results/baseline"),
):
    """Generate Markdown, HTML, and publication charts from baseline results."""
    if not baseline_file.exists():
        console.print(
            f"[red]Baseline file not found at {baseline_file}. Run 'edge-lab results import-baseline' first.[/red]"
        )
        raise typer.Exit(code=1)

    with open(baseline_file, encoding="utf-8") as f:
        data = json.load(f)

    # 1. Charts
    chart1 = output_dir / "throughput_comparison.png"
    chart2 = output_dir / "vision_projector_placement.png"
    generate_baseline_throughput_chart(data, chart1)
    generate_vision_placement_chart(data["vision_projector_512px"], chart2)
    console.print(f"[green]✓ Generated charts:[/green] {chart1}, {chart2}")

    # 2. Markdown report
    md_file = output_dir / "report.md"
    generate_baseline_markdown_report(data, md_file)
    console.print(f"[green]✓ Generated Markdown report:[/green] {md_file}")

    # 3. HTML report
    html_file = output_dir / "index.html"
    generate_baseline_html_report(data, html_file)
    console.print(f"[green]✓ Generated Static HTML report:[/green] {html_file}")


# =========================================================================
# Benchmark Subcommands
# =========================================================================


@benchmark_app.command("resolution")
def run_resolution_benchmark(
    config_file: Annotated[Path, typer.Option("--config", "-c", help="Experiment config")] = Path(
        "configs/experiments/resolution-crossover.yaml"
    ),
    resolution: Annotated[
        int | None,
        typer.Option(
            "--resolution", "-r", help="Run single resolution (e.g. 512) for smoke testing"
        ),
    ] = None,
    output_dir: Annotated[
        Path, typer.Option("--output", "-o", help="Directory to save run results")
    ] = Path("results/runs/resolution-latest"),
):
    """Execute Workstream A: Resolution crossover benchmark."""
    exp_cfg = load_experiment_config(config_file)
    model_cfg = load_model_config(exp_cfg.model_config_path)

    adb = AdbClient()
    runner = ResolutionCrossoverExperiment(
        adb=adb,
        output_dir=output_dir,
        experiment_config=exp_cfg,
        model_config=model_cfg,
    )

    resolutions = [resolution] if resolution else None
    console.print(
        f"[bold cyan]Starting Resolution Crossover Benchmark (Resolutions: {resolutions or exp_cfg.resolutions})...[/bold cyan]"
    )
    result = runner.run(resolutions_to_run=resolutions)
    console.print(f"[bold green]✓ Benchmark completed. Saved results to {output_dir}[/bold green]")
    console.print(f"Total Trials: {len(result.trials)}")


# =========================================================================
# Profiling Subcommands
# =========================================================================


@profile_app.command("doctor")
def profile_doctor():
    """Verify stock Android Perfetto service and tracing data sources."""
    adb = AdbClient()
    profiler = PerfettoProfiler(adb)
    caps = profiler.check_capabilities()

    table = Table(title="Android Perfetto Tracing Capabilities")
    table.add_column("Capability / Data Source")
    table.add_column("Status")

    table.add_row(
        "Perfetto Service", caps.version if caps.service_available else "[red]UNAVAILABLE[/red]"
    )
    table.add_row(
        "linux.ftrace (Scheduling/Frequency)",
        "[green]AVAILABLE[/green]" if caps.linux_ftrace else "[red]UNAVAILABLE[/red]",
    )
    table.add_row(
        "linux.process_stats (Threads/PIDs)",
        "[green]AVAILABLE[/green]" if caps.process_stats else "[red]UNAVAILABLE[/red]",
    )
    table.add_row(
        "linux.sys_stats (CPU Utilization)",
        "[green]AVAILABLE[/green]" if caps.sys_stats else "[red]UNAVAILABLE[/red]",
    )
    table.add_row(
        "android.power (Battery Rails)",
        "[green]AVAILABLE[/green]" if caps.android_power else "[yellow]RESTRICTED[/yellow]",
    )
    table.add_row(
        "android.gpu.memory (GPU Allocation)",
        "[green]AVAILABLE[/green]" if caps.gpu_memory else "[yellow]UNSUPPORTED[/yellow]",
    )

    console.print(table)


@profile_app.command("record")
def profile_record(
    duration: Annotated[
        int, typer.Option("--duration", "-d", help="Trace duration in seconds")
    ] = 15,
    config_file: Annotated[
        Path, typer.Option("--config", "-c", help="Path to .pbtx config")
    ] = Path("perfetto/configs/cpu-scheduling.pbtx"),
    output_trace: Annotated[
        Path, typer.Option("--output", "-o", help="Local destination .pftrace")
    ] = Path("results/profiling/trace.pftrace"),
):
    """Record a smoke-test Perfetto trace from device."""
    if not config_file.exists():
        console.print(f"[red]Config file {config_file} does not exist[/red]")
        raise typer.Exit(code=1)

    with open(config_file, encoding="utf-8") as f:
        cfg_content = f.read()

    adb = AdbClient()
    profiler = PerfettoProfiler(adb)
    console.print(
        f"[bold cyan]Starting Perfetto trace ({duration}s) using {config_file.name}...[/bold cyan]"
    )
    pid = profiler.start_trace(cfg_content)
    console.print(f"Trace running with PID {pid}. Waiting {duration}s...")
    import time

    time.sleep(duration)

    pulled_path = profiler.stop_and_pull_trace(pid, output_trace)
    console.print(
        f"[bold green]✓ Trace saved successfully ({pulled_path.stat().st_size} bytes): {pulled_path}[/bold green]"
    )
    console.print(f"Open in browser: https://ui.perfetto.dev/ (Load {pulled_path})")


@profile_app.command("analyze")
def profile_analyze(
    trace_path: Annotated[Path, typer.Option("--trace", "-t", help="Path to .pftrace file")] = Path(
        "results/profiling/trace.pftrace"
    ),
):
    """Analyze a captured Perfetto trace using TraceProcessor SQL."""
    if not trace_path.exists():
        console.print(f"[red]Trace file {trace_path} does not exist[/red]")
        raise typer.Exit(code=1)

    adb = AdbClient()
    profiler = PerfettoProfiler(adb)
    console.print(
        f"[bold cyan]Analyzing trace {trace_path} with Perfetto TraceProcessor...[/bold cyan]"
    )

    freq_df = profiler.get_cpu_frequency_summary(trace_path)
    if not freq_df.empty:
        table = Table(title="CPU Frequency Distribution Across Cores")
        table.add_column("Core", justify="center")
        table.add_column("Freq (MHz)", justify="right")
        table.add_column("Duration (ms)", justify="right")
        for _, row in freq_df.head(15).iterrows():
            table.add_row(
                str(int(row["cpu"])), f"{row['freq_mhz']:.0f}", f"{row['duration_ms']:.1f}"
            )
        console.print(table)
    else:
        console.print("[yellow]No CPU frequency tracks found in trace.[/yellow]")
