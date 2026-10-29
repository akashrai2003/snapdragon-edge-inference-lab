"""Unit tests for statistical computations."""

from edge_lab.metrics.stats import compute_latency_ratio, compute_speedup, compute_statistics


def test_compute_statistics_known_values():
    # 5 runs of GPU projector from prompt: 2512, 2517, 2508, 2550, 2517
    values = [2512.0, 2517.0, 2508.0, 2550.0, 2517.0]
    stats = compute_statistics(values, compute_bootstrap_ci=False)

    assert stats.n == 5
    assert stats.median == 2517.0
    assert stats.min == 2508.0
    assert stats.max == 2550.0
    assert round(stats.mean, 1) == 2520.8
    assert stats.cv > 0.0


def test_compute_speedup():
    # Prefill: GPU 738.10 vs CPU 103.98
    # Baseline latency (CPU) / Target latency (GPU) is proportional to GPU_tps / CPU_tps
    cpu_latency = 1.0 / 103.98
    gpu_latency = 1.0 / 738.10
    speedup = compute_speedup(cpu_latency, gpu_latency)
    assert round(speedup, 2) == 7.10


def test_compute_latency_ratio():
    # 512px projector: GPU median 2517 ms vs Mixed median 1632 ms
    ratio = compute_latency_ratio(2517.0, 1632.0)
    assert ratio == 1.542
