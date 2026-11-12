#!/bin/sh
echo "🚀 Starting Snapdragon Edge AI Stack with Persistent Watchdog & Controller..."

rm -f /sdcard/Download/.stop_stack

# Ensure controller daemon is running on 8082 with setsid (detached from ADB)
if ! pgrep -f 'nc -s 127.0.0.1 -p 8082' >/dev/null 2>&1; then
    /system/bin/setsid sh /sdcard/Download/controller.sh </dev/null >/dev/null 2>&1 &
fi

# Launch AI Servers
sh /sdcard/Download/lfm3/start_server.sh
sh /sdcard/Download/whisper/start_whisper.sh

# Launch persistent watchdog
pkill -9 -f "watchdog.sh" 2>/dev/null
/system/bin/setsid sh /sdcard/Download/watchdog.sh </dev/null >/dev/null 2>&1 &
WATCHDOG_PID=$!

echo "🛡️  Watchdog supervisor active (PID: $WATCHDOG_PID)"
echo "🎛️  Controller listening on http://127.0.0.1:8082"
echo "✅ Stack Ready"
