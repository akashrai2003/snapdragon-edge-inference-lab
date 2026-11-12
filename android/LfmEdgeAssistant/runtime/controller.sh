#!/system/bin/sh
# Runs in the shell/Termux context that owns the existing runtime; no root required.
# This detaches from USB, but does not claim immunity to OS termination or reboot.
pkill -f '^(/system/bin/)?toybox nc .*controller_handler.sh' 2>/dev/null || true
CUE_CONTROL_TOKEN=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
[ ${#CUE_CONTROL_TOKEN} -eq 48 ] || exit 1
export CUE_CONTROL_TOKEN
exec /system/bin/setsid /system/bin/toybox nc -s 127.0.0.1 -p 8082 -L sh /sdcard/Download/controller_handler.sh
