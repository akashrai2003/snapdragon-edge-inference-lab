"""Workstream B2: steady-state (warm) vision encoder latency inside a persistent llama-server.

The resolution sweep (Workstream A) times `llama-mtmd-cli` runs started with `--no-warmup`, so every
measured encode also pays the projector's one-time costs (compute-buffer reservation and allocation,
flash-attention probe, lazily compiled OpenCL kernels). This experiment separates the two:

* one llama-server process per configuration (projector warmup left enabled),
* the first image request is recorded as `is_warmup=True` (captures any remaining one-time cost),
* every later request uses a unique image (8x8 corner patch changed) with `cache_prompt=false`, so the
  server can never reuse a previously encoded image or its KV cache,
* encode time is read from the server log ("mtmd batch encoded in N ms", logged by the
  instrumented fork), image-token decode time and token count from mtmd-helper log lines.
"""

import base64
import io
import logging
import random
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import requests
from PIL import Image

from edge_lab.config.models import ModelConfig
from edge_lab.devices.adb import AdbClient
from edge_lab.experiments.base import ExperimentResult, TrialResult
from edge_lab.experiments.cold_warm import ColdVsWarmExperiment
from edge_lab.metrics.parser import parse_server_image_timings
from edge_lab.metrics.stats import compute_statistics
from edge_lab.runtimes.snapdragon import SnapdragonRuntime

logger = logging.getLogger("experiment_warm_encode")

SERVER_LOG = "/data/local/tmp/llama-server-warm-encode.log"

DEFAULT_CONFIGURATIONS: list[dict[str, Any]] = [
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


@dataclass
class WarmEncodeTrial(TrialResult):
    """Trial record with server-side image timings."""

    image_encode_ms: float | None = None
    image_decode_ms: float | None = None
    image_tokens: int | None = None
    prompt_n: int | None = None
    prompt_ms: float | None = None


def make_unique_jpeg(base: Image.Image, resolution: int, variant: int) -> bytes:
    """Resize `base` so its longest edge is `resolution`, then stamp a per-variant corner patch.

    The perturbation changes the image hash so the server treats every request as a new image.
    """
    w, h = base.size
    scale = resolution / max(w, h)
    img = base.convert("RGB").resize(
        (max(1, round(w * scale)), max(1, round(h * scale))), Image.Resampling.LANCZOS
    )
    # an 8x8 corner patch aligned to a JPEG block, so the change survives compression
    color = ((variant * 37) % 256, (variant * 91) % 256, (variant * 53) % 256)
    img.paste(color, (0, 0, min(8, img.width), min(8, img.height)))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=95)
    return buf.getvalue()


def build_server_flags(image_max_tokens: int | None, extra: list[str] | None = None) -> list[str]:
    """Extra llama-server flags for this experiment."""
    flags = ["--warmup"]
    if image_max_tokens is not None:
        flags.append(f"--image-max-tokens {image_max_tokens}")
    if extra:
        flags.extend(extra)
    return flags


def summarize(trials: list[WarmEncodeTrial]) -> dict[str, Any]:
    """Group measured (non-warmup) trials by resolution and configuration."""
    summary: dict[str, Any] = {}
    keys = sorted({(t.resolution, t.configuration_id) for t in trials})
    for res, cfg_id in keys:
        group = [t for t in trials if t.resolution == res and t.configuration_id == cfg_id]
        measured = [t.image_encode_ms for t in group if not t.is_warmup and t.image_encode_ms]
        first = [t.image_encode_ms for t in group if t.is_warmup and t.image_encode_ms]
        entry: dict[str, Any] = {
            "first_request_encode_ms": first[0] if first else None,
            "image_tokens": next((t.image_tokens for t in group if t.image_tokens), None),
        }
        if measured:
            entry["warm_encode_ms"] = asdict(compute_statistics(measured))
        else:
            entry["warm_encode_ms"] = {"error": "No valid measurements"}
        decode = [t.image_decode_ms for t in group if not t.is_warmup and t.image_decode_ms]
        if decode:
            entry["warm_image_decode_ms"] = asdict(compute_statistics(decode))
        summary.setdefault(f"res_{res}", {})[cfg_id] = entry
    return summary


def write_warm_trials_csv(trials: list[WarmEncodeTrial], path: Path) -> None:
    """Write the server-side image timing columns that trials.csv does not carry."""
    cols = [
        "trial_index", "configuration_id", "resolution", "is_warmup", "success",
        "image_encode_ms", "image_decode_ms", "image_tokens", "prompt_n", "prompt_ms",
        "wall_duration_seconds",
    ]  # fmt: skip
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(",".join(cols) + "\n")
        for t in trials:
            row = asdict(t)
            f.write(",".join("" if row[c] is None else str(row[c]) for c in cols) + "\n")


class WarmEncodeExperiment(ColdVsWarmExperiment):
    """Measures warm, per-image encoder latency across resolutions in a persistent server."""

    def __init__(
        self,
        adb: AdbClient,
        output_dir: Path,
        model_config: ModelConfig,
        runtime: SnapdragonRuntime | None = None,
        server_port: int = 8080,
    ):
        super().__init__(adb, output_dir, model_config, runtime, server_port)

    def _log_line_count(self) -> int:
        res = self.adb.shell(f"wc -l < {SERVER_LOG}")
        try:
            return int(res.stdout.strip() or 0)
        except ValueError:
            return 0

    def _log_since(self, line_count: int) -> str:
        return self.adb.shell(f"tail -n +{line_count + 1} {SERVER_LOG}").stdout

    def _request(self, jpeg: bytes, max_tokens: int, timeout: float) -> dict[str, Any]:
        uri = "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii")
        payload = {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Describe this image in one sentence."},
                        {"type": "image_url", "image_url": {"url": uri}},
                    ],
                }
            ],
            "max_tokens": max_tokens,
            "temperature": 0.0,
            "stream": False,
            "cache_prompt": False,
        }
        url = f"http://127.0.0.1:{self.server_port}/v1/chat/completions"
        resp = requests.post(url, json=payload, timeout=timeout)
        if resp.status_code != 200:
            raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:500]}")
        return resp.json()

    def run(  # type: ignore[override]
        self,
        local_base_image: Path,
        resolutions: list[int],
        configurations: list[dict[str, Any]] | None = None,
        requests_per_resolution: int = 5,
        image_max_tokens: int | None = None,
        threads: int = 4,
        context_size: int = 8192,
        max_tokens: int = 8,
        cooldown_seconds: float = 2.0,
        request_timeout: float = 300.0,
        seed: int = 42,
    ) -> ExperimentResult:
        configs = configurations or DEFAULT_CONFIGURATIONS
        timestamp = time.time()
        trials: list[WarmEncodeTrial] = []
        rng = random.Random(seed)
        variant = 0
        trial_index = 0

        with Image.open(local_base_image) as im:
            base = im.convert("RGB")

        for cfg in configs:
            logger.info(f"Starting llama-server for {cfg['name']}")
            pid = self.start_persistent_server(
                cfg,
                threads=threads,
                context_size=context_size,
                extra_flags=build_server_flags(image_max_tokens),
                log_path=SERVER_LOG,
            )
            first_request = True
            try:
                res_order = list(resolutions)
                rng.shuffle(res_order)
                for res in res_order:
                    for _ in range(requests_per_resolution):
                        trial_index += 1
                        variant += 1
                        jpeg = make_unique_jpeg(base, res, variant)

                        t_before = self.telemetry_monitor.capture()
                        safe, reason = self.telemetry_monitor.check_safety(t_before)
                        if not safe:
                            raise RuntimeError(f"Thermal abort: {reason}")

                        n_lines = self._log_line_count()
                        t0 = time.perf_counter()
                        err = None
                        data: dict[str, Any] = {}
                        try:
                            data = self._request(jpeg, max_tokens, request_timeout)
                        except (requests.RequestException, RuntimeError) as e:
                            err = str(e)
                        wall = time.perf_counter() - t0
                        t_after = self.telemetry_monitor.capture()

                        img_t = parse_server_image_timings(self._log_since(n_lines))
                        timings = data.get("timings", {}) if data else {}
                        prompt_ms = timings.get("prompt_ms")
                        prompt_n = timings.get("prompt_n")
                        trials.append(
                            WarmEncodeTrial(
                                trial_index=trial_index,
                                configuration_id=cfg["id"],
                                resolution=res,
                                is_warmup=first_request,
                                success=err is None and img_t.encode_ms is not None,
                                vision_encoding_ms=img_t.encode_ms,
                                prompt_eval_tok_per_sec=timings.get("prompt_per_second"),
                                eval_tok_per_sec=timings.get("predicted_per_second"),
                                wall_duration_seconds=wall,
                                telemetry_before=asdict(t_before),
                                telemetry_after=asdict(t_after),
                                error_message=err,
                                image_encode_ms=img_t.encode_ms,
                                image_decode_ms=img_t.decode_ms,
                                image_tokens=img_t.image_tokens,
                                prompt_n=prompt_n,
                                prompt_ms=prompt_ms,
                            )
                        )
                        first_request = False
                        time.sleep(cooldown_seconds)
            finally:
                self.stop_persistent_server(pid)

        exp_res = ExperimentResult(
            experiment_id="warm-encode",
            timestamp=timestamp,
            device_id="realme-gt8-pro",
            model_id=self.model_config.id,
            trials=list(trials),
            summary=summarize(trials),
            metadata={
                "resolutions": resolutions,
                "configurations": [c["id"] for c in configs],
                "requests_per_resolution": requests_per_resolution,
                "image_max_tokens": image_max_tokens,
                "threads": threads,
                "server_log": SERVER_LOG,
                "requires": "llama-server build that logs 'mtmd batch encoded in N ms'",
            },
        )
        exp_res.save(self.output_dir)
        write_warm_trials_csv(trials, self.output_dir / "warm_trials.csv")
        return exp_res
