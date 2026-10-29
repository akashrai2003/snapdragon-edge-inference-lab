# Experiment Execution Log

| Date (UTC) | Experiment ID | Target Device | Configuration | Resolution | Result Summary | Notes |
|---|---|---|---|---|---|---|
| Historical Baseline | `baseline-text-cpu` | Realme GT 8 Pro | Termux Native CPU (4T) | N/A | pp512: 68.34 t/s, tg128: 25.93 t/s | b11967 |
| Historical Baseline | `baseline-text-snapdragon-cpu` | Realme GT 8 Pro | Snapdragon Cross CPU (4T) | N/A | pp512: 103.98 t/s, tg128: 31.12 t/s | ac8e169 |
| Historical Baseline | `baseline-text-snapdragon-gpu` | Realme GT 8 Pro | Adreno 840 OpenCL (-ngl 99) | N/A | pp512: 738.10 t/s, tg128: 44.48 t/s | ac8e169 |
| Historical Baseline | `baseline-vision-512-mixed` | Realme GT 8 Pro | GPU LM + CPU Vision | 512px | Median: 1632 ms | ac8e169 |
| Historical Baseline | `baseline-vision-512-gpu` | Realme GT 8 Pro | GPU LM + GPU Vision | 512px | Median: 2517 ms | ac8e169 (+54% slower) |
| 2026-10-29 17:08 | `smoke-test-512` | Realme GT 8 Pro | Mixed vs GPU Vision | 512px | Mixed: 1391 ms, GPU: 1814 ms | Live smoke test on device (+30.4% slower) |
| 2026-10-29 17:09 | `smoke-trace-01` | Realme GT 8 Pro | CPU Scheduling Trace | N/A | 557,411 bytes pulled | Perfetto trace captured & SQL verified |
