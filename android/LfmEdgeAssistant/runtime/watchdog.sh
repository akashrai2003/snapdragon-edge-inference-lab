#!/bin/sh
# Snapdragon Edge AI Stack - Resilient Watchdog Supervisor
# Keeps llama-server and whisper-server running unless explicitly stopped

STOP_FILE="/sdcard/Download/.stop_stack"
LOG_FILE="/sdcard/Download/watchdog.log"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Watchdog supervisor started (PID: $$)" >> "$LOG_FILE"

while true; do
    # If explicit stop signal exists, terminate supervisor
    if [ -f "$STOP_FILE" ]; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] Stop signal detected. Watchdog exiting." >> "$LOG_FILE"
        exit 0
    fi

    # Check llama-server
    if ! pgrep -f "llama-server" >/dev/null 2>&1; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] llama-server not running. Resurrecting..." >> "$LOG_FILE"
        sh /sdcard/Download/lfm3/start_server.sh >> "$LOG_FILE" 2>&1
    fi

    # Check whisper-server
    if ! pgrep -f "whisper-server" >/dev/null 2>&1; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] whisper-server not running. Resurrecting..." >> "$LOG_FILE"
        sh /sdcard/Download/whisper/start_whisper.sh >> "$LOG_FILE" 2>&1
    fi

    sleep 10
done
