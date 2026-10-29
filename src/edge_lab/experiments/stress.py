"""Stress testing and concurrent request benchmark runner."""

import base64
import concurrent.futures
import logging
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import requests

from edge_lab.config.models import ModelConfig
from edge_lab.devices.adb import AdbClient
from edge_lab.experiments.base import BaseExperiment, ExperimentResult
from edge_lab.metrics.stats import compute_statistics
from edge_lab.runtimes.snapdragon import SnapdragonRuntime
from edge_lab.telemetry.thermal import ContinuousThermalProfiler

logger = logging.getLogger("stress_experiment")


@dataclass
class StressRequestResult:
    request_id: int
    worker_id: int
    start_time: float
    duration_seconds: float
    prompt_tokens: int
    completion_tokens: int
    tokens_per_second: float
    status_code: int
    success: bool
    error: str | None = None


class StressExperiment(BaseExperiment):
    """Sustained stress and multi-client concurrent load testing runner."""

    def __init__(
        self,
        adb: AdbClient,
        output_dir: Path,
        model_config: ModelConfig,
        runtime: SnapdragonRuntime | None = None,
        server_port: int = 8080,
    ):
        super().__init__(adb, output_dir)
        self.model_config = model_config
        self.runtime = runtime or SnapdragonRuntime(adb)
        self.server_port = server_port
        self.thermal_profiler = ContinuousThermalProfiler(adb, sample_interval_seconds=0.5)

    def _encode_image_b64(self, local_path: Path) -> str:
        with open(local_path, "rb") as f:
            b64_str = base64.b64encode(f.read()).decode("utf-8")
        return f"data:image/png;base64,{b64_str}"

    def start_server(
        self,
        concurrency: int = 2,
        context_size: int = 4096,
        threads: int = 4,
        ngl: int = 99,
        device: str | None = "GPUOpenCL",
        mmproj_offload: bool = True,
    ) -> int:
        """Launch llama-server with continuous batching and multi-slot parallelism."""
        model_p = self.model_config.language_component.device_path
        mmproj_p = (
            self.model_config.vision_projector.device_path
            if self.model_config.vision_projector
            else ""
        )

        flags = [
            f"-m '{model_p}'",
            f"--mmproj '{mmproj_p}'",
            f"-c {context_size}",
            f"-np {concurrency}",
            "--cont-batching",
            "--kv-unified",
            f"-t {threads}",
            f"--port {self.server_port}",
            "--host 127.0.0.1",
            f"-ngl {ngl}",
        ]
        if mmproj_offload:
            flags.append("--mmproj-offload")
            if device:
                flags.append(f"--mmproj-device '{device}'")
        else:
            flags.append("--no-mmproj-offload")

        if device:
            flags.append(f"--device '{device}'")

        env_prefix = (
            f"LD_LIBRARY_PATH='{self.runtime.lib_dir}' "
            f"GGML_OPENCL_KERNEL_CACHE_DIR='{self.runtime.cl_cache_dir}'"
        )
        cmd = f"{env_prefix} nohup {self.runtime.bin_dir}/llama-server {' '.join(flags)} > /data/local/tmp/llama-server.log 2>&1 & echo $!"

        res = self.adb.shell(cmd)
        server_pid = int(res.stdout.strip().split()[-1])

        # Establish forward
        self.adb.forward(self.server_port, self.server_port)

        # Health polling
        url = f"http://127.0.0.1:{self.server_port}/health"
        for _ in range(60):
            try:
                resp = requests.get(url, timeout=2.0)
                if resp.status_code == 200:
                    logger.info("llama-server is healthy and ready for stress load")
                    return server_pid
            except requests.RequestException:
                pass
            time.sleep(1.0)

        log_res = self.adb.shell("cat /data/local/tmp/llama-server.log | tail -n 25")
        raise RuntimeError(f"llama-server failed to start: {log_res.stdout}")

    def stop_server(self, pid: int) -> None:
        try:
            self.adb.shell(f"kill -TERM {pid} 2>/dev/null || kill -9 {pid} 2>/dev/null")
        finally:
            self.adb.remove_forward(self.server_port)

    def _worker_loop(
        self,
        worker_id: int,
        stop_event: Any,
        image_b64: str,
        results_list: list[StressRequestResult],
        max_tokens: int = 48,
    ) -> None:
        """Simulates an active client issuing repeated multimodal document queries."""
        req_counter = 0
        url = f"http://127.0.0.1:{self.server_port}/v1/chat/completions"

        prompts = [
            "Extract the key metrics from this document and list them briefly.",
            "Summarize the technical trends shown in this chart.",
            "What is the most notable data point visible in this document?",
        ]

        while not stop_event.is_set():
            req_counter += 1
            prompt_text = prompts[req_counter % len(prompts)]
            payload = {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt_text},
                            {"type": "image_url", "image_url": {"url": image_b64}},
                        ],
                    }
                ],
                "max_tokens": max_tokens,
                "temperature": 0.1,
                "stream": False,
            }

            t0 = time.perf_counter()
            try:
                resp = requests.post(url, json=payload, timeout=60.0)
                dur = time.perf_counter() - t0
                if resp.status_code == 200:
                    data = resp.json()
                    usage = data.get("usage", {})
                    p_toks = usage.get("prompt_tokens", 0)
                    c_toks = usage.get("completion_tokens", 0)
                    tps = (c_toks / dur) if dur > 0 else 0.0

                    logger.info(
                        f"Worker {worker_id} finished request {req_counter}: {c_toks} tokens in {dur:.2f}s ({tps:.1f} t/s)"
                    )
                    results_list.append(
                        StressRequestResult(
                            request_id=req_counter,
                            worker_id=worker_id,
                            start_time=t0,
                            duration_seconds=dur,
                            prompt_tokens=p_toks,
                            completion_tokens=c_toks,
                            tokens_per_second=round(tps, 2),
                            status_code=200,
                            success=True,
                        )
                    )
                else:
                    logger.warning(
                        f"Worker {worker_id} request {req_counter} failed with HTTP {resp.status_code}: {resp.text[:120]}"
                    )
                    results_list.append(
                        StressRequestResult(
                            request_id=req_counter,
                            worker_id=worker_id,
                            start_time=t0,
                            duration_seconds=dur,
                            prompt_tokens=0,
                            completion_tokens=0,
                            tokens_per_second=0.0,
                            status_code=resp.status_code,
                            success=False,
                            error=resp.text,
                        )
                    )
            except Exception as e:
                dur = time.perf_counter() - t0
                logger.warning(f"Worker {worker_id} request {req_counter} exception: {e}")
                results_list.append(
                    StressRequestResult(
                        request_id=req_counter,
                        worker_id=worker_id,
                        start_time=t0,
                        duration_seconds=dur,
                        prompt_tokens=0,
                        completion_tokens=0,
                        tokens_per_second=0.0,
                        status_code=500,
                        success=False,
                        error=str(e),
                    )
                )

            # Brief pacing gap
            time.sleep(0.2)

    def run(
        self,
        duration_seconds: int = 60,
        concurrency: int = 2,
        local_document_image: Path | None = None,
        ngl: int = 99,
        mmproj_offload: bool = True,
    ) -> ExperimentResult:
        """Run continuous concurrent stress test with active thermal and DVFS tracking."""
        doc_img = local_document_image or Path("assets/images/documents/chart_financial_multi.png")
        if not doc_img.exists():
            raise FileNotFoundError(f"Document evaluation asset not found: {doc_img}")

        img_b64 = self._encode_image_b64(doc_img)
        timestamp = time.time()

        # 1. Start Server
        logger.info(f"Starting persistent server with concurrency={concurrency}...")
        server_pid = self.start_server(
            concurrency=concurrency, ngl=ngl, mmproj_offload=mmproj_offload
        )

        # 2. Start Continuous Thermal Profiler
        self.thermal_profiler.start()

        request_results: list[StressRequestResult] = []
        stop_event = threading.Event()

        try:
            # 3. Launch Concurrent Client Workers
            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
                futures = [
                    executor.submit(
                        self._worker_loop,
                        i + 1,
                        stop_event,
                        img_b64,
                        request_results,
                    )
                    for i in range(concurrency)
                ]

                # Monitor loop until duration expires or thermal safety triggers
                start_loop = time.time()
                while time.time() - start_loop < duration_seconds:
                    safe, reason = self.thermal_profiler.is_safe
                    if not safe:
                        logger.warning(f"SAFETY ABORT TRIGGERED: {reason}")
                        break
                    time.sleep(1.0)

                stop_event.set()
                concurrent.futures.wait(futures, timeout=10.0)

        finally:
            # 4. Cleanup
            self.stop_server(server_pid)
            self.thermal_profiler.stop()

        # 5. Export Telemetry Timeseries
        csv_path = self.output_dir / "thermal_timeseries.csv"
        self.thermal_profiler.save_csv(csv_path)

        # 6. Save Requests CSV
        req_csv = self.output_dir / "requests.csv"
        with open(req_csv, "w", encoding="utf-8") as f:
            f.write(
                "request_id,worker_id,duration_seconds,prompt_tokens,completion_tokens,tokens_per_sec,status_code,success,error\n"
            )
            for r in request_results:
                err_str = f'"{r.error}"' if r.error else ""
                f.write(
                    f"{r.request_id},{r.worker_id},{r.duration_seconds:.3f},{r.prompt_tokens},"
                    f"{r.completion_tokens},{r.tokens_per_second},{r.status_code},{r.success},{err_str}\n"
                )

        # 7. Summary & Metrics Calculation
        durations = [r.duration_seconds for r in request_results if r.success]
        tps_list = [r.tokens_per_second for r in request_results if r.success]
        total_completion_toks = sum(r.completion_tokens for r in request_results if r.success)
        total_test_duration = time.time() - timestamp
        aggregate_throughput = (
            total_completion_toks / total_test_duration if total_test_duration > 0 else 0.0
        )

        thermal_df = self.thermal_profiler.to_dataframe()
        summary: dict[str, Any] = {
            "duration_seconds": round(total_test_duration, 2),
            "concurrency": concurrency,
            "total_requests": len(request_results),
            "successful_requests": len(durations),
            "failed_requests": len(request_results) - len(durations),
            "aggregate_tokens_per_sec": round(aggregate_throughput, 2),
            "latency_stats": asdict(compute_statistics(durations)) if durations else {},
            "throughput_stats": asdict(compute_statistics(tps_list)) if tps_list else {},
            "thermal": {
                "initial_battery_temp_c": (
                    float(thermal_df["battery_temp_c"].iloc[0]) if not thermal_df.empty else 0.0
                ),
                "peak_battery_temp_c": (
                    float(thermal_df["battery_temp_c"].max()) if not thermal_df.empty else 0.0
                ),
                "peak_cpu_perf_temp_c": (
                    float(thermal_df["cpu_perf_temp_c"].max()) if not thermal_df.empty else 0.0
                ),
                "peak_cpu_prime_temp_c": (
                    float(thermal_df["cpu_prime_temp_c"].max()) if not thermal_df.empty else 0.0
                ),
                "peak_gpu_temp_c": (
                    float(thermal_df["gpu_temp_c"].max()) if not thermal_df.empty else 0.0
                ),
                "thermal_status_max": (
                    int(thermal_df["thermal_status"].max()) if not thermal_df.empty else 0
                ),
            },
        }

        # Check for frequency throttling: did any prime core drop below 4000 MHz?
        prime_freq_cols = [c for c in thermal_df.columns if c in ("cpu6_mhz", "cpu7_mhz")]
        if prime_freq_cols:
            min_prime = float(thermal_df[prime_freq_cols].min().min())
            summary["thermal"]["min_prime_freq_mhz"] = min_prime
            summary["thermal"]["throttling_observed"] = bool(min_prime < 4000.0)

        exp_result = ExperimentResult(
            experiment_id="thermal-stress-concurrency",
            timestamp=timestamp,
            device_id="realme-gt8-pro",
            model_id=self.model_config.id,
            trials=[],
            summary=summary,
            metadata={"concurrency": concurrency, "target_duration": duration_seconds},
        )
        exp_result.save(self.output_dir)
        return exp_result
