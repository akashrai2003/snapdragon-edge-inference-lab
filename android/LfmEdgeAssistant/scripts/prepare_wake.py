#!/usr/bin/env python3
"""Fetch checksum-pinned Apache-2.0 wake assets; keep generated binaries out of Git."""

import hashlib
import json
import os
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "research/keyword-spotting/wake-assets"
JNI = ROOT / "research/keyword-spotting/jniLibs/arm64-v8a/libsherpa-onnx-jni.so"
FILES = {
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx": "encoder.onnx",
    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx": "decoder.onnx",
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx": "joiner.onnx",
    "tokens.txt": "tokens.txt",
}


def main():
    manifest = json.loads((ROOT / "scripts/wake-assets.json").read_text())
    ASSETS.mkdir(parents=True, exist_ok=True)
    JNI.parent.mkdir(parents=True, exist_ok=True)
    for kind, info in manifest.items():
        outputs = [JNI] if kind == "runtime" else [ASSETS / name for name in FILES.values()]
        stamp = ASSETS / (kind + ".sha256")
        if (
            all(path.is_file() for path in outputs)
            and stamp.exists()
            and stamp.read_text().strip() == info["sha256"]
        ):
            continue
        cache = Path(os.environ.get("MIRA_DEP_CACHE", str(Path.home() / ".cache/mira-build")))
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / (info["sha256"] + ".tar.bz2")
        if not archive.exists():
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
                tmp_path = Path(tmp.name)
            try:
                urllib.request.urlretrieve(info["url"], tmp_path)
                if hashlib.sha256(tmp_path.read_bytes()).hexdigest() != info["sha256"]:
                    raise ValueError("Wake dependency checksum mismatch")
                tmp_path.replace(archive)
            finally:
                tmp_path.unlink(missing_ok=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != info["sha256"]:
            raise ValueError("Cached wake dependency checksum mismatch")
        # Read named members only. Never extract arbitrary archive paths.
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                basename = Path(member.name).name
                target = (
                    JNI
                    if kind == "runtime" and member.name.endswith("arm64-v8a/libsherpa-onnx-jni.so")
                    else ASSETS / FILES[basename]
                    if kind == "model" and basename in FILES
                    else None
                )
                if target is not None:
                    with tar.extractfile(member) as stream:
                        target.write_bytes(stream.read())
            if not all(path.is_file() for path in outputs):
                raise ValueError("Missing wake dependency assets")
        stamp.write_text(info["sha256"] + "\n")
    # BPE encodings generated from the release's bpe.model, verified against tokens.txt.
    (ASSETS / "keywords.txt").write_text(
        "▁HE Y ▁MI RA :1.5 #0.30 @MIRA\n▁HE Y ▁ME ER A :1.5 #0.30 @MIRA\n"
    )
    print("Mira wake assets ready (sherpa-onnx v1.12.17, English GigaSpeech KWS).")


if __name__ == "__main__":
    main()
