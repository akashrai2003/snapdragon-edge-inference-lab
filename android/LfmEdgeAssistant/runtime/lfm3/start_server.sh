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
mkdir -p "$GGML_OPENCL_KERNEL_CACHE_DIR" 2>/dev/null

# Context size (default: 4096 for ultra-responsive low-latency edge inference)
CTX_SIZE=${1:-4096}

# Kill existing instance if running
killall -9 llama-server 2>/dev/null || pkill -9 -f llama-server 2>/dev/null

# Auto-detect OpenCL GPU capability in current execution namespace
DEVICE_ARGS="--device none -ngl 0"
if env LD_LIBRARY_PATH="$BASE_DIR/lib" "$BASE_DIR/bin/llama-server" --list-devices 2>/dev/null | grep -q "GPUOpenCL"; then
    echo "Found Qualcomm Adreno GPU with OpenCL support! Offloading to Adreno 840 GPU..."
    DEVICE_ARGS="--device GPUOpenCL -ngl 99 -ctk q8_0 -ctv q8_0"
else
    echo "Running with Snapdragon Oryon CPU NEON vector acceleration..."
fi

echo "Starting llama-server for LFM3-VL-2B (Context: ${CTX_SIZE}) on 0.0.0.0:8080..."
nohup env LD_LIBRARY_PATH="$BASE_DIR/lib" "$BASE_DIR/bin/llama-server" \
  -m /sdcard/Download/lfm3/LFM3-VL-2B-Q4_K_M.gguf \
  --mmproj /sdcard/Download/lfm3/mmproj-LFM3-VL-2B-Q8_0.gguf \
  -c "${CTX_SIZE}" \
  -t 4 \
  $DEVICE_ARGS \
  --path /sdcard/Download/lfm3/webui \
  --host 0.0.0.0 \
  --port 8080 > /sdcard/Download/lfm3/server.log 2>&1 &

SERVER_PID=$!
echo "llama-server launched in background (PID: $SERVER_PID)"
echo "Log file: /sdcard/Download/lfm3/server.log"
echo "Access on phone browser: http://localhost:8080"
