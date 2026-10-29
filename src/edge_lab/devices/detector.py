"""Hardware environment detector and diagnostic engine for Snapdragon devices."""

import re
from dataclasses import dataclass, field

from edge_lab.devices.adb import AdbClient


@dataclass
class HardwareReport:
    serial: str
    model: str = "Unknown"
    product: str = "Unknown"
    soc_model: str = "Unknown"
    soc_platform: str = "Unknown"
    android_version: str = "Unknown"
    abi: str = "Unknown"
    cpu_cores: int = 0
    cpu_frequencies_khz: dict[int, int] = field(default_factory=dict)
    mem_total_kb: int = 0
    mem_available_kb: int = 0
    battery_level: int = -1
    battery_temp_celsius: float = -1.0
    thermal_status: int = -1
    storage_free_mb: int = 0
    llama_deployed: bool = False
    opencl_supported: bool = False
    hexagon_supported: bool = False
    perfetto_available: bool = False
    models_found: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


class DeviceDetector:
    """Detects and validates connected Snapdragon Android hardware environment."""

    def __init__(self, adb: AdbClient):
        self.adb = adb

    def detect(self) -> HardwareReport:
        serial = self.adb.serial or "default"
        report = HardwareReport(serial=serial)

        # 1. Basic properties
        res_props = self.adb.shell(
            "getprop ro.product.model; getprop ro.product.name; getprop ro.soc.model; getprop ro.board.platform; getprop ro.build.version.release; getprop ro.product.cpu.abi"
        )
        if res_props.success:
            lines = [line.strip() for line in res_props.stdout.splitlines() if line.strip()]
            if len(lines) >= 6:
                report.model = lines[0]
                report.product = lines[1]
                report.soc_model = lines[2]
                report.soc_platform = lines[3]
                report.android_version = lines[4]
                report.abi = lines[5]

        # 2. CPU topology and frequencies
        res_cpus = self.adb.shell(
            'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo -n "$(basename $c):"; cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null || echo 0; done'
        )
        if res_cpus.success:
            for line in res_cpus.stdout.splitlines():
                if ":" in line:
                    core_str, freq_str = line.strip().split(":", 1)
                    core_idx = int(core_str.replace("cpu", ""))
                    try:
                        freq_val = int(freq_str)
                    except ValueError:
                        freq_val = 0
                    report.cpu_frequencies_khz[core_idx] = freq_val
            report.cpu_cores = len(report.cpu_frequencies_khz)

        # 3. Memory info
        res_mem = self.adb.shell("cat /proc/meminfo")
        if res_mem.success:
            for line in res_mem.stdout.splitlines():
                if line.startswith("MemTotal:"):
                    report.mem_total_kb = int(line.split()[1])
                elif line.startswith("MemAvailable:"):
                    report.mem_available_kb = int(line.split()[1])

        # 4. Battery and thermals
        res_bat = self.adb.shell("dumpsys battery")
        if res_bat.success:
            for line in res_bat.stdout.splitlines():
                line = line.strip()
                if line.startswith("level:"):
                    report.battery_level = int(line.split(":")[1])
                elif line.startswith("temperature:"):
                    # Reported in tenths of degree Celsius
                    raw_temp = int(line.split(":")[1])
                    report.battery_temp_celsius = raw_temp / 10.0

        res_therm = self.adb.shell("dumpsys thermalservice 2>&1")
        if res_therm.success:
            match = re.search(r"Thermal Status:\s*(\d+)", res_therm.stdout)
            if match:
                report.thermal_status = int(match.group(1))

        # 5. Storage free
        res_df = self.adb.shell("df -k /data | tail -n 1")
        if res_df.success:
            parts = res_df.stdout.strip().split()
            if len(parts) >= 4:
                try:
                    report.storage_free_mb = int(parts[3]) // 1024
                except ValueError:
                    pass

        # 6. Deployed runtime validation
        bin_mtmd = "/data/local/tmp/llama.cpp/bin/llama-mtmd-cli"
        lib_opencl = "/data/local/tmp/llama.cpp/lib/libggml-opencl.so"
        lib_hexagon = "/data/local/tmp/llama.cpp/lib/libggml-hexagon.so"

        report.llama_deployed = self.adb.file_exists(bin_mtmd)
        report.opencl_supported = self.adb.file_exists(lib_opencl)
        report.hexagon_supported = self.adb.file_exists(lib_hexagon)

        # 7. Model files validation
        model_paths = [
            "/sdcard/Download/lfm3/LFM3-VL-2B-Q4_K_M.gguf",
            "/sdcard/Download/lfm3/mmproj-LFM3-VL-2B-Q8_0.gguf",
            "/sdcard/Download/lfm3/test-512.jpg",
        ]
        for mp in model_paths:
            if self.adb.file_exists(mp):
                report.models_found.append(mp)

        # 8. Perfetto service check
        res_perfetto = self.adb.shell("perfetto --query 2>&1 | head -n 5")
        if res_perfetto.success and "Perfetto" in res_perfetto.stdout:
            report.perfetto_available = True

        return report
