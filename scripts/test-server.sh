#!/usr/bin/env bash
# 启动本机测试服务端（测试模式 + 演示登录 + 邀请码注册 + 隐私说明 + 第二批功能 + 系统推送 + 图片和文件），模拟器用 10.0.2.2 访问。
#   scripts/test-server.sh start   编译并启动，密钥写到 build/test-server/env（每次随机，只给本机测试用，不进仓库）
#   scripts/test-server.sh stop
# 图片和文件（C2）：同时启动 scripts/fake_s3.py（只监听 127.0.0.1:$TT_S3_PORT），服务端 IM_FILES_ENABLED=1。预签名地址是
# http://127.0.0.1:$TT_S3_PORT，模拟器要先 adb reverse 同一个端口（run-tests.sh 做）。TT_FILES=0 时不启动、不打开文件功能。
# TT_S3_ORIGINS：假 S3 的 CORS 放行的源（逗号分隔，默认不放行；FE-640 跨端用例里 H5 的页面也连这个服务端）。
# 撤回、引用、@（C4）：默认 IM_MSG_ACTIONS_ENABLED=1（要 IM_QUARANTINE_KEY，下面随机生成），IM_MSG_ACTIONS_ENABLED=0 关掉。
# 其他 IM_* 环境变量（例如 IM_ACCESS_TTL=30s）原样传给服务端。
# TT_SERVER_STATE_DIR：服务端二进制、库、日志、env 放哪（默认 build/test-server），可以指到仓库外的临时目录。
# 服务端源码默认在同级目录 ../tianting-server/server 或 ../im-mvp/server，也可以用 TIANTING_SERVER_DIR 指定。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dir="${TT_SERVER_STATE_DIR:-$root/build/test-server}"
port="${TT_PORT:-18280}"
admin_port="${TT_ADMIN_PORT:-18281}"
metrics_port="${TT_METRICS_PORT:-18282}"
s3_port="${TT_S3_PORT:-18283}"
files="${TT_FILES:-1}"

stop() {
  for f in pid s3.pid; do
    if [[ -f "$dir/$f" ]]; then
      p="$(cat "$dir/$f")"
      kill "$p" 2>/dev/null || true
      # 等它真的退出（端口释放），否则紧接着的 start 会报端口被占用。最多 5 秒。
      for _ in $(seq 1 50); do kill -0 "$p" 2>/dev/null || break; sleep 0.1; done
      rm -f "$dir/$f"
    fi
  done
}

case "${1:-}" in
  stop) stop; exit 0 ;;
  start) ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac

stop
# 同一台机器上可能有别的仓库的测试服务端：端口被占用时直接报错，不要连到别人的服务端上。
for p in "$port" "$admin_port" "$metrics_port" "$s3_port"; do
  if lsof -nP -iTCP:"$p" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "端口 $p 已被占用（$port / $admin_port / $metrics_port / $s3_port）" >&2
    exit 1
  fi
done
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
[{"id":"android-e2e","alg":"hmac-sha256","secret":"$secret_b64","scopes":["user.ban","group.write","msg.delete","mute.write","social.settings","social.friends","push.user","push.all","account.invite"],"pushTypes":["notice","promo"]}]
JSON
chmod 600 "$callers"

file_env=()
if [[ "$files" == "1" ]]; then
  nohup python3 -I "$root/scripts/fake_s3.py" --port "$s3_port" --origins "${TT_S3_ORIGINS:-}" > "$dir/s3.log" 2>&1 &
  echo $! > "$dir/s3.pid"
  for _ in $(seq 1 50); do
    curl -fsS "http://127.0.0.1:$s3_port/__health" > /dev/null 2>&1 && break
    sleep 0.1
  done
  # 凭证是假 S3 用的假值（不校验签名），不是真实密钥。
  file_env=(
    IM_FILES_ENABLED=1
    "IM_OBJSTORE_ENDPOINT=http://127.0.0.1:$s3_port"
    IM_OBJSTORE_REGION=us-east-1
    IM_OBJSTORE_BUCKET=chat
    IM_OBJSTORE_PUBLIC_BUCKET=avatars
    "IM_OBJSTORE_PUBLIC_BASE_URL=http://127.0.0.1:$s3_port/avatars"
    IM_OBJSTORE_ACCESS_KEY=android-e2e-access
    IM_OBJSTORE_SECRET_KEY=android-e2e-not-a-secret
    # 申请上传的频率（默认 60 次/分钟）调高：TC-FILE-04-10 的对方要连发 200 张图。可以用同名环境变量改。
    "IM_UPLOAD_RATE=${IM_UPLOAD_RATE:-1000}"
  )
fi

IM_ADDR="127.0.0.1:$port" \
IM_ADMIN_ADDR="127.0.0.1:$admin_port" \
IM_METRICS_ADDR="127.0.0.1:$metrics_port" \
IM_DB="$dir/im.db" \
IM_TEST_MODE=1 \
IM_DEMO_LOGIN=1 \
IM_V13_ADMIN_FEATURES=1 \
IM_PUSH_ENABLED=1 \
IM_MSG_ACTIONS_ENABLED="${IM_MSG_ACTIONS_ENABLED:-1}" \
IM_REGISTER_MODE=invite \
IM_PRIVACY_NOTICE_VERSION=v1 \
IM_PRIVACY_NOTICE_URL="http://10.0.2.2:$port/readyz" \
IM_QUARANTINE_KEY="$(rnd)" \
IM_JWT_SECRET="$(rnd)" \
IM_ADMIN_KEY="$admin_key" \
IM_ADMIN_CALLERS="$callers" \
nohup env ${file_env[@]+"${file_env[@]}"} "$dir/im-server" > "$dir/server.log" 2>&1 &
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
TT_METRICS_PORT=$metrics_port
TT_S3_PORT=$s3_port
TT_FILES=$files
ENV
    echo "test server ready on 127.0.0.1:$port (admin $admin_port)"
    exit 0
  fi
  sleep 0.1
done
echo "服务端没有起来，看 $dir/server.log" >&2
tail -20 "$dir/server.log" >&2
exit 1
