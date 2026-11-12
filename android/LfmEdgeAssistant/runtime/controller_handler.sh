#!/system/bin/sh
# Loopback control protocol. Per-run token + no CORS prevent browser-driven mutations.
ROOT=${CUE_RUNTIME_ROOT:-/sdcard/Download}
read -r request || exit 0
method=${request%% *}
rest=${request#* }
route=${rest%% *}
token=''
origin=''
while IFS= read -r header; do
    header=$(printf '%s' "$header" | tr -d '\r')
    [ -z "$header" ] && break
    case "$header" in
        [Xx]-[Cc][Uu][Ee]-[Cc][Oo][Nn][Tt][Rr][Oo][Ll]:*) token=${header#*:}; token=$(printf '%s' "$token" | tr -d ' ') ;;
        [Oo][Rr][Ii][Gg][Ii][Nn]:*) origin=${header#*:} ;;
    esac
done
code='200 OK'
body='{"status":"unknown"}'
if [ -n "$origin" ]; then
    code='403 Forbidden'; body='{"error":"browser_origin_not_allowed"}'
else
    case "$method $route" in
        'GET /session')
            if [ -n "$CUE_CONTROL_TOKEN" ]; then body="{\"token\":\"$CUE_CONTROL_TOKEN\"}"
            else code='503 Service Unavailable'; body='{"error":"controller_not_initialized"}'; fi ;;
        'GET /status')
            if pgrep -f 'llama-server' >/dev/null 2>&1; then body='{"running":true,"status":"running"}'
            else body='{"running":false,"status":"resting"}'; fi ;;
        'POST /start'|'POST /stop')
            if [ -z "$CUE_CONTROL_TOKEN" ] || [ "$token" != "$CUE_CONTROL_TOKEN" ]; then
                code='403 Forbidden'; body='{"error":"invalid_control_token"}'
            elif [ "$route" = '/start' ]; then
                if ! pgrep -f 'llama-server' >/dev/null 2>&1; then sh "$ROOT/start_all.sh" >/dev/null 2>&1 & fi
                body='{"status":"starting"}'
            else
                sh "$ROOT/stop_all.sh" >/dev/null 2>&1
                body='{"status":"stopping"}'
            fi ;;
        'GET /start'|'GET /stop') code='405 Method Not Allowed'; body='{"error":"use_post"}' ;;
        *) code='404 Not Found'; body='{"error":"not_found"}' ;;
    esac
fi
printf 'HTTP/1.1 %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' "$code" "${#body}" "$body"
