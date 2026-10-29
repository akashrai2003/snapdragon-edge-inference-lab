# Android Perfetto Systems Profiling Guide

This guide explains how Perfetto system tracing works on Android 16, how the Linux kernel scheduler is captured without root, and how to analyze performance traces using TraceProcessor SQL.

---

## 1. Perfetto Tracing Architecture

Perfetto is the unified performance tracing service for the Android platform and the Linux kernel:

```
USERSPACE (Host Workstation)
┌──────────────────────────────────────────────────────────────┐
│ edge-lab profile record  /  edge-lab profile analyze         │
│ (TraceProcessor SQL analysis via Python API)                 │
└──────────────────────────────▲───────────────────────────────┘
                               │ ADB Pull / Push (.pbtx / .pftrace)
DEVICE KERNEL & USERSPACE (Android 16)
┌──────────────────────────────────────────────────────────────┐
│  /system/bin/perfetto (CLI Controller)                       │
│  └── Reads config from /data/misc/perfetto-configs/          │
│                                                              │
│  traced (Central Tracing Daemon, UID 9999)                   │
│  └── Ring Buffers in POSIX Shared Memory (64-128 MB)         │
│                                                              │
│  traced_probes (Kernel Probe Daemon)                         │
│  ├── Linux Kernel ftrace (/sys/kernel/tracing/)              │
│  │   ├── sched/sched_switch, sched/sched_wakeup              │
│  │   ├── power/cpu_frequency, power/cpu_idle                │
│  │   └── thermal/thermal_temperature                         │
│  ├── /proc and /sys pollers (Process & Memory stats)         │
│  └── Android Power Rails (Battery & Rail energy)             │
│                                                              │
│  Output Destination: /data/misc/perfetto-traces/             │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Kernel ftrace Tracepoints Under the Hood

### `sched/sched_switch` (Context Switches)
- Fired whenever the Linux kernel preempts a thread or a thread voluntarily yields execution.
- Captures:
  - `prev_comm`: Name of outgoing thread (e.g., `llama-mtmd-cli` or `ggml-opencl`).
  - `prev_pid`: Outgoing thread ID.
  - `prev_state`: Why did it stop running? (`R` = runnable/preempted, `S` = sleeping/waiting for I/O or OpenCL, `D` = uninterruptible disk wait).
  - `next_comm`: Name of incoming thread.
  - `cpu`: Physical Oryon CPU core ID (0–7) where the switch occurred.

### `sched/sched_wakeup` & `sched_waking` (Scheduling Delay)
- Fired when a thread transitions from sleeping to runnable (e.g. an OpenCL kernel finishes and signals the CPU thread).
- **Runnable Latency:** The time interval between `sched_wakeup` and the subsequent `sched_switch` when the thread actually gets CPU time. High runnable latency indicates CPU starvation or thread oversubscription.

### `power/cpu_frequency` (DVFS State)
- Captures Dynamic Voltage and Frequency Scaling (DVFS).
- On the Snapdragon 8 Elite:
  - Performance cores throttle between ~800 MHz and 3628 MHz.
  - Prime cores throttle between ~1200 MHz and 4608 MHz.
- Tracing this reveals whether thermal throttling reduced CPU clock frequency during repeated inference trials.

---

## 3. Android 16 Permission Architecture (Zero-Root Operation)

On retail, unrooted Android 16 devices, SELinux policies prevent system daemons from accessing arbitrary paths:
- **Why `/data/local/tmp` fails:** The `traced_probes` daemon runs as UID 9999 (`traced`). It does not have read permissions to files created by the `shell` user in `/data/local/tmp`.
- **The Official Solution:** Android provides dedicated directories with group `shell` (GID 2000) and sticky permissions:
  - Config directory: `/data/misc/perfetto-configs/`
  - Trace output directory: `/data/misc/perfetto-traces/`
- By pushing configurations and saving `.pftrace` captures in these directories, the platform achieves **100% stock, unrooted system tracing**.

---

## 4. TraceProcessor SQL Analysis

Perfetto parses binary trace buffers into an in-memory SQLite virtual database. Instead of inspecting gigabyte trace files by hand, you can execute SQL queries directly.

### Core Tables & Schema
- `sched`: Every scheduled thread slice (`ts`, `dur`, `cpu`, `utid`).
- `thread`: Thread metadata (`utid`, `tid`, `name`, `upid`).
- `process`: Process metadata (`upid`, `pid`, `name`).
- `counter`: System metrics over time (`ts`, `track_id`, `value`).
- `cpu_counter_track`: Counter tracks mapped to physical CPUs (`id`, `name`, `cpu`).

### Example 1: Querying Thread CPU Time for Inference
```sql
SELECT
    t.name AS thread_name,
    p.name AS process_name,
    COUNT(s.id) AS slices_count,
    ROUND(SUM(s.dur) / 1e6, 3) AS total_cpu_time_ms,
    ROUND(AVG(s.dur) / 1e6, 3) AS avg_slice_time_ms
FROM sched s
JOIN thread t USING (utid)
LEFT JOIN process p USING (upid)
WHERE (p.name LIKE '%llama%' OR t.name LIKE '%llama%')
GROUP BY t.utid
ORDER BY total_cpu_time_ms DESC;
```

### Example 2: Querying CPU Frequency Distribution Across Physical Cores
```sql
SELECT
    t.cpu,
    c.value / 1000 AS freq_mhz,
    ROUND(SUM(c.dur) / 1e6, 2) AS duration_ms
FROM counter c
JOIN cpu_counter_track t ON c.track_id = t.id
WHERE t.name = 'cpufreq'
GROUP BY t.cpu, freq_mhz
ORDER BY t.cpu, freq_mhz DESC;
```

---

## 5. Visualizing Traces in Perfetto UI

1. Run a trace capture:
   ```bash
   edge-lab profile record --duration 10 --config perfetto/configs/inference-standard.pbtx --output results/profiling/my_trace.pftrace
   ```
2. Open your web browser and navigate to:
   [https://ui.perfetto.dev/](https://ui.perfetto.dev/)
3. Click **"Open trace file"** in the sidebar and select `results/profiling/my_trace.pftrace`.
4. Navigate the timeline:
   - `W / S`: Zoom in / out.
   - `A / D`: Pan left / right.
   - Inspect CPU cores 0–7 to see which threads ran on Performance cores vs Prime cores.
