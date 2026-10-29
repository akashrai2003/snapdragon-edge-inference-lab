# Contributing to Snapdragon Edge Inference Lab

We welcome contributions to expand edge AI inference benchmarking, profiling, and optimization on Snapdragon and mobile ARM platforms.

## Development Workflow

1. Fork or branch from `main`.
2. Ensure you have Python 3.11+ and `uv` installed.
3. Install development dependencies:
   ```bash
   uv pip install -e ".[dev]"
   ```
4. Run the automated test suite and linter before submitting changes:
   ```bash
   pytest
   ruff check .
   ruff format --check .
   ```

## Contribution Principles

- **Separation of Measurements and Hypotheses:** Never fabricate or estimate benchmark numbers to fill tables. Every benchmark claim must be substantiated with verifiable execution evidence.
- **Hardware Safety:** Never write code that attempts to root devices, overwrite CPU governors, or bypass thermal throttles.
- **No Large Binaries or Weights:** Never commit `.gguf` weights, proprietary Qualcomm SDK binaries, or multi-gigabyte raw Perfetto traces to Git.

## Upstream Contributions

When identifying optimization bottlenecks or runtime bugs:
1. Document the reproducible benchmark and trace evidence.
2. Prepare an upstream PR proposal for `llama.cpp` or `GGML`.
3. Provide before-and-after performance measurements.
