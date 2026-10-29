# Architecture Decisions — Snapdragon Edge Inference Lab

## ADR-001: Separation of Host Orchestrator and Device Runtime
- **Context:** The host workstation is x86_64 Ubuntu 22.04 LTS, while the physical inference target is an unrooted ARM64 Android 16 device.
- **Decision:** Build all orchestration, configuration management, trace analysis, and statistics in Python on the host workstation. Communicate with the device exclusively via standard `adb` commands and port forwarding.
- **Consequences:** Avoids bloating the mobile device with Python runtime or heavyweight data science dependencies. Ensures tests can execute in CI without physical hardware.

## ADR-002: Android 16 Stock Tracing Compatibility
- **Context:** Stock Android 16 restricts access to `/data/local/tmp` for system daemons like `perfetto` (running as UID 9999).
- **Decision:** Use standard Android tracing directories `/data/misc/perfetto-configs/` for configuration files and `/data/misc/perfetto-traces/` for trace capture, which are world/group accessible to the `shell` UID.
- **Consequences:** Enables zero-root Perfetto system tracing on commercial retail Snapdragon flagship devices.

## ADR-003: Subprocess Arrays vs. Shell Interpolation
- **Context:** Complex CLI flags and paths passed to ADB can create shell escaping and quote injection bugs.
- **Decision:** Always use array-based `subprocess.run(["adb", "-s", serial, "shell", ...])` and encapsulate ADB execution in `AdbClient`.
- **Consequences:** Safe parameter passing, clean process timeouts, and consistent capturing of exit codes and stdout/stderr.

## ADR-004: TraceProcessor SQL Analysis in Python
- **Context:** Trace processing previously required compiling native C++ tools.
- **Decision:** Utilize the official `perfetto` Python package with `TraceProcessor`, which embeds the standalone TraceProcessor engine.
- **Consequences:** Full SQL queries against Android traces directly within Python scripts, exporting data into pandas DataFrames and structured JSON.
