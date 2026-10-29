"""Unit tests for configuration loading and validation."""

from pathlib import Path

from edge_lab.config.loader import (
    load_device_config,
    load_experiment_config,
    load_model_config,
    load_runtime_config,
)


def test_load_configs():
    repo_root = Path(__file__).parents[2]

    # Device config
    dev_path = repo_root / "configs" / "devices" / "realme-gt8-pro.yaml"
    dev = load_device_config(dev_path)
    assert dev.id == "realme-gt8-pro"
    assert dev.soc.model == "SM8850"
    assert dev.soc.cpu.cores_total == 8
    assert dev.soc.gpu.name == "Qualcomm Adreno 840"

    # Model config
    model_path = repo_root / "configs" / "models" / "lfm3-vl-2b.yaml"
    model = load_model_config(model_path)
    assert model.id == "lfm3-vl-2b"
    assert model.language_component.quantization == "Q4_K_M"
    assert model.vision_projector is not None
    assert model.vision_projector.quantization == "Q8_0"

    # Runtime configs
    cpu_path = repo_root / "configs" / "runtimes" / "llamacpp-cpu.yaml"
    cpu_rt = load_runtime_config(cpu_path)
    assert cpu_rt.backend == "CPU"

    gpu_path = repo_root / "configs" / "runtimes" / "llamacpp-opencl.yaml"
    gpu_rt = load_runtime_config(gpu_path)
    assert gpu_rt.backend == "GPUOpenCL"

    # Experiment config
    exp_path = repo_root / "configs" / "experiments" / "resolution-crossover.yaml"
    exp = load_experiment_config(exp_path)
    assert exp.id == "resolution-crossover"
    assert len(exp.resolutions) == 5
    assert len(exp.configurations) == 3
