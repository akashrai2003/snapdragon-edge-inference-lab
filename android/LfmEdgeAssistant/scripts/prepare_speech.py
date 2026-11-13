#!/usr/bin/env python3
"""Fetch the pinned, Apache-2.0 offline English recognizer; never commit its weights."""

import argparse
import hashlib
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path

URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/assets/speech/vosk-en"


def prepare(cached_archive=None):
    if (TARGET / "asset-version").exists() and (
        TARGET / "asset-version"
    ).read_text().strip() == SHA256:
        print("Pinned speech model is prepared.")
        return
    with tempfile.TemporaryDirectory(prefix="cue-speech-") as temp:
        archive = Path(temp) / "model.zip"
        if cached_archive is not None:
            shutil.copyfile(cached_archive, archive)
        else:
            urllib.request.urlretrieve(URL, archive)
        with archive.open("rb") as stream:
            digest = hashlib.sha256(stream.read()).hexdigest()
        if digest != SHA256:
            raise ValueError("Speech model checksum mismatch")
        unpack = Path(temp) / "unpack"
        with zipfile.ZipFile(archive) as model:
            for item in model.infolist():
                path = Path(item.filename)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError("Unsafe model archive path")
            model.extractall(unpack)
        source = unpack / "vosk-model-small-en-us-0.15"
        for required in ["am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst"]:
            if not (source / required).is_file():
                raise ValueError("Incomplete speech model")
        (source / "asset-version").write_text(SHA256 + "\n")
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        if TARGET.exists():
            shutil.rmtree(TARGET)
        shutil.copytree(source, TARGET)
    print("Prepared pinned offline speech model:", TARGET)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded official archive.")
    prepare(parser.parse_args().archive)
