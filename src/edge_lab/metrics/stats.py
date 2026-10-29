"""Statistical computation utilities for benchmark latency and throughput distributions."""

from dataclasses import dataclass

import numpy as np


@dataclass
class SummaryStatistics:
    """Statistical summary of measured sample distribution."""

    n: int
    mean: float
    median: float
    std_dev: float
    min: float
    max: float
    cv: float  # Coefficient of variation (std_dev / mean)
    p10: float
    p90: float
    ci95_low: float | None = None
    ci95_high: float | None = None


def compute_statistics(
    values: list[float],
    compute_bootstrap_ci: bool = True,
    n_bootstrap: int = 2000,
    seed: int = 42,
) -> SummaryStatistics:
    """Compute complete statistical summary for a sample of latency or throughput values."""
    if not values:
        raise ValueError("Cannot compute statistics on an empty list")

    arr = np.array(values, dtype=float)
    n = len(arr)
    mean_val = float(np.mean(arr))
    median_val = float(np.median(arr))
    min_val = float(np.min(arr))
    max_val = float(np.max(arr))
    std_val = float(np.std(arr, ddof=1)) if n > 1 else 0.0
    cv_val = (std_val / mean_val) if mean_val != 0 else 0.0
    p10_val = float(np.percentile(arr, 10))
    p90_val = float(np.percentile(arr, 90))

    ci_low = None
    ci_high = None
    if compute_bootstrap_ci and n >= 3:
        rng = np.random.default_rng(seed)
        boot_medians = [
            float(np.median(rng.choice(arr, size=n, replace=True))) for _ in range(n_bootstrap)
        ]
        ci_low = float(np.percentile(boot_medians, 2.5))
        ci_high = float(np.percentile(boot_medians, 97.5))

    return SummaryStatistics(
        n=n,
        mean=round(mean_val, 3),
        median=round(median_val, 3),
        std_dev=round(std_val, 3),
        min=round(min_val, 3),
        max=round(max_val, 3),
        cv=round(cv_val, 4),
        p10=round(p10_val, 3),
        p90=round(p90_val, 3),
        ci95_low=round(ci_low, 3) if ci_low is not None else None,
        ci95_high=round(ci_high, 3) if ci_high is not None else None,
    )


def compute_speedup(baseline_latency: float, target_latency: float) -> float:
    """Compute relative speedup: baseline_latency / target_latency.

    Values > 1.0 indicate target is faster than baseline.
    Values < 1.0 indicate target is slower than baseline.
    """
    if target_latency <= 0:
        raise ValueError("Target latency must be positive")
    return round(baseline_latency / target_latency, 3)


def compute_latency_ratio(slower_latency: float, faster_latency: float) -> float:
    """Compute percentage increase in latency: (slower / faster)."""
    if faster_latency <= 0:
        raise ValueError("Faster latency must be positive")
    return round(slower_latency / faster_latency, 3)
