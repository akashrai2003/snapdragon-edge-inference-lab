#!/usr/bin/env python3
"""Run Cue's device probes and restore previously enabled hands-free after instrumentation exits."""

import argparse
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "ai.snapdragon.edge"
SERVICE = f"{PACKAGE}/.service.VoiceWakeService"
ACTIVITY = f"{PACKAGE}/.MainActivity"


def run(adb, *args, capture=False):
    return subprocess.run([*adb, *args], check=True, text=True, capture_output=capture)


def metrics(adb):
    xml = run(
        adb, "shell", "run-as", PACKAGE, "cat", "shared_prefs/cue_metrics.xml", capture=True
    ).stdout
    return {n.get("name"): int(n.get("value")) for n in ET.fromstring(xml) if n.tag == "long"}


def await_capture(adb, previous_count, timeout=15):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        sample = metrics(adb)
        if sample.get("wake_capture_started_count", 0) > previous_count and sample.get(
            "wake_capture_started_elapsed", 0
        ) > sample.get("wake_capture_stopped_elapsed", 0):
            return
        time.sleep(0.25)
    raise RuntimeError(
        "Wake capture did not become ready; no live voice check should be requested."
    )


def verify_process_recovery(adb):
    """Kill only Cue's idle debug process, without force-stopping the opted-in package."""
    previous = metrics(adb)
    pid = run(adb, "shell", "pidof", PACKAGE, capture=True).stdout.strip()
    if not pid.isdecimal():
        raise RuntimeError("Expected exactly one idle Cue process")
    run(adb, "shell", "run-as", PACKAGE, "kill", "-9", pid, capture=True)
    await_capture(adb, previous.get("wake_capture_started_count", 0), timeout=30)
    current_pid = run(adb, "shell", "pidof", PACKAGE, capture=True).stdout.strip()
    recovered = metrics(adb).get("wake_system_recovery_count", 0)
    if current_pid == pid or recovered <= previous.get("wake_system_recovery_count", 0):
        raise RuntimeError("Capture returned without a verified system service restart")
    print("Process recovery: PASS; Android restarted the enabled listener without opening Cue.")


def verify_background_open(adb):
    """Run without instrumentation's background-activity-start privileges."""
    previous = metrics(adb)
    time.sleep(5.5)
    run(
        adb,
        "shell",
        "run-as",
        PACKAGE,
        "/system/bin/am",
        "startservice",
        "--user",
        "0",
        "-n",
        SERVICE,
        "-a",
        f"{PACKAGE}.VERIFY_OPENING",
        capture=True,
    )
    until = time.monotonic() + 7
    while time.monotonic() < until:
        sample = metrics(adb)
        windows = run(adb, "shell", "dumpsys", "window", capture=True).stdout
        focused = any(
            "mCurrentFocus=" in line and f"{PACKAGE}.FloatingAssistantActivity" in line
            for line in windows.splitlines()
        )
        if focused and sample.get("wake_panel_visible_count", 0) > previous.get(
            "wake_panel_visible_count", 0
        ):
            print("Uninstrumented background opening: PASS; floating conversation is focused.")
            run(adb, "shell", "input", "keyevent", "KEYCODE_BACK", capture=True)
            await_capture(adb, previous.get("wake_capture_started_count", 0))
            return
        time.sleep(0.1)
    raise RuntimeError("Wake handoff did not focus the conversation outside instrumentation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--model", action="store_true")
    parser.add_argument(
        "--recovery",
        action="store_true",
        help="Also kill the idle process and verify sticky recovery",
    )
    args = parser.parse_args()
    if args.quiet and args.model:
        parser.error("Run quiet and model probes separately.")
    if args.recovery and not args.quiet:
        parser.error("Use --recovery with --quiet, with hands-free already enabled.")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    xml = run(adb, "shell", "run-as", PACKAGE, "cat", "shared_prefs/mira.xml", capture=True).stdout
    enabled = any(
        n.get("name") == "handsfree" and n.get("value") == "true" for n in ET.fromstring(xml)
    )
    if args.recovery and not enabled:
        parser.error("Recovery testing requires previously enabled hands-free.")
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
            previous_count = metrics(adb).get("wake_capture_started_count", 0)
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
            try:
                await_capture(adb, previous_count)
                print("Restored previously enabled hands-free; continuous capture is ready.")
                if args.quiet:
                    verify_background_open(adb)
                if args.recovery:
                    verify_process_recovery(adb)
            except (RuntimeError, subprocess.CalledProcessError) as error:
                success = False
                print(f"Wake restoration/recovery failed: {error}", file=sys.stderr)
        if not success:
            stopped = subprocess.run(
                [
                    *adb,
                    "shell",
                    "run-as",
                    PACKAGE,
                    "/system/bin/am",
                    "stopservice",
                    "--user",
                    "0",
                    "-n",
                    SERVICE,
                ],
                text=True,
                capture_output=True,
            )
            if stopped.returncode and "was not running" not in stopped.stdout + stopped.stderr:
                print(stopped.stderr or stopped.stdout, file=sys.stderr)
            print("Probe did not pass; listener stopped. Open Cue to retry.", file=sys.stderr)
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
