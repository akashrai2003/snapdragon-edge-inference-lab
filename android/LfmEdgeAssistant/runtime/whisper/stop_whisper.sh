#!/bin/sh
killall -9 whisper-server 2>/dev/null || pkill -9 -f whisper-server 2>/dev/null
echo "whisper-server stopped successfully."
