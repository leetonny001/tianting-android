#!/usr/bin/env bash
# AC-UI-90-12：编译 logcheck 构建（release 的 R8、不可调试，只是用调试签名、允许连本机测试服务端），
# 在模拟器上真实收发，然后在设备日志里搜特征串和 token。用完卸载（只动 com.tianting.app.logcheck）。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"
[[ -n "${ANDROID_SERIAL:-}" ]] && adb="$adb -s $ANDROID_SERIAL"
trap '$adb uninstall com.tianting.app.logcheck >/dev/null 2>&1 || true; scripts/test-server.sh stop' EXIT
scripts/test-server.sh start
./gradlew -q :app:assembleLogcheck
$adb install -r app/build/outputs/apk/logcheck/app-logcheck.apk >/dev/null
set -a
# shellcheck disable=SC1091
source build/test-server/env
ADB="$adb"
set +a
python3 -I tools/release_logcheck/check.py
