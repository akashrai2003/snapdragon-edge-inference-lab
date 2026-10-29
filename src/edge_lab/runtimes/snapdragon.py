"""Snapdragon llama.cpp runtime execution adapter."""

import logging
from dataclasses import dataclass

from edge_lab.devices.adb import AdbClient, AdbResult
from edge_lab.metrics.parser import VisionEncodingTiming, parse_mtmd_cli_output

logger = logging.getLogger("snapdragon_runtime")


@dataclass
class RuntimeExecutionResult:
    """Result of an inference or benchmark invocation on Snapdragon device."""

    command: str
    returncode: int
    duration_seconds: float
    stdout: str
    stderr: str
    timing: VisionEncodingTiming | None = None
    timed_out: bool = False

    @property
    def success(self) -> bool:
        return self.returncode == 0 and not self.timed_out


class SnapdragonRuntime:
    """Orchestrates deployed Snapdragon llama.cpp tools on physical Android device."""

    def __init__(
        self,
        adb: AdbClient,
        bin_dir: str = "/data/local/tmp/llama.cpp/bin",
        lib_dir: str = "/data/local/tmp/llama.cpp/lib",
        cl_cache_dir: str = "/data/local/tmp/llama.cpp/cl-cache",
    ):
        self.adb = adb
        self.bin_dir = bin_dir
        self.lib_dir = lib_dir
        self.cl_cache_dir = cl_cache_dir

    def run_mtmd_cli(
        self,
        model_path: str,
        mmproj_path: str,
        image_path: str,
        prompt: str = "Describe this image in detail.",
        ngl: int = 0,
        device: str | None = None,
        mmproj_offload: bool = False,
        mmproj_device: str | None = None,
        threads: int = 4,
        context_size: int = 2048,
        max_tokens: int = 128,
        temperature: float = 0.1,
        warmup: bool = False,
        timeout_seconds: float = 120.0,
        extra_env: dict[str, str] | None = None,
    ) -> RuntimeExecutionResult:
        """Run multimodal CLI inference (llama-mtmd-cli) on device."""
        env_vars = {
            "LD_LIBRARY_PATH": self.lib_dir,
            "GGML_OPENCL_KERNEL_CACHE_DIR": self.cl_cache_dir,
        }
        if extra_env:
            env_vars.update(extra_env)

        env_prefix = " ".join([f"{k}='{v}'" for k, v in env_vars.items()])
        binary_path = f"{self.bin_dir}/llama-mtmd-cli"

        # Build flags
        flags: list[str] = [
            f"-m '{model_path}'",
            f"--mmproj '{mmproj_path}'",
            f"--image '{image_path}'",
            f"-p '{prompt}'",
            f"-t {threads}",
            f"-c {context_size}",
            f"-n {max_tokens}",
            f"--temp {temperature}",
            f"-ngl {ngl}",
        ]

        if not warmup:
            flags.append("--no-warmup")

        if mmproj_offload:
            flags.append("--mmproj-offload")
            if mmproj_device:
                flags.append(f"--mmproj-device '{mmproj_device}'")
        else:
            flags.append("--no-mmproj-offload")

        if device:
            flags.append(f"--device '{device}'")

        full_cmd = f"{env_prefix} {binary_path} {' '.join(flags)}"

        adb_res = self.adb.shell(full_cmd, timeout=timeout_seconds)

        combined_output = f"{adb_res.stdout}\n{adb_res.stderr}"
        timing = parse_mtmd_cli_output(combined_output)

        return RuntimeExecutionResult(
            command=full_cmd,
            returncode=adb_res.returncode,
            duration_seconds=adb_res.duration_seconds,
            stdout=adb_res.stdout,
            stderr=adb_res.stderr,
            timing=timing,
            timed_out=adb_res.timed_out,
        )

    def run_llama_bench(
        self,
        model_path: str,
        prompt_tokens: int = 512,
        gen_tokens: int = 128,
        ngl: int = 0,
        device: str | None = None,
        threads: int = 4,
        repetitions: int = 3,
        timeout_seconds: float = 180.0,
    ) -> AdbResult:
        """Run llama-bench throughput benchmark on device."""
        env_prefix = (
            f"LD_LIBRARY_PATH='{self.lib_dir}' GGML_OPENCL_KERNEL_CACHE_DIR='{self.cl_cache_dir}'"
        )
        binary_path = f"{self.bin_dir}/llama-bench"

        flags = [
            f"-m '{model_path}'",
            f"-p {prompt_tokens}",
            f"-n {gen_tokens}",
            f"-ngl {ngl}",
            f"-t {threads}",
            f"-r {repetitions}",
        ]
        if device:
            flags.append(f"--device '{device}'")

        cmd = f"{env_prefix} {binary_path} {' '.join(flags)}"
        return self.adb.shell(cmd, timeout=timeout_seconds)
