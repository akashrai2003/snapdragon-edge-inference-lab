#!/usr/bin/env python3
"""Run Cue's device probes and restore previously enabled hands-free after instrumentation exits."""

import argparse
import subprocess
import sys
import xml.etree.ElementTree as ET

PACKAGE = "ai.snapdragon.edge"
SERVICE = f"{PACKAGE}/.service.VoiceWakeService"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def run(adb, *args, capture=False):
    return subprocess.run([*adb, *args], check=True, text=True, capture_output=capture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--model", action="store_true")
    args = parser.parse_args()
    if args.quiet and args.model:
        parser.error("Run quiet and model probes separately.")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    xml = run(adb, "shell", "run-as", PACKAGE, "cat", "shared_prefs/mira.xml", capture=True).stdout
    enabled = any(
        n.get("name") == "handsfree" and n.get("value") == "true" for n in ET.fromstring(xml)
    )
    command = ["shell", "am", "instrument", "-w"]
    if args.quiet:
        command.extend(["-e", "quiet", "true"])
    if args.model:
        command.extend(["-e", "model", "true"])
    command.append(f"{PACKAGE}.test/{PACKAGE}.VoiceIntegrationProbe")
    success = False
    try:
        result = run(adb, *command, capture=True)
        print(result.stdout, end="")
        success = "INSTRUMENTATION_RESULT: result=PASS" in result.stdout.splitlines()
    finally:
        if success and enabled:
            run(
                adb,
                "shell",
                "am",
                "start",
                "-W",
                "-f",
                "0x24000000",
                "-n",
                ACTIVITY,
                "--es",
                "destination",
                "home",
                capture=True,
            )
            run(
                adb,
                "shell",
                "run-as",
                PACKAGE,
                "/system/bin/am",
                "start-foreground-service",
                "--user",
                "0",
                "-n",
                SERVICE,
                capture=True,
            )
            run(adb, "shell", "input", "keyevent", "KEYCODE_HOME", capture=True)
            print("Restored previously enabled hands-free after the probe.")
        elif not success:
            run(
                adb,
                "shell",
                "run-as",
                PACKAGE,
                "/system/bin/am",
                "stopservice",
                "--user",
                "0",
                "-n",
                SERVICE,
                capture=True,
            )
            print(
                "Probe did not pass; hands-free was left off for explicit retry.", file=sys.stderr
            )
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
