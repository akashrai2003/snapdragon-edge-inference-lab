"""Pydantic data models for configuration validation and experiment tracking."""

from typing import Any

from pydantic import BaseModel, Field


class CpuClusterConfig(BaseModel):
    name: str
    cores: list[int]
    max_freq_khz: int


class CpuConfig(BaseModel):
    cores_total: int
    microarchitecture: str
    clusters: list[CpuClusterConfig] = Field(default_factory=list)
    features: list[str] = Field(default_factory=list)


class GpuConfig(BaseModel):
    name: str
    opencl_version: str
    opencl_device_name: str
    opencl_platform_name: str = "QUALCOMM Snapdragon(TM)"
    memory_capacity_mib: int = 0


class NpuConfig(BaseModel):
    name: str
    device_id: str = "HTP0"
    architecture: str = "v81"
    fastrpc_domain: str = "nsp1000"


class SocConfig(BaseModel):
    vendor: str = "Qualcomm"
    name: str
    model: str
    platform: str
    arch: str = "aarch64"
    cpu: CpuConfig
    gpu: GpuConfig
    npu: NpuConfig | None = None


class DeviceMemoryConfig(BaseModel):
    total_kb: int


class DeviceOsConfig(BaseModel):
    android_version: int
    kernel_version: str
    rooted: bool = False


class StoragePathsConfig(BaseModel):
    runtime_bin_dir: str = "/data/local/tmp/llama.cpp/bin"
    runtime_lib_dir: str = "/data/local/tmp/llama.cpp/lib"
    opencl_cache_dir: str = "/data/local/tmp/llama.cpp/cl-cache"
    model_storage_dir: str = "/sdcard/Download/lfm3"


class DeviceConfig(BaseModel):
    id: str
    name: str
    model: str
    product: str
    serial: str = ""
    soc: SocConfig
    memory: DeviceMemoryConfig
    os: DeviceOsConfig
    storage_paths: StoragePathsConfig = Field(default_factory=StoragePathsConfig)


class ModelFileConfig(BaseModel):
    filename: str
    quantization: str
    device_path: str
    size_bytes: int = 0


class InferenceParams(BaseModel):
    threads: int = 4
    context_size: int = 2048
    temperature: float = 0.1
    top_p: float = 0.9
    max_tokens: int = 128
    prompt: str = "Describe this image in detail."


class TestImageConfig(BaseModel):
    filename: str
    device_path: str
    size_bytes: int = 0
    base_dimension: int = 512


class ModelConfig(BaseModel):
    id: str
    name: str
    architecture: str = "transformer"
    family: str = ""
    parameters: str = ""
    language_component: ModelFileConfig
    vision_projector: ModelFileConfig | None = None
    default_inference: InferenceParams = Field(default_factory=InferenceParams)
    test_image: TestImageConfig | None = None


class RuntimeConfig(BaseModel):
    id: str
    engine: str = "llama.cpp"
    version: str = "ac8e169"
    target: str = "snapdragon-android"
    backend: str = "CPU"
    status: str = "stable"
    cli_flags: dict[str, Any] = Field(default_factory=dict)
    environment: dict[str, str] = Field(default_factory=dict)


class TrialConfig(BaseModel):
    warmup_runs: int = 1
    measured_repetitions: int = 5
    cooldown_seconds: float = 4.0
    randomize_order: bool = True
    seed: int = 42
    threads: int = 4
    timeout_seconds: int = 120
    max_battery_temp_celsius: float = 42.0


class ExperimentConfigurationItem(BaseModel):
    id: str
    name: str
    ngl: int = 0
    device: str | None = None
    mmproj_offload: bool = False
    mmproj_device: str | None = None


class ExperimentConfig(BaseModel):
    id: str
    name: str
    description: str = ""
    model_config_path: str = Field(alias="model_config")
    device_config_path: str = Field(alias="device_config")
    resolutions: list[int] = Field(default_factory=list)
    configurations: list[ExperimentConfigurationItem] = Field(default_factory=list)
    trials: TrialConfig = Field(default_factory=TrialConfig)
