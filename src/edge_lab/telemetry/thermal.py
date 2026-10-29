"""Continuous multi-zone thermal and per-core DVFS frequency profiler."""

import logging
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pandas as pd

from edge_lab.devices.adb import AdbClient

logger = logging.getLogger("thermal_profiler")


@dataclass
class ThermalSample:
    """Instantaneous multi-sensor thermal and DVFS snapshot."""

    timestamp: float
    elapsed_seconds: float
    battery_temp_celsius: float
    battery_current_ma: float | None
    thermal_status: int
    cpu_perf_temp_celsius: float  # Avg of Oryon Performance cores
    cpu_prime_temp_celsius: float  # Avg of Oryon Prime cores
    gpu_temp_celsius: float  # Avg of Adreno GPU sensors
    cpu_frequencies_mhz: dict[int, float] = field(default_factory=dict)
    raw_sensors: dict[str, float] = field(default_factory=dict)


class ContinuousThermalProfiler:
    """Background sampling thread for fine-grained thermal and DVFS tracking."""

    def __init__(
        self,
        adb: AdbClient,
        sample_interval_seconds: float = 0.5,
        max_battery_temp_celsius: float = 42.0,
    ):
        self.adb = adb
        self.sample_interval = sample_interval_seconds
        self.max_temp = max_battery_temp_celsius
        self.samples: list[ThermalSample] = []
        self._running = False
        self._thread: threading.Thread | None = None
        self._start_time = 0.0
        self._safety_triggered = False
        self._safety_reason: str | None = None

    def _sample_iteration(self) -> ThermalSample:
        now = time.time()
        elapsed = now - self._start_time

        # Run combined shell query to minimize ADB command roundtrips
        script = (
            "dumpsys battery | grep -E 'temperature|level|Battery current|current now';"
            "dumpsys thermalservice | grep 'Thermal Status';"
            'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo -n "$(basename $c):"; cat $c/cpufreq/scaling_cur_freq 2>/dev/null || echo 0; done;'
            'for tz in /sys/class/thermal/thermal_zone*; do echo -n "$(cat $tz/type 2>/dev/null):"; cat $tz/temp 2>/dev/null || echo 0; done'
        )
        res = self.adb.shell(script, timeout=4.0)

        bat_temp = 0.0
        current_ma = None
        thermal_status = 0
        freqs: dict[int, float] = {}
        sensors: dict[str, float] = {}

        if res.success:
            for line in res.stdout.splitlines():
                line = line.strip()
                if line.startswith("temperature:"):
                    try:
                        bat_temp = int(line.split(":")[1]) / 10.0
                    except ValueError:
                        pass
                elif "Battery current" in line or "current now" in line:
                    match = re.search(r"[-+]?\d+", line)
                    if match:
                        current_ma = int(match.group(0)) / 1000.0  # uA to mA
                elif "Thermal Status:" in line:
                    match = re.search(r"Thermal Status:\s*(\d+)", line)
                    if match:
                        thermal_status = int(match.group(1))
                elif line.startswith("cpu") and ":" in line and not line.startswith("cpullc"):
                    parts = line.split(":", 1)
                    if parts[0].startswith("cpu") and parts[0][3:].isdigit():
                        # CPU frequency line e.g. cpu0:3628800
                        core_idx = int(parts[0][3:])
                        try:
                            freq_val = int(parts[1]) / 1000.0  # kHz to MHz
                            freqs[core_idx] = freq_val
                        except ValueError:
                            pass
                    else:
                        # Thermal zone line e.g. cpu-0-2-1:36000
                        try:
                            sensors[parts[0]] = int(parts[1]) / 1000.0  # mC to C
                        except ValueError:
                            pass
                elif ":" in line:
                    parts = line.split(":", 1)
                    try:
                        sensors[parts[0]] = int(parts[1]) / 1000.0
                    except ValueError:
                        pass

        # Calculate cluster averages
        perf_temps = [v for k, v in sensors.items() if k.startswith("cpu-0-")]
        prime_temps = [v for k, v in sensors.items() if k.startswith("cpu-1-")]
        gpu_temps = [v for k, v in sensors.items() if k.startswith("gpuss-")]

        avg_perf = sum(perf_temps) / len(perf_temps) if perf_temps else bat_temp
        avg_prime = sum(prime_temps) / len(prime_temps) if prime_temps else bat_temp
        avg_gpu = sum(gpu_temps) / len(gpu_temps) if gpu_temps else bat_temp

        sample = ThermalSample(
            timestamp=now,
            elapsed_seconds=elapsed,
            battery_temp_celsius=bat_temp,
            battery_current_ma=current_ma,
            thermal_status=thermal_status,
            cpu_perf_temp_celsius=round(avg_perf, 1),
            cpu_prime_temp_celsius=round(avg_prime, 1),
            gpu_temp_celsius=round(avg_gpu, 1),
            cpu_frequencies_mhz=freqs,
            raw_sensors=sensors,
        )

        # Check safety
        if bat_temp > self.max_temp:
            self._safety_triggered = True
            self._safety_reason = (
                f"Battery temperature ({bat_temp}°C) exceeded safety limit ({self.max_temp}°C)"
            )
        elif thermal_status >= 3:
            self._safety_triggered = True
            self._safety_reason = f"Android Thermal Status reached SEVERE ({thermal_status})"

        return sample

    def _worker(self) -> None:
        while self._running and not self._safety_triggered:
            try:
                sample = self._sample_iteration()
                self.samples.append(sample)
            except Exception as e:
                logger.warning(f"Error sampling thermal state: {e}")
            time.sleep(self.sample_interval)

    def start(self) -> None:
        """Start the background thermal polling thread."""
        self.samples.clear()
        self._running = True
        self._safety_triggered = False
        self._safety_reason = None
        self._start_time = time.time()
        self._thread = threading.Thread(target=self._worker, daemon=True)
        self._thread.start()
        logger.info("Continuous thermal profiler thread started")

    def stop(self) -> list[ThermalSample]:
        """Stop background sampling and return gathered samples."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=3.0)
        logger.info(f"Continuous thermal profiler stopped. Gathered {len(self.samples)} samples")
        return self.samples

    @property
    def is_safe(self) -> tuple[bool, str | None]:
        return (not self._safety_triggered, self._safety_reason)

    def to_dataframe(self) -> pd.DataFrame:
        """Convert collected samples into a pandas DataFrame."""
        rows = []
        for s in self.samples:
            row: dict[str, Any] = {
                "elapsed_sec": round(s.elapsed_seconds, 2),
                "battery_temp_c": s.battery_temp_celsius,
                "battery_current_ma": s.battery_current_ma,
                "thermal_status": s.thermal_status,
                "cpu_perf_temp_c": s.cpu_perf_temp_celsius,
                "cpu_prime_temp_c": s.cpu_prime_temp_celsius,
                "gpu_temp_c": s.gpu_temp_celsius,
            }
            for core, freq in s.cpu_frequencies_mhz.items():
                row[f"cpu{core}_mhz"] = freq
            rows.append(row)
        return pd.DataFrame(rows)

    def save_csv(self, output_path: Path) -> Path:
        """Export collected thermal samples to CSV."""
        df = self.to_dataframe()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        df.to_csv(output_path, index=False)
        return output_path
