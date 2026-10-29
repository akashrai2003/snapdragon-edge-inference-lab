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
| 2026-10-29 17:24 | `scaling-256px` | Realme GT 8 Pro | Mixed vs GPU Vision | 256px | CPU: 600 ms, GPU: 1281 ms | CPU is 2.13× faster (small token batch) |
| 2026-10-29 17:26 | `scaling-768px` | Realme GT 8 Pro | Mixed vs GPU Vision | 768px | CPU: 5271 ms, GPU: 3338 ms | GPU is 1.58× faster (Crossover passed!) |
| 2026-10-29 17:27 | `scaling-1024px` | Realme GT 8 Pro | Mixed vs GPU Vision | 1024px | CPU: 10475 ms, GPU: 2878 ms | GPU is 3.64× FASTER (2.88s vs 10.48s) |
