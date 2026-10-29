"""Unit tests for metrics and log parsers."""

from pathlib import Path

from edge_lab.metrics.parser import parse_llama_bench_output, parse_mtmd_cli_output


def test_parse_mtmd_cli_output():
    fixture_path = Path(__file__).parents[1] / "fixtures" / "mtmd_cli_output.txt"
    with open(fixture_path, encoding="utf-8") as f:
        content = f.read()

    timing = parse_mtmd_cli_output(content)
    assert timing.vision_encoding_ms == 1632.0
    assert timing.prompt_eval_tokens == 512
    assert timing.prompt_eval_tok_per_sec == 1592.78
    assert timing.eval_tokens == 128
    assert timing.eval_tok_per_sec == 102.79
    assert timing.load_time_ms == 450.20
    assert timing.total_time_ms == 3648.85
    assert timing.offloaded_layers == 99
    assert timing.total_layers == 99
    assert timing.device_selected == "QUALCOMM Adreno(TM) 840"
    assert not timing.fallback_detected


def test_parse_llama_bench_output():
    fixture_path = Path(__file__).parents[1] / "fixtures" / "llama_bench_output.txt"
    with open(fixture_path, encoding="utf-8") as f:
        content = f.read()

    rows = parse_llama_bench_output(content)
    assert len(rows) == 4
    # Row 0: CPU pp512
    assert rows[0].model == "LFM3-VL-2B"
    assert rows[0].test == "pp512"
    assert rows[0].tokens_per_second == 103.98
    assert rows[0].stdev == 11.61

    # Row 2: OpenCL pp512
    assert rows[2].test == "pp512"
    assert rows[2].backend == "OpenCL"
    assert rows[2].tokens_per_second == 738.10
    assert rows[2].stdev == 4.67
