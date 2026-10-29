"""Unit tests for thermal profiler, stress testing logic, and charting."""

from pathlib import Path
from unittest.mock import MagicMock

import pandas as pd
import pytest

from edge_lab.devices.adb import AdbResult
from edge_lab.reporting.thermal_charts import generate_thermal_trajectory_chart
from edge_lab.telemetry.thermal import ContinuousThermalProfiler


def test_thermal_profiler_parsing():
    """Verify that ADB shell outputs are properly parsed into multi-zone temperatures and CPU freqs."""
    mock_adb = MagicMock()
    mock_output = (
        "temperature: 345\n"
        "level: 85\n"
        "Battery current: -450000\n"
        "Thermal Status: 0\n"
        "cpu0: 1824000\n"
        "cpu1: 1824000\n"
        "cpu6: 4128000\n"
        "cpu7: 4608000\n"
        "cpu-0-0-0: 38200\n"
        "cpu-0-1-0: 38500\n"
        "cpu-1-0-0: 44100\n"
        "cpu-1-1-0: 45200\n"
        "gpuss-0: 41000\n"
    )
    mock_adb.shell.return_value = AdbResult(
        command=["shell"], returncode=0, stdout=mock_output, stderr="", duration_seconds=0.1
    )

    profiler = ContinuousThermalProfiler(mock_adb, sample_interval_seconds=0.1)
    sample = profiler._sample_iteration()

    assert sample.battery_temp_celsius == 34.5
    assert sample.battery_current_ma == -450.0
    assert sample.thermal_status == 0
    assert sample.cpu_frequencies_mhz[0] == 1824.0
    assert sample.cpu_frequencies_mhz[7] == 4608.0
    assert sample.cpu_perf_temp_celsius == pytest.approx(38.35, abs=0.1)
    assert sample.cpu_prime_temp_celsius == pytest.approx(44.65, abs=0.1)
    assert sample.gpu_temp_celsius == 41.0
    assert profiler.is_safe[0] is True


def test_thermal_profiler_safety_abort():
    """Verify that battery temperatures exceeding 42°C trigger immediate safety abort flag."""
    mock_adb = MagicMock()
    mock_output = (
        "temperature: 432\n"  # 43.2°C
        "Thermal Status: 2\n"
    )
    mock_adb.shell.return_value = AdbResult(
        command=["shell"], returncode=0, stdout=mock_output, stderr="", duration_seconds=0.1
    )

    profiler = ContinuousThermalProfiler(mock_adb, max_battery_temp_celsius=42.0)
    profiler._sample_iteration()

    safe, reason = profiler.is_safe
    assert safe is False
    assert "exceeded safety limit" in reason


def test_generate_thermal_trajectory_chart(tmp_path: Path):
    """Test generating chart from synthesized thermal time-series."""
    csv_file = tmp_path / "test_thermal.csv"
    data = {
        "elapsed_sec": [0.0, 1.0, 2.0, 3.0],
        "battery_temp_c": [34.0, 34.2, 34.5, 34.8],
        "cpu_perf_temp_c": [40.0, 42.0, 43.5, 44.0],
        "cpu_prime_temp_c": [42.0, 45.0, 47.0, 48.5],
        "gpu_temp_c": [38.0, 41.0, 43.0, 44.0],
        "cpu0_mhz": [1800, 3600, 3600, 3600],
        "cpu6_mhz": [2000, 4400, 4200, 4100],
        "cpu7_mhz": [2000, 4500, 4300, 4000],
    }
    df = pd.DataFrame(data)
    df.to_csv(csv_file, index=False)

    chart_file = tmp_path / "trajectory.png"
    out = generate_thermal_trajectory_chart(csv_file, chart_file)

    assert out.exists()
    assert out.stat().st_size > 1000
