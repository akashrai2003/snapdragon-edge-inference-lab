"""Perfetto tracing, capability detection, and TraceProcessor SQL analysis."""

import logging
import os
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

from edge_lab.devices.adb import AdbClient

logger = logging.getLogger("perfetto_profiler")


@dataclass
class PerfettoCapabilities:
    service_available: bool = False
    version: str = "Unknown"
    linux_ftrace: bool = False
    process_stats: bool = False
    sys_stats: bool = False
    android_power: bool = False
    gpu_memory: bool = False
    heapprofd: bool = False
    raw_sources: list[str] = field(default_factory=list)


class PerfettoProfiler:
    """Manages system tracing via Android's native Perfetto service and executes SQL analysis."""

    def __init__(self, adb: AdbClient):
        self.adb = adb

    def check_capabilities(self) -> PerfettoCapabilities:
        """Inspect device capabilities and registered data sources via perfetto --query."""
        caps = PerfettoCapabilities()

        res = self.adb.shell("perfetto --query 2>&1")
        if not res.success or "PRODUCER PROCESSES" not in res.stdout:
            return caps

        caps.service_available = True
        for line in res.stdout.splitlines():
            line = line.strip()
            if line.startswith("Service: Perfetto"):
                caps.version = line.split(":", 1)[1].strip()
            elif line.startswith("linux.ftrace"):
                caps.linux_ftrace = True
                caps.raw_sources.append("linux.ftrace")
            elif line.startswith("linux.process_stats"):
                caps.process_stats = True
                caps.raw_sources.append("linux.process_stats")
            elif line.startswith("linux.sys_stats"):
                caps.sys_stats = True
                caps.raw_sources.append("linux.sys_stats")
            elif line.startswith("android.power"):
                caps.android_power = True
                caps.raw_sources.append("android.power")
            elif line.startswith("android.gpu.memory"):
                caps.gpu_memory = True
                caps.raw_sources.append("android.gpu.memory")
            elif line.startswith("android.heapprofd"):
                caps.heapprofd = True
                caps.raw_sources.append("android.heapprofd")

        return caps

    def start_trace(
        self, config_content: str, remote_trace_path: str = "/data/misc/perfetto-traces/trace.pftrace"
    ) -> int:
        """Start a background tracing session with the provided protobuf text configuration.

        Returns the background Perfetto process PID.
        """
        # Remove any lingering trace file
        self.adb.shell(f"rm -f '{remote_trace_path}'")

        # Push config to device perfetto-configs directory
        remote_cfg = "/data/misc/perfetto-configs/trace_config.pbtx"
        with tempfile.NamedTemporaryFile("w", suffix=".pbtx", delete=False) as f:
            f.write(config_content)
            temp_path = f.name

        try:
            self.adb.push(temp_path, remote_cfg)
        finally:
            if os.path.exists(temp_path):
                os.remove(temp_path)

        cmd = f"perfetto -c '{remote_cfg}' --txt -o '{remote_trace_path}' --background"
        res = self.adb.shell(cmd, check=True)
        try:
            pid = int(res.stdout.strip().split()[-1])
            logger.info(f"Perfetto tracing started on device with PID: {pid}")
            return pid
        except (ValueError, IndexError):
            raise RuntimeError(f"Failed to start Perfetto trace: {res.stdout}")

    def stop_and_pull_trace(
        self,
        pid: int,
        local_output_path: Path,
        remote_trace_path: str = "/data/misc/perfetto-traces/trace.pftrace",
    ) -> Path:
        """Stop the background trace session, wait for flush, and pull the trace to host."""
        logger.info(f"Stopping Perfetto trace session PID {pid}...")
        self.adb.shell(f"kill -TERM {pid} 2>/dev/null || true")

        # Wait for file flush
        time.sleep(1.0)
        for _ in range(15):
            res = self.adb.shell(f"test -s '{remote_trace_path}' && echo 'READY'")
            if "READY" in res.stdout:
                break
            time.sleep(0.5)

        local_output_path.parent.mkdir(parents=True, exist_ok=True)
        self.adb.pull(remote_trace_path, str(local_output_path))
        self.adb.shell(f"rm -f '{remote_trace_path}' /data/misc/perfetto-configs/trace_config.pbtx")

        file_size = local_output_path.stat().st_size
        logger.info(
            f"Successfully pulled Perfetto trace ({file_size} bytes) to {local_output_path}"
        )
        return local_output_path

    def analyze_trace_sql(self, trace_path: Path, sql_query: str) -> pd.DataFrame:
        """Run arbitrary SQL queries against a Perfetto trace using TraceProcessor."""
        from perfetto.trace_processor import TraceProcessor

        tp = TraceProcessor(trace=str(trace_path))
        try:
            qr = tp.query(sql_query)
            df = qr.as_pandas_dataframe()
            return df
        finally:
            tp.close()

    def get_thread_cpu_summary(self, trace_path: Path) -> pd.DataFrame:
        """Extract CPU runtime per thread for llama and inference processes."""
        sql = """
        SELECT
            t.name AS thread_name,
            p.name AS process_name,
            t.tid,
            COUNT(s.id) AS slices_count,
            ROUND(SUM(s.dur) / 1e6, 3) AS total_cpu_time_ms,
            ROUND(AVG(s.dur) / 1e6, 3) AS avg_slice_time_ms,
            ROUND(MAX(s.dur) / 1e6, 3) AS max_slice_time_ms
        FROM sched s
        JOIN thread t USING (utid)
        LEFT JOIN process p USING (upid)
        WHERE (p.name LIKE '%llama%' OR t.name LIKE '%llama%')
        GROUP BY t.utid
        ORDER BY total_cpu_time_ms DESC;
        """
        return self.analyze_trace_sql(trace_path, sql)

    def get_cpu_frequency_summary(self, trace_path: Path) -> pd.DataFrame:
        """Extract CPU frequency distribution across physical cores."""
        sql = """
        SELECT
            t.cpu,
            c.value / 1000 AS freq_mhz,
            ROUND(SUM(c.dur) / 1e6, 2) AS duration_ms
        FROM (
            SELECT
                track_id,
                value,
                LEAD(ts, 1, (SELECT end_ts FROM trace_bounds)) OVER (PARTITION BY track_id ORDER BY ts) - ts AS dur
            FROM counter
        ) c
        JOIN cpu_counter_track t ON c.track_id = t.id
        WHERE t.name = 'cpufreq' AND c.dur > 0
        GROUP BY t.cpu, freq_mhz
        ORDER BY t.cpu, freq_mhz DESC;
        """
        return self.analyze_trace_sql(trace_path, sql)
