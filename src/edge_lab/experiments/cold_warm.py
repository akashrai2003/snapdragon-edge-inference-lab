"""Workstream B: Cold-start versus persistent-runtime inference experiment."""

import base64
import logging
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

import requests

from edge_lab.config.models import ModelConfig
from edge_lab.devices.adb import AdbClient
from edge_lab.experiments.base import BaseExperiment, ExperimentResult, TrialResult
from edge_lab.runtimes.snapdragon import SnapdragonRuntime

logger = logging.getLogger("experiment_cold_warm")


class ColdVsWarmExperiment(BaseExperiment):
    """Compares cold process invocation with a persistent, in-memory model server."""

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

    def _encode_image_base64(self, local_image_path: Path) -> str:
        """Encode local image file to base64 data URI."""
        with open(local_image_path, "rb") as f:
            b64_data = base64.b64encode(f.read()).decode("utf-8")
        return f"data:image/jpeg;base64,{b64_data}"

    def run_cold_trials(
        self,
        image_path: str,
        configuration: dict[str, Any],
        repetitions: int = 5,
    ) -> list[TrialResult]:
        """Execute cold-process trials starting a fresh binary every run."""
        results: list[TrialResult] = []
        model_path = self.model_config.language_component.device_path
        mmproj_path = (
            self.model_config.vision_projector.device_path
            if self.model_config.vision_projector
            else ""
        )

        for i in range(repetitions):
            t_before = self.telemetry_monitor.capture()
            exec_res = self.runtime.run_mtmd_cli(
                model_path=model_path,
                mmproj_path=mmproj_path,
                image_path=image_path,
                ngl=configuration.get("ngl", 0),
                device=configuration.get("device"),
                mmproj_offload=configuration.get("mmproj_offload", False),
                mmproj_device=configuration.get("mmproj_device"),
                threads=4,
                warmup=False,
            )
            t_after = self.telemetry_monitor.capture()
            timing = exec_res.timing

            results.append(
                TrialResult(
                    trial_index=i + 1,
                    configuration_id=f"cold_{configuration.get('id', 'default')}",
                    resolution=512,
                    is_warmup=False,
                    success=exec_res.success,
                    vision_encoding_ms=timing.vision_encoding_ms if timing else None,
                    prompt_eval_tok_per_sec=timing.prompt_eval_tok_per_sec if timing else None,
                    eval_tok_per_sec=timing.eval_tok_per_sec if timing else None,
                    wall_duration_seconds=exec_res.duration_seconds,
                    telemetry_before=asdict(t_before),
                    telemetry_after=asdict(t_after),
                    error_message=exec_res.stderr if not exec_res.success else None,
                    raw_stdout=exec_res.stdout,
                )
            )
            time.sleep(3.0)

        return results

    def start_persistent_server(
        self,
        configuration: dict[str, Any],
        threads: int = 4,
        context_size: int = 2048,
    ) -> int:
        """Launch llama-server on device and establish port forwarding. Returns PID."""
        model_path = self.model_config.language_component.device_path
        mmproj_path = (
            self.model_config.vision_projector.device_path
            if self.model_config.vision_projector
            else ""
        )
        ngl = configuration.get("ngl", 0)
        device = configuration.get("device")
        mmproj_offload = configuration.get("mmproj_offload", False)
        mmproj_device = configuration.get("mmproj_device")

        flags = [
            f"-m '{model_path}'",
            f"--mmproj '{mmproj_path}'",
            f"-t {threads}",
            f"-c {context_size}",
            f"--port {self.server_port}",
            "--host 127.0.0.1",
            f"-ngl {ngl}",
        ]
        if mmproj_offload:
            flags.append("--mmproj-offload")
            if mmproj_device:
                flags.append(f"--mmproj-device '{mmproj_device}'")
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

        # Forward port
        self.adb.forward(self.server_port, self.server_port)

        # Wait for server health endpoint
        url = f"http://127.0.0.1:{self.server_port}/health"
        for _ in range(30):
            try:
                resp = requests.get(url, timeout=2.0)
                if resp.status_code == 200:
                    logger.info("Persistent llama-server is healthy and ready")
                    return server_pid
            except requests.RequestException:
                time.sleep(1.0)

        # If not ready, check log
        log_res = self.adb.shell("cat /data/local/tmp/llama-server.log | tail -n 25")
        raise RuntimeError(f"llama-server failed to become ready. Log:\n{log_res.stdout}")

    def stop_persistent_server(self, server_pid: int) -> None:
        """Terminate persistent server and remove port forwarding."""
        try:
            self.adb.shell(
                f"kill -TERM {server_pid} 2>/dev/null || kill -9 {server_pid} 2>/dev/null"
            )
        finally:
            self.adb.remove_forward(self.server_port)

    def send_chat_completion(
        self,
        prompt: str,
        image_data_uri: str | None = None,
        max_tokens: int = 64,
    ) -> dict[str, Any]:
        """Send an OpenAI-compatible multimodal chat completion request."""
        url = f"http://127.0.0.1:{self.server_port}/v1/chat/completions"

        content_parts: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        if image_data_uri:
            content_parts.append(
                {
                    "type": "image_url",
                    "image_url": {"url": image_data_uri},
                }
            )

        payload = {
            "messages": [{"role": "user", "content": content_parts}],
            "max_tokens": max_tokens,
            "temperature": 0.1,
            "stream": False,
        }

        start = time.perf_counter()
        resp = requests.post(url, json=payload, timeout=60.0)
        dur = time.perf_counter() - start

        if resp.status_code != 200:
            raise RuntimeError(f"HTTP request failed {resp.status_code}: {resp.text}")

        data = resp.json()
        data["_client_duration_seconds"] = dur
        return data

    def run(self) -> ExperimentResult:
        """Execute full cold-vs-warm experiment comparing cold process and warm server."""
        timestamp = time.time()
        trials: list[TrialResult] = []
        target_dir = self.model_config.language_component.device_path.rsplit("/", 1)[0]
        test_img_device = f"{target_dir}/test-512.jpg"

        # Check local test image for server queries
        local_img = self.output_dir / "test-512.jpg"
        if not local_img.exists():
            self.adb.pull(test_img_device, str(local_img))
        img_b64 = self._encode_image_base64(local_img)

        configurations = [
            {
                "id": "gpu_lm_cpu_vision",
                "name": "GPU LM + CPU Vision",
                "ngl": 99,
                "device": "GPUOpenCL",
                "mmproj_offload": False,
            },
            {
                "id": "gpu_all",
                "name": "GPU LM + GPU Vision",
                "ngl": 99,
                "device": "GPUOpenCL",
                "mmproj_offload": True,
                "mmproj_device": "GPUOpenCL",
            },
        ]

        # 1. Cold runs
        for cfg in configurations:
            logger.info(f"Running cold process trials for {cfg['name']}")
            cold_res = self.run_cold_trials(test_img_device, cfg, repetitions=3)
            trials.extend(cold_res)

        # 2. Warm persistent server runs
        for cfg in configurations:
            logger.info(f"Launching persistent server for {cfg['name']}")
            pid = self.start_persistent_server(cfg)
            try:
                for rep in range(3):
                    t_before = self.telemetry_monitor.capture()
                    t_start = time.perf_counter()
                    _ = self.send_chat_completion(
                        prompt="Describe this image in brief.", image_data_uri=img_b64
                    )
                    dur = time.perf_counter() - t_start
                    t_after = self.telemetry_monitor.capture()

                    trials.append(
                        TrialResult(
                            trial_index=rep + 1,
                            configuration_id=f"warm_{cfg['id']}",
                            resolution=512,
                            is_warmup=(rep == 0),
                            success=True,
                            wall_duration_seconds=dur,
                            telemetry_before=asdict(t_before),
                            telemetry_after=asdict(t_after),
                        )
                    )
                    time.sleep(2.0)
            finally:
                self.stop_persistent_server(pid)

        # Summary
        summary = {"trials_count": len(trials)}
        exp_res = ExperimentResult(
            experiment_id="cold-vs-warm",
            timestamp=timestamp,
            device_id="realme-gt8-pro",
            model_id=self.model_config.id,
            trials=trials,
            summary=summary,
        )
        exp_res.save(self.output_dir)
        return exp_res
