#!/usr/bin/env bash
# 启动本机测试服务端（测试模式 + 演示登录 + 第二批功能 + 系统推送），模拟器用 10.0.2.2 访问。
#   scripts/test-server.sh start   编译并启动，密钥写到 build/test-server/env（每次随机，只给本机测试用，不进仓库）
#   scripts/test-server.sh stop
# 服务端源码默认在同级目录 ../tianting-server/server 或 ../im-mvp/server，也可以用 TIANTING_SERVER_DIR 指定。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$root/build/test-server"
port="${TT_PORT:-18280}"
admin_port="${TT_ADMIN_PORT:-18281}"

stop() {
  if [[ -f "$dir/pid" ]]; then
    kill "$(cat "$dir/pid")" 2>/dev/null || true
    rm -f "$dir/pid"
  fi
}

case "${1:-}" in
  stop) stop; exit 0 ;;
  start) ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac

stop
# 同一台机器上可能有别的仓库的测试服务端：端口被占用时直接报错，不要连到别人的服务端上。
if lsof -nP -iTCP:"$port" -sTCP:LISTEN > /dev/null 2>&1 || lsof -nP -iTCP:"$admin_port" -sTCP:LISTEN > /dev/null 2>&1; then
  echo "端口 $port / $admin_port 已被占用" >&2
  exit 1
fi
src="${TIANTING_SERVER_DIR:-}"
if [[ -z "$src" ]]; then
  for c in "$root/../tianting-server/server" "$root/../im-mvp/server"; do
    [[ -d "$c/cmd/im-server" ]] && src="$c" && break
  done
fi
[[ -n "$src" ]] || { echo "找不到服务端源码，请设置 TIANTING_SERVER_DIR" >&2; exit 1; }

mkdir -p "$dir"
rm -f "$dir"/im.db*
(cd "$src" && go build -o "$dir/im-server" ./cmd/im-server)

rnd() { LC_ALL=C tr -dc 'a-zA-Z0-9' < /dev/urandom | head -c 40 || true; }
caller_secret="$(rnd)"
admin_key="$(rnd)"
callers="$dir/callers.json"
secret_b64="$(printf '%s' "$caller_secret" | base64 | tr -d '\n')"
cat > "$callers" <<JSON
[{"id":"android-e2e","alg":"hmac-sha256","secret":"$secret_b64","scopes":["user.ban","msg.delete","mute.write","social.settings","social.friends","push.user","push.all"],"pushTypes":["notice","promo"]}]
JSON
chmod 600 "$callers"

IM_ADDR="127.0.0.1:$port" \
IM_ADMIN_ADDR="127.0.0.1:$admin_port" \
IM_METRICS_ADDR=off \
IM_DB="$dir/im.db" \
IM_TEST_MODE=1 \
IM_DEMO_LOGIN=1 \
IM_V13_ADMIN_FEATURES=1 \
IM_PUSH_ENABLED=1 \
IM_QUARANTINE_KEY="$(rnd)" \
IM_JWT_SECRET="$(rnd)" \
IM_ADMIN_KEY="$admin_key" \
IM_ADMIN_CALLERS="$callers" \
nohup "$dir/im-server" > "$dir/server.log" 2>&1 &
echo $! > "$dir/pid"

for _ in $(seq 1 100); do
  if curl -fsS "http://127.0.0.1:$port/readyz" > /dev/null 2>&1; then
    umask 077
    cat > "$dir/env" <<ENV
TT_ADMIN_KEY=$admin_key
TT_CALLER_ID=android-e2e
TT_CALLER_SECRET=$caller_secret
TT_PORT=$port
TT_ADMIN_PORT=$admin_port
ENV
    echo "test server ready on 127.0.0.1:$port (admin $admin_port)"
    exit 0
  fi
  sleep 0.1
done
echo "服务端没有起来，看 $dir/server.log" >&2
tail -20 "$dir/server.log" >&2
exit 1
