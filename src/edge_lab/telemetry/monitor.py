"""Telemetry subsystem for capturing battery, thermal, and memory state before and after trials."""

import re
import time
from dataclasses import dataclass

from edge_lab.devices.adb import AdbClient


@dataclass
class TelemetrySnapshot:
    timestamp: float
    battery_level: int
    battery_temp_celsius: float
    battery_current_ua: int | None
    battery_voltage_uv: int | None
    is_charging: bool
    thermal_status: int
    mem_available_kb: int
    cpu_frequencies_khz: dict[int, int]


class TelemetryMonitor:
    """Collects hardware telemetry and enforces safety thresholds."""

    def __init__(self, adb: AdbClient, max_temp_celsius: float = 42.0):
        self.adb = adb
        self.max_temp_celsius = max_temp_celsius

    def capture(self) -> TelemetrySnapshot:
        """Capture an instantaneous snapshot of device telemetry."""
        now = time.time()
        bat_level = -1
        bat_temp = -1.0
        current_ua = None
        voltage_uv = None
        is_charging = False
        thermal_status = -1
        mem_avail = -1
        cpu_freqs: dict[int, int] = {}

        # 1. Dumpsys battery
        res_bat = self.adb.shell("dumpsys battery")
        if res_bat.success:
            for line in res_bat.stdout.splitlines():
                line = line.strip()
                if line.startswith("level:"):
                    bat_level = int(line.split(":")[1])
                elif line.startswith("temperature:"):
                    bat_temp = int(line.split(":")[1]) / 10.0
                elif line.startswith("status:"):
                    # 2 is charging, 3 is discharging, 5 is full
                    status_val = int(line.split(":")[1])
                    is_charging = status_val == 2
                elif "Battery current" in line or "current now" in line:
                    match = re.search(r"[-+]?\d+", line)
                    if match:
                        current_ua = int(match.group(0))
                elif "voltage" in line:
                    match = re.search(r"\d+", line)
                    if match:
                        voltage_uv = int(match.group(0))

        # 2. Thermal service
        res_therm = self.adb.shell("dumpsys thermalservice 2>&1")
        if res_therm.success:
            match = re.search(r"Thermal Status:\s*(\d+)", res_therm.stdout)
            if match:
                thermal_status = int(match.group(1))

        # 3. Memory
        res_mem = self.adb.shell("cat /proc/meminfo")
        if res_mem.success:
            for line in res_mem.stdout.splitlines():
                if line.startswith("MemAvailable:"):
                    mem_avail = int(line.split()[1])
                    break

        # 4. CPU Current frequencies
        res_freq = self.adb.shell(
            'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo -n "$(basename $c):"; cat $c/cpufreq/scaling_cur_freq 2>/dev/null || echo 0; done'
        )
        if res_freq.success:
            for line in res_freq.stdout.splitlines():
                if ":" in line:
                    c_str, f_str = line.strip().split(":", 1)
                    core_idx = int(c_str.replace("cpu", ""))
                    try:
                        cpu_freqs[core_idx] = int(f_str)
                    except ValueError:
                        pass

        snapshot = TelemetrySnapshot(
            timestamp=now,
            battery_level=bat_level,
            battery_temp_celsius=bat_temp,
            battery_current_ua=current_ua,
            battery_voltage_uv=voltage_uv,
            is_charging=is_charging,
            thermal_status=thermal_status,
            mem_available_kb=mem_avail,
            cpu_frequencies_khz=cpu_freqs,
        )

        return snapshot

    def check_safety(self, snapshot: TelemetrySnapshot) -> tuple[bool, str | None]:
        """Verify telemetry does not violate safety limits."""
        if snapshot.battery_temp_celsius > self.max_temp_celsius:
            return (
                False,
                f"Battery temperature ({snapshot.battery_temp_celsius}°C) exceeded safety limit ({self.max_temp_celsius}°C)",
            )
        if snapshot.thermal_status >= 3:
            # 3 = SEVERE, 4 = CRITICAL, 5 = EMERGENCY, 6 = SHUTDOWN
            return (
                False,
                f"Android thermal throttling status ({snapshot.thermal_status}) indicates severe/critical throttling",
            )
        return True, None
