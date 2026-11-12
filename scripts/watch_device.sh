#!/usr/bin/env bash
# Background watcher that auto-forwards ports whenever the phone is plugged in
echo "👀 Watching for Snapdragon device connection..."
while true; do
    adb wait-for-device
    echo "📱 Device detected! Forwarding ports..."
    adb forward tcp:8080 tcp:8080 2>/dev/null || true
    adb forward tcp:8081 tcp:8081 2>/dev/null || true
    while adb get-state 2>/dev/null | grep -q "device"; do
        sleep 3
    done
    echo "🔌 Device disconnected. Waiting for reconnection..."
done
