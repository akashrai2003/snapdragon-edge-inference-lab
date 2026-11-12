#!/bin/sh
killall -9 llama-server 2>/dev/null || pkill -9 -f llama-server 2>/dev/null
which termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock
echo "llama-server stopped successfully."
