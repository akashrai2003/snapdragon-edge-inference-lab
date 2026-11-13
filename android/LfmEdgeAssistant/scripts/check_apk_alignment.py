#!/usr/bin/env python3
"""Verify native ZIP offsets, ELF LOAD alignment and RELRO safety for 16 KB pages."""

import argparse
import struct
import zipfile
from pathlib import Path

PAGE = 16384


def elf_issues(data, label):
    problems = []
    if data[:6] != b"\x7fELF\x02\x01":
        return [f"{label}: expected little-endian ELF64"]
    table = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    headers = [struct.unpack_from("<IIQQQQQQ", data, table + i * entry_size) for i in range(count)]
    loads = [p for p in headers if p[0] == 1]
    relros = [p for p in headers if p[0] == 0x6474E552]
    if not loads:
        return [f"{label}: missing ELF LOAD segments"]
    for p in loads:
        if p[7] < PAGE or p[2] % PAGE != p[3] % PAGE:
            problems.append(f"{label}: LOAD segment is not 16 KB aligned")
    for relro in relros:
        start, end = relro[3], relro[3] + relro[6]
        page_start = start // PAGE * PAGE
        page_end = (end + PAGE - 1) // PAGE * PAGE
        for p in loads:
            if not p[1] & 2:
                continue
            # Writable data outside RELRO must not share its rounded read-only pages.
            left, right = p[3], p[3] + p[6]
            for a, b in [(left, min(right, start)), (max(left, end), right)]:
                if a < b and max(a, page_start) < min(b, page_end):
                    problems.append(f"{label}: writable data shares a 16 KB RELRO page")
    return sorted(set(problems))


def check_apk(path):
    problems = []
    count = 0
    with zipfile.ZipFile(path) as apk, open(path, "rb") as stream:
        for entry in apk.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            count += 1
            stream.seek(entry.header_offset)
            header = stream.read(30)
            filename_size, extra_size = struct.unpack_from("<HH", header, 26)
            offset = entry.header_offset + 30 + filename_size + extra_size
            if entry.compress_type == zipfile.ZIP_STORED and offset % PAGE:
                problems.append(
                    f"{entry.filename}: uncompressed ZIP offset {offset} is not 16 KB aligned"
                )
            problems.extend(elf_issues(apk.read(entry), entry.filename))
    return count, problems


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    count, issues = check_apk(args.apk)
    for issue in issues:
        print("FAIL:", issue)
    if issues:
        raise SystemExit(1)
    print(f"PASS: {count} native libraries meet 16 KB ZIP, LOAD and RELRO checks.")
