"""Unit tests for ADB command construction and mocking."""

from edge_lab.devices.adb import AdbClient


def test_adb_command_construction():
    client = AdbClient(adb_path="adb", serial="TEST_SERIAL_123")
    cmd = client._build_command(["shell", "ls", "-la"])
    assert cmd == ["adb", "-s", "TEST_SERIAL_123", "shell", "ls", "-la"]


def test_adb_command_no_serial():
    client = AdbClient(adb_path="adb", serial=None)
    cmd = client._build_command(["devices"])
    assert cmd == ["adb", "devices"]
