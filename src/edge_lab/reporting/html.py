"""Static HTML report generator for Snapdragon Edge Inference Lab."""

from pathlib import Path
from typing import Any


def generate_baseline_html_report(data: dict[str, Any], output_path: Path) -> Path:
    """Generate self-contained static HTML report."""
    tb = data.get("text_benchmarks", {})
    t_cpu = tb.get("termux_cpu", {})
    s_cpu = tb.get("snapdragon_cpu", {})
    s_gpu = tb.get("snapdragon_gpu", {})

    vp = data.get("vision_projector_512px", {})
    c1 = vp.get("cpu_lm_cpu_vision", {})
    c2 = vp.get("gpu_lm_cpu_vision", {})
    c3 = vp.get("gpu_lm_gpu_vision", {})

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Snapdragon Edge Inference Lab — Benchmark Report</title>
    <style>
        :root {{
            --bg-color: #0f172a;
            --card-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --border: #334155;
            --primary: #38bdf8;
            --accent-green: #4ade80;
            --accent-red: #f87171;
            --accent-blue: #60a5fa;
        }}
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background-color: var(--bg-color);
            color: var(--text-main);
            margin: 0;
            padding: 2.5rem 1.5rem;
            line-height: 1.6;
        }}
        .container {{
            max-width: 1040px;
            margin: 0 auto;
        }}
        header {{
            margin-bottom: 2.5rem;
            border-bottom: 1px solid var(--border);
            padding-bottom: 1.5rem;
        }}
        h1 {{
            font-size: 2.2rem;
            color: var(--primary);
            margin-bottom: 0.5rem;
        }}
        .meta-badges {{
            display: flex;
            flex-wrap: wrap;
            gap: 0.75rem;
            margin-top: 1rem;
        }}
        .badge {{
            background: #334155;
            padding: 0.35rem 0.85rem;
            border-radius: 9999px;
            font-size: 0.85rem;
            color: #e2e8f0;
        }}
        .badge.highlight {{
            background: #0369a1;
            color: #bae6fd;
        }}
        .card {{
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 1.75rem;
            margin-bottom: 2rem;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.2);
        }}
        h2 {{
            font-size: 1.4rem;
            color: #e2e8f0;
            margin-top: 0;
            margin-bottom: 1.25rem;
            border-bottom: 1px solid #334155;
            padding-bottom: 0.5rem;
        }}
        table {{
            width: 100%;
            border-collapse: collapse;
            margin: 1.25rem 0;
            font-size: 0.95rem;
        }}
        th, td {{
            text-align: left;
            padding: 0.85rem 1rem;
            border-bottom: 1px solid var(--border);
        }}
        th {{
            background-color: #0f172a;
            color: #94a3b8;
            font-weight: 600;
        }}
        .stat-highlight {{
            font-weight: bold;
            color: var(--accent-green);
        }}
        .stat-warning {{
            font-weight: bold;
            color: var(--accent-red);
        }}
        .kpi-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 1.25rem;
            margin-bottom: 1.5rem;
        }}
        .kpi {{
            background: #0f172a;
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 1.25rem;
            text-align: center;
        }}
        .kpi-val {{
            font-size: 1.9rem;
            font-weight: bold;
            color: var(--primary);
            margin: 0.4rem 0;
        }}
        .kpi-label {{
            font-size: 0.85rem;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.05em;
        }}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>Snapdragon Edge Inference Lab</h1>
            <p>Physical hardware performance characterization of <strong>Liquid AI LFM3-VL-2B</strong> on <strong>Qualcomm Snapdragon 8 Elite</strong>.</p>
            <div class="meta-badges">
                <span class="badge highlight">Device: Realme GT 8 Pro (SM8850)</span>
                <span class="badge">GPU: Adreno 840 (OpenCL 3.0)</span>
                <span class="badge">NPU: Hexagon HTP v81</span>
                <span class="badge">OS: Android 16</span>
                <span class="badge">Model: LFM3-VL-2B Q4_K_M</span>
            </div>
        </header>

        <section class="card">
            <h2>Key Performance Indicators</h2>
            <div class="kpi-grid">
                <div class="kpi">
                    <div class="kpi-label">GPU Prefill Speedup</div>
                    <div class="kpi-val">7.10×</div>
                    <div class="kpi-label">738.10 vs 103.98 t/s</div>
                </div>
                <div class="kpi">
                    <div class="kpi-label">GPU Decode Speedup</div>
                    <div class="kpi-val">1.43×</div>
                    <div class="kpi-label">44.48 vs 31.12 t/s</div>
                </div>
                <div class="kpi">
                    <div class="kpi-label">GPU Projector Slowdown</div>
                    <div class="kpi-val" style="color: var(--accent-red);">+54%</div>
                    <div class="kpi-label">2517 ms vs 1632 ms</div>
                </div>
                <div class="kpi">
                    <div class="kpi-label">Device RAM Capacity</div>
                    <div class="kpi-val">14.76 GiB</div>
                    <div class="kpi-label">~7.0 GiB Available</div>
                </div>
            </div>
        </section>

        <section class="card">
            <h2>1. Text Component Throughput Benchmarks</h2>
            <table>
                <thead>
                    <tr>
                        <th>Runtime / Environment</th>
                        <th>Device Target</th>
                        <th>Prompt Processing (pp512)</th>
                        <th>Generation (tg128)</th>
                        <th>Speedup vs Termux</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>Termux Native (b11967)</td>
                        <td>Oryon CPU (4T)</td>
                        <td>{t_cpu.get("pp512_tokens_per_sec")} ± {t_cpu.get("pp512_std")} t/s</td>
                        <td>{t_cpu.get("tg128_tokens_per_sec")} ± {t_cpu.get("tg128_std")} t/s</td>
                        <td>1.00× (Baseline)</td>
                    </tr>
                    <tr>
                        <td>Snapdragon Cross (ac8e169)</td>
                        <td>Oryon CPU (4T)</td>
                        <td>{s_cpu.get("pp512_tokens_per_sec")} ± {s_cpu.get("pp512_std")} t/s</td>
                        <td>{s_cpu.get("tg128_tokens_per_sec")} ± {s_cpu.get("tg128_std")} t/s</td>
                        <td>1.52× (pp) / 1.20× (tg)</td>
                    </tr>
                    <tr>
                        <td>Snapdragon Cross (ac8e169)</td>
                        <td>Adreno 840 GPU (-ngl 99)</td>
                        <td class="stat-highlight">{s_gpu.get("pp512_tokens_per_sec")} ± {s_gpu.get("pp512_std")} t/s</td>
                        <td class="stat-highlight">{s_gpu.get("tg128_tokens_per_sec")} ± {s_gpu.get("tg128_std")} t/s</td>
                        <td class="stat-highlight">10.80× (pp) / 1.72× (tg)</td>
                    </tr>
                </tbody>
            </table>
        </section>

        <section class="card">
            <h2>2. Multimodal Vision Projector Latency (512px Image)</h2>
            <table>
                <thead>
                    <tr>
                        <th>Configuration</th>
                        <th>CLI Parameters</th>
                        <th>Raw Measured Trials (ms)</th>
                        <th>Median Latency</th>
                        <th>Comparison</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td>CPU LM + CPU Vision</td>
                        <td><code>-ngl 0 --no-mmproj-offload</code></td>
                        <td>{" ".join(str(x) for x in c1.get("runs_ms", []))}</td>
                        <td>{c1.get("median_ms")} ms</td>
                        <td>1.20× vs Mixed</td>
                    </tr>
                    <tr>
                        <td>GPU LM + CPU Vision (Mixed)</td>
                        <td><code>-ngl 99 --no-mmproj-offload</code></td>
                        <td>{" ".join(str(x) for x in c2.get("runs_ms", []))}</td>
                        <td class="stat-highlight">{c2.get("median_ms")} ms</td>
                        <td class="stat-highlight">Fastest Configuration</td>
                    </tr>
                    <tr>
                        <td>GPU LM + GPU Vision</td>
                        <td><code>-ngl 99 --mmproj-offload --mmproj-device GPUOpenCL</code></td>
                        <td>{" ".join(str(x) for x in c3.get("runs_ms", []))}</td>
                        <td class="stat-warning">{c3.get("median_ms")} ms</td>
                        <td class="stat-warning">54% Slower than Mixed (+885 ms)</td>
                    </tr>
                </tbody>
            </table>
        </section>
    </div>
</body>
</html>
"""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html_content)
    return output_path
