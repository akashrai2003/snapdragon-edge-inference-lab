#!/usr/bin/env python3
"""Check/recreate pinned, sparse source references without installing upstream runtimes."""

import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_ROOT = HERE.parents[4] / "cue-assistant-references"


def git(path, *args):
    result = subprocess.run(
        ["git", "-c", "core.hooksPath=/dev/null", "-C", str(path), *args],
        check=True,
        capture_output=True,
        text=True,
        timeout=300,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_LFS_SKIP_SMUDGE": "1"},
    )
    return result.stdout.strip()


def validate_record(record):
    repo = record["repository"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError(f"Invalid repository: {repo}")
    if record["clone_url"] != f"https://github.com/{repo}.git":
        raise ValueError(f"Unexpected clone URL: {repo}")
    if not re.fullmatch(r"[0-9a-f]{40}", record["revision"]):
        raise ValueError(f"Invalid revision: {repo}")
    for value in [*record["sparse_paths"], *record["reviewed_paths"], *record["license_files"]]:
        path = Path(value)
        if path.is_absolute() or ".." in path.parts or not path.parts:
            raise ValueError(f"Unsafe reference path: {value}")


def check(target, record):
    if git(target, "remote", "get-url", "origin") != record["clone_url"]:
        raise ValueError(f"Origin mismatch; existing checkout left unchanged: {target}")
    if git(target, "rev-parse", "HEAD") != record["revision"]:
        raise ValueError(f"Revision mismatch; existing checkout left unchanged: {target}")
    if git(target, "status", "--porcelain"):
        raise ValueError(f"Modified checkout left unchanged: {target}")
    for value in [*record["reviewed_paths"], *record["license_files"]]:
        if not (target / value).is_file():
            raise ValueError(f"Missing sparse-checkout evidence: {target / value}")


def acquire(root, record, check_only):
    validate_record(record)
    target = root / record["repository"]
    if target.exists():
        check(target, record)
        return "checked"
    if check_only:
        raise ValueError(f"Missing checkout: {target}")

    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".cue-reference-", dir=target.parent))
    try:
        git(temporary, "init", "--quiet")
        git(temporary, "remote", "add", "origin", record["clone_url"])
        git(temporary, "config", "remote.origin.promisor", "true")
        git(temporary, "config", "remote.origin.partialclonefilter", "blob:none")
        git(temporary, "fetch", "--depth=1", "--filter=blob:none", "origin", record["revision"])
        # Git 2.34 needs cone mode on init; `set --cone ...` becomes literal patterns.
        git(temporary, "sparse-checkout", "init", "--cone")
        git(temporary, "sparse-checkout", "set", *record["sparse_paths"])
        git(temporary, "checkout", "--detach", record["revision"])
        check(temporary, record)
        temporary.rename(target)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)  # Only this invocation's owned temporary directory.
    return "cloned"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument(
        "--check", action="store_true", help="Check existing references; no network"
    )
    parser.add_argument("--only", action="append", help="Limit to one recorded owner/repository")
    args = parser.parse_args()
    records = json.loads((HERE / "references.json").read_text())["repositories"]
    names = {r["repository"] for r in records}
    if args.only and (unknown := set(args.only) - names):
        parser.error(f"Unrecorded repositories: {', '.join(sorted(unknown))}")
    selected = [r for r in records if not args.only or r["repository"] in args.only]
    for record in selected:
        state = acquire(args.root.resolve(), record, args.check)
        print(f"{state}: {record['repository']} @ {record['revision'][:12]}", flush=True)
    print(f"PASS: {len(selected)} pinned source references; no upstream setup code executed.")


if __name__ == "__main__":
    main()
