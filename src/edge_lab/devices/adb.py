"""ADB execution abstraction with robust error handling, process timeouts, and output capture."""

import os
import shutil
import subprocess
import time
from collections.abc import Sequence
from dataclasses import dataclass


@dataclass
class AdbResult:
    """Structured result from an ADB command invocation."""

    command: list[str]
    returncode: int
    stdout: str
    stderr: str
    duration_seconds: float
    timed_out: bool = False

    @property
    def success(self) -> bool:
        return self.returncode == 0 and not self.timed_out


class AdbError(Exception):
    """Exception raised when an ADB command fails."""

    def __init__(self, message: str, result: AdbResult | None = None):
        super().__init__(message)
        self.result = result


class AdbClient:
    """Robust client for interacting with Android devices via ADB."""

    def __init__(self, serial: str | None = None, adb_path: str | None = None):
        self.adb_path = adb_path or shutil.which("adb") or "adb"
        self.serial = serial or os.environ.get("EDGE_LAB_DEVICE_SERIAL")

    def _build_command(self, args: Sequence[str]) -> list[str]:
        cmd = [self.adb_path]
        if self.serial:
            cmd.extend(["-s", self.serial])
        cmd.extend(args)
        return cmd

    def execute(
        self,
        args: Sequence[str],
        timeout: float | None = 60.0,
        check: bool = False,
    ) -> AdbResult:
        """Execute an arbitrary ADB command array."""
        cmd = self._build_command(args)
        start_time = time.perf_counter()
        timed_out = False

        try:
            process = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            stdout = process.stdout
            stderr = process.stderr
            returncode = process.returncode
        except subprocess.TimeoutExpired as e:
            timed_out = True
            stdout = e.stdout.decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
            stderr = e.stderr.decode() if isinstance(e.stderr, bytes) else (e.stderr or "")
            returncode = -1
        except Exception as e:
            duration = time.perf_counter() - start_time
            raise AdbError(f"Failed to execute ADB command: {cmd}: {e}") from e

        duration = time.perf_counter() - start_time
        result = AdbResult(
            command=cmd,
            returncode=returncode,
            stdout=stdout,
            stderr=stderr,
            duration_seconds=duration,
            timed_out=timed_out,
        )

        if check and not result.success:
            err_msg = (
                f"ADB command failed with code {result.returncode} (timed_out={result.timed_out}): "
                f"{' '.join(cmd)}\nStderr: {result.stderr.strip()}"
            )
            raise AdbError(err_msg, result=result)

        return result

    def shell(
        self,
        command: str | Sequence[str],
        timeout: float | None = 60.0,
        check: bool = False,
    ) -> AdbResult:
        """Execute a command in the Android shell."""
        if isinstance(command, str):
            args = ["shell", command]
        else:
            args = ["shell"] + list(command)
        return self.execute(args, timeout=timeout, check=check)

    def pull(self, remote_path: str, local_path: str, timeout: float = 60.0) -> AdbResult:
        """Pull a file from device to host."""
        return self.execute(["pull", remote_path, local_path], timeout=timeout, check=True)

    def push(self, local_path: str, remote_path: str, timeout: float = 60.0) -> AdbResult:
        """Push a file from host to device."""
        return self.execute(["push", local_path, remote_path], timeout=timeout, check=True)

    def forward(self, local_port: int, remote_port: int) -> AdbResult:
        """Set up ADB port forwarding."""
        return self.execute(["forward", f"tcp:{local_port}", f"tcp:{remote_port}"], check=True)

    def remove_forward(self, local_port: int) -> AdbResult:
        """Remove ADB port forwarding for a local port."""
        return self.execute(["forward", "--remove", f"tcp:{local_port}"])

    def file_exists(self, remote_path: str) -> bool:
        """Check if a remote file or directory exists."""
        res = self.shell(f"test -e '{remote_path}' && echo 'EXISTS'")
        return "EXISTS" in res.stdout

    def get_file_size(self, remote_path: str) -> int:
        """Get size of remote file in bytes."""
        res = self.shell(f"stat -c %s '{remote_path}' 2>/dev/null || wc -c < '{remote_path}'")
        try:
            return int(res.stdout.strip().split()[0])
        except (ValueError, IndexError):
            return -1

    def list_devices(self) -> list[dict[str, str]]:
        """List attached devices with their descriptors."""
        raw_cmd = [self.adb_path, "devices", "-l"]
        res = subprocess.run(raw_cmd, capture_output=True, text=True)
        devices = []
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                serial = parts[0]
                state = parts[1]
                details = {p.split(":")[0]: p.split(":")[1] for p in parts[2:] if ":" in p}
                details["serial"] = serial
                details["state"] = state
                devices.append(details)
        return devices
