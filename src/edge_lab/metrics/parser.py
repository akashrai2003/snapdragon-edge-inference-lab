"""Log and output parsers for llama.cpp, llama-bench, and llama-mtmd-cli."""

import re
from dataclasses import dataclass, field


@dataclass
class VisionEncodingTiming:
    """Parsed timing metrics from multimodal vision inference."""

    raw_log: str
    vision_encoding_ms: float | None = None
    prompt_eval_ms: float | None = None
    prompt_eval_tokens: int | None = None
    prompt_eval_tok_per_sec: float | None = None
    eval_ms: float | None = None
    eval_tokens: int | None = None
    eval_tok_per_sec: float | None = None
    load_time_ms: float | None = None
    total_time_ms: float | None = None
    backend_reported: str | None = None
    offloaded_layers: int | None = None
    total_layers: int | None = None
    device_selected: str | None = None
    warnings: list[str] = field(default_factory=list)
    fallback_detected: bool = False


@dataclass
class LlamaBenchRow:
    """A single row from llama-bench output."""

    model: str
    size: str
    params: str
    backend: str
    threads: int
    test: str
    tokens_per_second: float
    stdev: float | None = None


def parse_mtmd_cli_output(log_content: str) -> VisionEncodingTiming:
    """Parse output and timings from llama-mtmd-cli execution."""
    timing = VisionEncodingTiming(raw_log=log_content)

    # 1. Vision encoding log: "mtmd batch encoding done in 1632 ms"
    match_mtmd = re.search(
        r"mtmd batch encoding done in\s+(\d+(?:\.\d+)?)\s*ms", log_content, re.IGNORECASE
    )
    if match_mtmd:
        timing.vision_encoding_ms = float(match_mtmd.group(1))

    # Alternate vision encoding log format: "vision encoding done in X ms"
    if timing.vision_encoding_ms is None:
        alt_match = re.search(
            r"vision encoding done in\s+(\d+(?:\.\d+)?)\s*ms", log_content, re.IGNORECASE
        )
        if alt_match:
            timing.vision_encoding_ms = float(alt_match.group(1))

    # 2. Prompt eval timings:
    # "llama_perf_context_print:        prompt eval time =     321.45 ms /   512 tokens (    0.63 ms per token,  1592.78 tokens per second)"
    # or "llama_print_timings:        prompt eval time = ..."
    match_prompt = re.search(
        r"prompt eval time\s*=\s*(\d+(?:\.\d+)?)\s*ms\s*/\s*(\d+)\s*tokens.*?(\d+(?:\.\d+)?)\s*tokens per second",
        log_content,
    )
    if match_prompt:
        timing.prompt_eval_ms = float(match_prompt.group(1))
        timing.prompt_eval_tokens = int(match_prompt.group(2))
        timing.prompt_eval_tok_per_sec = float(match_prompt.group(3))

    # 3. Generation / decode eval timings:
    # "llama_perf_context_print:             eval time =    1245.20 ms /   128 tokens (    9.73 ms per token,   102.79 tokens per second)"
    match_eval = re.search(
        r"(?:^|\n)\s*(?:llama_\w+:\s*)?eval time\s*=\s*(\d+(?:\.\d+)?)\s*ms\s*/\s*(\d+)\s*runs.*?(\d+(?:\.\d+)?)\s*tokens per second",
        log_content,
    ) or re.search(
        r"(?:^|\n)\s*(?:llama_\w+:\s*)?eval time\s*=\s*(\d+(?:\.\d+)?)\s*ms\s*/\s*(\d+)\s*tokens.*?(\d+(?:\.\d+)?)\s*tokens per second",
        log_content,
    )
    if match_eval:
        timing.eval_ms = float(match_eval.group(1))
        timing.eval_tokens = int(match_eval.group(2))
        timing.eval_tok_per_sec = float(match_eval.group(3))

    # 4. Total and load times:
    match_load = re.search(r"load time\s*=\s*(\d+(?:\.\d+)?)\s*ms", log_content)
    if match_load:
        timing.load_time_ms = float(match_load.group(1))

    match_total = re.search(r"total time\s*=\s*(\d+(?:\.\d+)?)\s*ms", log_content)
    if match_total:
        timing.total_time_ms = float(match_total.group(1))

    # 5. Offloaded layers:
    match_offload = re.search(r"offloaded (\d+)/(\d+) layers to ([^\r\n]+)", log_content)
    if match_offload:
        timing.offloaded_layers = int(match_offload.group(1))
        timing.total_layers = int(match_offload.group(2))
        timing.backend_reported = match_offload.group(3).strip()

    # 6. Device selected
    match_dev = re.search(r"ggml_opencl:\s*(?:selecting\s+)?device:?\s*'([^']+)'", log_content)
    if match_dev:
        timing.device_selected = match_dev.group(1).strip()
    elif "ggml-hex:" in log_content:
        timing.device_selected = "Hexagon HTP"

    # 7. Fallback or CPU warnings
    if "fallback to CPU" in log_content or "not supported by backend" in log_content:
        timing.fallback_detected = True
        timing.warnings.append("Fallback to CPU detected in runtime logs")

    return timing


def parse_llama_bench_output(output: str) -> list[LlamaBenchRow]:
    """Parse markdown / table output from llama-bench."""
    rows = []
    lines = output.splitlines()

    for line in lines:
        line = line.strip()
        # Parse markdown table line: | model | size | params | backend | threads | test | t/s |
        if not line.startswith("|") or "model" in line.lower() or "---" in line:
            # Also check text line like: pp512 = 103.98 ± 11.61 tokens/sec
            match_txt = re.search(
                r"(pp\d+|tg\d+)\s*=\s*(\d+(?:\.\d+)?)\s*(?:±\s*(\d+(?:\.\d+)?))?\s*tokens/sec", line
            )
            if match_txt:
                rows.append(
                    LlamaBenchRow(
                        model="LFM3-VL-2B",
                        size="Q4_K_M",
                        params="1.73B",
                        backend="unknown",
                        threads=4,
                        test=match_txt.group(1),
                        tokens_per_second=float(match_txt.group(2)),
                        stdev=float(match_txt.group(3)) if match_txt.group(3) else None,
                    )
                )
            continue

        parts = [p.strip() for p in line.split("|")[1:-1]]
        if len(parts) >= 7:
            # model | size | params | backend | threads | test | t/s
            model_name = parts[0]
            size_str = parts[1]
            params_str = parts[2]
            backend_str = parts[3]
            try:
                threads_val = int(parts[4])
            except ValueError:
                threads_val = 4
            test_str = parts[5]

            # t/s can be: "738.10 ± 4.67" or "738.10"
            ts_str = parts[6]
            m_ts = re.search(r"(\d+(?:\.\d+)?)(?:\s*±\s*(\d+(?:\.\d+)?))?", ts_str)
            if m_ts:
                tps = float(m_ts.group(1))
                std = float(m_ts.group(2)) if m_ts.group(2) else None
                rows.append(
                    LlamaBenchRow(
                        model=model_name,
                        size=size_str,
                        params=params_str,
                        backend=backend_str,
                        threads=threads_val,
                        test=test_str,
                        tokens_per_second=tps,
                        stdev=std,
                    )
                )

    return rows
