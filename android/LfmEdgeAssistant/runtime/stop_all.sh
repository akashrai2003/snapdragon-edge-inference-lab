#!/bin/sh
echo "🛑 Stopping Snapdragon Edge AI Stack..."

# Set stop signal so watchdog doesn't resurrect processes
touch /sdcard/Download/.stop_stack

# Terminate watchdog
pkill -9 -f "watchdog.sh" 2>/dev/null

# Terminate servers
sh /sdcard/Download/lfm3/stop_server.sh
sh /sdcard/Download/whisper/stop_whisper.sh

echo "✅ All services and watchdog supervisor stopped."
