"""Configuration loading and validation utilities."""

import os
from pathlib import Path
from typing import Any

import yaml

from edge_lab.config.models import DeviceConfig, ExperimentConfig, ModelConfig, RuntimeConfig


def load_yaml(file_path: str | Path) -> dict[str, Any]:
    """Load a raw YAML configuration file."""
    path = Path(file_path)
    if not path.is_file():
        raise FileNotFoundError(f"Configuration file not found: {path}")
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict):
        raise ValueError(f"YAML content in {path} must be a dictionary")
    return data


def load_device_config(file_path: str | Path) -> DeviceConfig:
    """Load and validate a DeviceConfig from YAML."""
    raw = load_yaml(file_path)
    data = raw.get("device", raw)
    config = DeviceConfig.model_validate(data)
    # Environment variable override for serial
    env_serial = os.environ.get("EDGE_LAB_DEVICE_SERIAL")
    if env_serial:
        config.serial = env_serial
    return config


def load_model_config(file_path: str | Path) -> ModelConfig:
    """Load and validate a ModelConfig from YAML."""
    raw = load_yaml(file_path)
    data = raw.get("model", raw)
    return ModelConfig.model_validate(data)


def load_runtime_config(file_path: str | Path) -> RuntimeConfig:
    """Load and validate a RuntimeConfig from YAML."""
    raw = load_yaml(file_path)
    data = raw.get("runtime", raw)
    return RuntimeConfig.model_validate(data)


def load_experiment_config(file_path: str | Path) -> ExperimentConfig:
    """Load and validate an ExperimentConfig from YAML."""
    raw = load_yaml(file_path)
    data = raw.get("experiment", raw)
    return ExperimentConfig.model_validate(data)
