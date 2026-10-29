"""Base experiment execution framework with trial scheduling, safety, and telemetry."""

import json
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from edge_lab.devices.adb import AdbClient
from edge_lab.telemetry.monitor import TelemetryMonitor

logger = logging.getLogger("experiment")


@dataclass
class TrialResult:
    """Individual execution trial record."""

    trial_index: int
    configuration_id: str
    resolution: int
    is_warmup: bool
    success: bool
    vision_encoding_ms: float | None = None
    prompt_eval_tok_per_sec: float | None = None
    eval_tok_per_sec: float | None = None
    wall_duration_seconds: float = 0.0
    telemetry_before: dict[str, Any] | None = None
    telemetry_after: dict[str, Any] | None = None
    error_message: str | None = None
    raw_stdout: str = ""
    raw_stderr: str = ""


@dataclass
class ExperimentResult:
    """Complete results of an experiment run."""

    experiment_id: str
    timestamp: float
    device_id: str
    model_id: str
    trials: list[TrialResult] = field(default_factory=list)
    summary: dict[str, Any] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)

    def save(self, output_dir: Path) -> None:
        """Save experiment results, trials CSV, and metadata."""
        output_dir.mkdir(parents=True, exist_ok=True)

        # 1. Save metadata.json
        meta_file = output_dir / "metadata.json"
        with open(meta_file, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "experiment_id": self.experiment_id,
                    "timestamp": self.timestamp,
                    "device_id": self.device_id,
                    "model_id": self.model_id,
                    "metadata": self.metadata,
                },
                f,
                indent=2,
            )

        # 2. Save summary.json
        summary_file = output_dir / "summary.json"

        def _json_default(obj: Any) -> Any:
            if hasattr(obj, "item"):
                return obj.item()
            return str(obj)

        with open(summary_file, "w", encoding="utf-8") as f:
            json.dump(self.summary, f, indent=2, default=_json_default)

        # 3. Save trials.csv
        trials_file = output_dir / "trials.csv"
        with open(trials_file, "w", encoding="utf-8") as f:
            f.write(
                "trial_index,configuration_id,resolution,is_warmup,success,"
                "vision_encoding_ms,prompt_eval_tps,eval_tps,duration_sec,battery_temp_c,thermal_status,error\n"
            )
            for t in self.trials:
                b_temp = (
                    t.telemetry_before.get("battery_temp_celsius", "") if t.telemetry_before else ""
                )
                t_stat = t.telemetry_before.get("thermal_status", "") if t.telemetry_before else ""
                err = f'"{t.error_message}"' if t.error_message else ""
                f.write(
                    f"{t.trial_index},{t.configuration_id},{t.resolution},{t.is_warmup},{t.success},"
                    f"{t.vision_encoding_ms or ''},{t.prompt_eval_tok_per_sec or ''},{t.eval_tok_per_sec or ''},"
                    f"{t.wall_duration_seconds:.3f},{b_temp},{t_stat},{err}\n"
                )


class BaseExperiment(ABC):
    """Abstract base class for all inference lab experiments."""

    def __init__(self, adb: AdbClient, output_dir: Path):
        self.adb = adb
        self.output_dir = output_dir
        self.telemetry_monitor = TelemetryMonitor(adb)

    @abstractmethod
    def run(self) -> ExperimentResult:
        """Execute the experiment matrix."""
        pass
