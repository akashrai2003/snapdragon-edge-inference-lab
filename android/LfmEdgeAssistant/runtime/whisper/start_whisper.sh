#!/bin/sh
# Acquire wake lock if inside Termux
which termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

if [ -d "$HOME/whisper" ]; then
    BASE_DIR="$HOME/whisper"
elif [ -d "/data/data/com.termux/files/home/whisper" ]; then
    BASE_DIR="/data/data/com.termux/files/home/whisper"
else
    BASE_DIR="/data/local/tmp/whisper"
fi

# Kill existing instance if running
killall -9 whisper-server 2>/dev/null || pkill -9 -f whisper-server 2>/dev/null

echo "Starting whisper-server for STT (base.en) on 0.0.0.0:8081..."
nohup env LD_LIBRARY_PATH="$BASE_DIR/lib" "$BASE_DIR/bin/whisper-server" \
  -m /sdcard/Download/whisper/models/ggml-base.en.bin \
  --host 0.0.0.0 \
  --port 8081 \
  -t 6 \
  -bs 1 \
  -ng > /sdcard/Download/whisper/server.log 2>&1 &

SERVER_PID=$!
echo "whisper-server launched in background (PID: $SERVER_PID)"
echo "Log file: /sdcard/Download/whisper/server.log"
echo "Endpoint: http://localhost:8081/inference"
