"""Workstream A: Image resolution crossover experiment."""

import logging
import random
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

from PIL import Image

from edge_lab.config.models import ExperimentConfig, ModelConfig
from edge_lab.devices.adb import AdbClient
from edge_lab.experiments.base import BaseExperiment, ExperimentResult, TrialResult
from edge_lab.metrics.stats import compute_latency_ratio, compute_speedup, compute_statistics
from edge_lab.runtimes.snapdragon import SnapdragonRuntime

logger = logging.getLogger("experiment_resolution")


class ResolutionCrossoverExperiment(BaseExperiment):
    """Executes resolution crossover benchmark comparing CPU and GPU projector performance."""

    def __init__(
        self,
        adb: AdbClient,
        output_dir: Path,
        experiment_config: ExperimentConfig,
        model_config: ModelConfig,
        runtime: SnapdragonRuntime | None = None,
    ):
        super().__init__(adb, output_dir)
        self.exp_config = experiment_config
        self.model_config = model_config
        self.runtime = runtime or SnapdragonRuntime(adb)

    def prepare_test_images(self, local_source_image: Path | None = None) -> dict[int, str]:
        """Ensure test images at all requested resolutions exist on device."""
        resolution_paths: dict[int, str] = {}
        target_dir = self.model_config.language_component.device_path.rsplit("/", 1)[0]

        # First check if 512 exists
        default_img = (
            self.model_config.test_image.device_path
            if self.model_config.test_image
            else f"{target_dir}/test-512.jpg"
        )

        for res in self.exp_config.resolutions:
            device_img_path = f"{target_dir}/test-{res}.jpg"
            if self.adb.file_exists(device_img_path):
                resolution_paths[res] = device_img_path
            elif res == 512 and self.adb.file_exists(default_img):
                resolution_paths[res] = default_img
            else:
                # If local source image provided or we can pull test-512 from device
                local_tmp = self.output_dir / "tmp_images"
                local_tmp.mkdir(parents=True, exist_ok=True)
                local_base = local_tmp / "base-512.jpg"

                if not local_base.exists():
                    self.adb.pull(default_img, str(local_base))

                # Resize with PIL maintaining aspect ratio
                with Image.open(local_base) as img:
                    w, h = img.size
                    scale = res / max(w, h)
                    new_w = max(1, int(round(w * scale)))
                    new_h = max(1, int(round(h * scale)))
                    resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
                    local_target = local_tmp / f"test-{res}.jpg"
                    resized.save(local_target, "JPEG", quality=95)

                self.adb.push(str(local_target), device_img_path)
                resolution_paths[res] = device_img_path

        return resolution_paths

    def run(self, resolutions_to_run: list[int] | None = None) -> ExperimentResult:
        """Run the resolution experiment matrix."""
        res_list = resolutions_to_run or self.exp_config.resolutions
        image_paths = self.prepare_test_images()

        trials: list[TrialResult] = []
        trial_index = 0
        timestamp = time.time()

        # Build schedule: for each resolution, run configurations
        # Randomize order across repetitions
        rng = random.Random(self.exp_config.trials.seed)

        model_path = self.model_config.language_component.device_path
        mmproj_path = (
            self.model_config.vision_projector.device_path
            if self.model_config.vision_projector
            else ""
        )
        threads = self.exp_config.trials.threads
        cooldown = self.exp_config.trials.cooldown_seconds
        timeout = self.exp_config.trials.timeout_seconds

        for res in res_list:
            img_path = image_paths.get(res)
            if not img_path:
                logger.warning(f"Skipping resolution {res}: image not found")
                continue

            # 1. Warmup runs (1 per configuration)
            for cfg in self.exp_config.configurations:
                trial_index += 1
                logger.info(f"[Warmup] Res {res}px | Cfg: {cfg.name}")
                t_before = self.telemetry_monitor.capture()

                exec_res = self.runtime.run_mtmd_cli(
                    model_path=model_path,
                    mmproj_path=mmproj_path,
                    image_path=img_path,
                    ngl=cfg.ngl,
                    device=cfg.device,
                    mmproj_offload=cfg.mmproj_offload,
                    mmproj_device=cfg.mmproj_device,
                    threads=threads,
                    warmup=False,
                    timeout_seconds=timeout,
                )

                t_after = self.telemetry_monitor.capture()
                timing = exec_res.timing

                trials.append(
                    TrialResult(
                        trial_index=trial_index,
                        configuration_id=cfg.id,
                        resolution=res,
                        is_warmup=True,
                        success=exec_res.success,
                        vision_encoding_ms=timing.vision_encoding_ms if timing else None,
                        prompt_eval_tok_per_sec=timing.prompt_eval_tok_per_sec if timing else None,
                        eval_tok_per_sec=timing.eval_tok_per_sec if timing else None,
                        wall_duration_seconds=exec_res.duration_seconds,
                        telemetry_before=asdict(t_before),
                        telemetry_after=asdict(t_after),
                        error_message=exec_res.stderr if not exec_res.success else None,
                        raw_stdout=exec_res.stdout,
                        raw_stderr=exec_res.stderr,
                    )
                )
                time.sleep(cooldown)

            # 2. Measured repetitions
            for rep in range(self.exp_config.trials.measured_repetitions):
                cfg_list = list(self.exp_config.configurations)
                if self.exp_config.trials.randomize_order:
                    rng.shuffle(cfg_list)

                for cfg in cfg_list:
                    trial_index += 1
                    logger.info(f"[Measured #{rep + 1}] Res {res}px | Cfg: {cfg.name}")
                    t_before = self.telemetry_monitor.capture()

                    # Safety check
                    safe, reason = self.telemetry_monitor.check_safety(t_before)
                    if not safe:
                        logger.error(f"Aborting trial due to safety limit: {reason}")
                        trials.append(
                            TrialResult(
                                trial_index=trial_index,
                                configuration_id=cfg.id,
                                resolution=res,
                                is_warmup=False,
                                success=False,
                                error_message=f"Thermal abort: {reason}",
                                telemetry_before=asdict(t_before),
                            )
                        )
                        break

                    exec_res = self.runtime.run_mtmd_cli(
                        model_path=model_path,
                        mmproj_path=mmproj_path,
                        image_path=img_path,
                        ngl=cfg.ngl,
                        device=cfg.device,
                        mmproj_offload=cfg.mmproj_offload,
                        mmproj_device=cfg.mmproj_device,
                        threads=threads,
                        warmup=False,
                        timeout_seconds=timeout,
                    )

                    t_after = self.telemetry_monitor.capture()
                    timing = exec_res.timing

                    trials.append(
                        TrialResult(
                            trial_index=trial_index,
                            configuration_id=cfg.id,
                            resolution=res,
                            is_warmup=False,
                            success=exec_res.success,
                            vision_encoding_ms=timing.vision_encoding_ms if timing else None,
                            prompt_eval_tok_per_sec=timing.prompt_eval_tok_per_sec
                            if timing
                            else None,
                            eval_tok_per_sec=timing.eval_tok_per_sec if timing else None,
                            wall_duration_seconds=exec_res.duration_seconds,
                            telemetry_before=asdict(t_before),
                            telemetry_after=asdict(t_after),
                            error_message=exec_res.stderr if not exec_res.success else None,
                            raw_stdout=exec_res.stdout,
                            raw_stderr=exec_res.stderr,
                        )
                    )
                    time.sleep(cooldown)

        # 3. Compute statistical summary
        summary: dict[str, Any] = {}
        for res in res_list:
            summary[f"res_{res}"] = {}
            for cfg in self.exp_config.configurations:
                measured = [
                    t.vision_encoding_ms
                    for t in trials
                    if not t.is_warmup
                    and t.resolution == res
                    and t.configuration_id == cfg.id
                    and t.vision_encoding_ms is not None
                ]
                if measured:
                    stats = compute_statistics(measured)
                    summary[f"res_{res}"][cfg.id] = asdict(stats)
                else:
                    summary[f"res_{res}"][cfg.id] = {"error": "No valid measurements"}

            # Compute comparative ratios if both GPU and mixed exist
            cfg_mixed = summary[f"res_{res}"].get("gpu_lm_cpu_vision")
            cfg_gpu = summary[f"res_{res}"].get("gpu_all")
            if cfg_mixed and cfg_gpu and "median" in cfg_mixed and "median" in cfg_gpu:
                ratio = compute_latency_ratio(cfg_gpu["median"], cfg_mixed["median"])
                speedup = compute_speedup(cfg_gpu["median"], cfg_mixed["median"])
                summary[f"res_{res}"]["gpu_to_mixed_ratio"] = ratio
                summary[f"res_{res}"]["cpu_speedup_over_gpu"] = speedup

        exp_result = ExperimentResult(
            experiment_id=self.exp_config.id,
            timestamp=timestamp,
            device_id=self.exp_config.device_config_path,
            model_id=self.exp_config.model_config_path,
            trials=trials,
            summary=summary,
            metadata={
                "resolutions": res_list,
                "configurations": [c.id for c in self.exp_config.configurations],
                "threads": threads,
            },
        )

        exp_result.save(self.output_dir)
        return exp_result
