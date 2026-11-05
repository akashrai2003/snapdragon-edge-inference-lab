"""Unit tests for warm-encode parsing and helpers (no hardware required)."""

import io

from PIL import Image

from edge_lab.experiments.warm_encode import (
    WarmEncodeTrial,
    build_server_flags,
    make_unique_jpeg,
    summarize,
)
from edge_lab.metrics.parser import parse_mtmd_cli_output, parse_server_image_timings

SERVER_LOG = """\
slot encode_mtmd_: id  0 | task 3 | mtmd batch encoded in 812.50 ms, n_chunks = 1
decoding image batch 1/1, n_tokens_batch = 256
image decoded (batch 1/1) in 301 ms
"""


def test_parse_server_image_timings():
    t = parse_server_image_timings(SERVER_LOG)
    assert t.encode_ms == 812.5
    assert t.n_encodes == 1
    assert t.decode_ms == 301.0
    assert t.image_tokens == 256


def test_parse_server_image_timings_helper_fallback_and_empty():
    t = parse_server_image_timings("image slice encoded in 100 ms\nimage slice encoded in 50 ms\n")
    assert t.encode_ms == 150.0
    assert t.n_encodes == 2
    empty = parse_server_image_timings("nothing here")
    assert empty.encode_ms is None and empty.decode_ms is None and empty.image_tokens is None


def test_parse_mtmd_cli_clip_diagnostics():
    log = (
        "warmup: graph splits = 1, nodes = 900\n"
        "warmup: flash attention is enabled\n"
        "clip_encode: lazy warmup (buffers not allocated) took 1050.25 ms, enable warmup\n"
        "decoding image batch 1/1, n_tokens_batch = 256\n"
        "mtmd batch encoding done in 1814 ms\n"
    )
    t = parse_mtmd_cli_output(log)
    assert t.vision_encoding_ms == 1814.0
    assert t.clip_graph_splits == 1
    assert t.clip_flash_attn is True
    assert t.clip_lazy_warmup_ms == 1050.25
    assert t.image_tokens == 256
    assert not t.fallback_detected


def test_unsupported_clip_ops_flagged():
    t = parse_mtmd_cli_output("WARNING: the CLIP graph uses unsupported operators by the backend")
    assert t.fallback_detected


def test_make_unique_jpeg_resizes_and_differs():
    base = Image.new("RGB", (1000, 500), (120, 130, 140))
    a = make_unique_jpeg(base, 512, 1)
    b = make_unique_jpeg(base, 512, 2)
    assert a != b
    with Image.open(io.BytesIO(a)) as im:
        assert im.size == (512, 256)


def test_build_server_flags():
    assert build_server_flags(None) == ["--warmup"]
    assert build_server_flags(256) == ["--warmup", "--image-max-tokens 256"]


def _trial(i, res, cfg, warm, enc):
    return WarmEncodeTrial(
        trial_index=i,
        configuration_id=cfg,
        resolution=res,
        is_warmup=warm,
        success=True,
        image_encode_ms=enc,
        image_tokens=256,
    )


def test_summarize_separates_first_request():
    trials = [
        _trial(1, 512, "gpu_all", True, 2000.0),
        _trial(2, 512, "gpu_all", False, 600.0),
        _trial(3, 512, "gpu_all", False, 700.0),
    ]
    s = summarize(trials)["res_512"]["gpu_all"]
    assert s["first_request_encode_ms"] == 2000.0
    assert s["warm_encode_ms"]["median"] == 650.0
    assert s["image_tokens"] == 256


def test_make_unique_jpeg_decoded_pixels_differ():
    base = Image.new("RGB", (512, 512), (120, 130, 140))
    decoded = []
    for v in range(1, 6):
        with Image.open(io.BytesIO(make_unique_jpeg(base, 512, v))) as im:
            decoded.append(im.convert("RGB").tobytes())
    assert len(set(decoded)) == 5
