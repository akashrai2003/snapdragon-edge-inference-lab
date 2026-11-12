#!/usr/bin/env bash
set -e

echo "🔍 Checking connected Snapdragon 8 Elite device..."
DEVICE=$(adb devices | grep -E "device$" | awk '{print $1}' | head -n 1)

if [ -z "$DEVICE" ]; then
    echo "❌ No authorized ADB device found. Please ensure USB Debugging is enabled on your phone."
    exit 1
fi

echo "📱 Connected device: $DEVICE"
echo "🔌 Forwarding ports (8080 -> LLM WebUI, 8081 -> Whisper STT)..."
adb forward tcp:8080 tcp:8080
adb forward tcp:8081 tcp:8081

# Check if servers are running on phone
echo "🔍 Checking phone stack status..."
if ! adb shell "pgrep -f 'llama-server' >/dev/null 2>&1"; then
    echo "⚠️  llama-server is off on device. Starting Edge AI stack..."
    adb shell "sh /sdcard/Download/start_all.sh"
    sleep 3
else
    echo "✅ Edge AI stack is already active on phone."
fi

# Verify HTTP endpoint
if curl -s -I http://localhost:8080/ >/dev/null 2>&1; then
    echo "🎉 Everything is READY!"
    echo "👉 Open Web UI in browser: http://localhost:8080"
else
    echo "⏳ Server is initializing, please wait a few seconds and open http://localhost:8080"
fi
