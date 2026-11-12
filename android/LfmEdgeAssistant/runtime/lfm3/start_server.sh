#!/bin/sh
# Acquire wake lock if inside Termux
which termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

if [ -d "$HOME/llama_cpp" ]; then
    BASE_DIR="$HOME/llama_cpp"
elif [ -d "/data/data/com.termux/files/home/llama_cpp" ]; then
    BASE_DIR="/data/data/com.termux/files/home/llama_cpp"
else
    BASE_DIR="/data/local/tmp/llama.cpp"
fi

export GGML_OPENCL_KERNEL_CACHE_DIR="$BASE_DIR/cl_cache"

# Context size (default: 32768 with INT8 KV cache)
CTX_SIZE=${1:-32768}

# Kill existing instance if running
killall -9 llama-server 2>/dev/null || pkill -9 -f llama-server 2>/dev/null

echo "Starting llama-server for LFM3-VL-2B (Context: ${CTX_SIZE}, Q8_0 KV Cache) on 0.0.0.0:8080..."
nohup env LD_LIBRARY_PATH="$BASE_DIR/lib" "$BASE_DIR/bin/llama-server" \
  -m /sdcard/Download/lfm3/LFM3-VL-2B-Q4_K_M.gguf \
  --mmproj /sdcard/Download/lfm3/mmproj-LFM3-VL-2B-Q8_0.gguf \
  -c "${CTX_SIZE}" \
  -ctk q8_0 \
  -ctv q8_0 \
  -t 4 \
  -ngl 99 \
  --device GPUOpenCL \
  --path /sdcard/Download/lfm3/webui \
  --host 0.0.0.0 \
  --port 8080 > /sdcard/Download/lfm3/server.log 2>&1 &

SERVER_PID=$!
echo "llama-server launched in background (PID: $SERVER_PID)"
echo "Log file: /sdcard/Download/lfm3/server.log"
echo "Access on phone browser: http://localhost:8080"
echo "Access on laptop: http://192.168.1.3:8080"
