#!/usr/bin/env bash
# 系统通知的 release Logcat 检查：编译 logcheck 构建，真实收一条通知，在设备日志里搜特征串和 token。用完卸载（只动 com.tianting.app.logcheck）。
# 设备用 ANDROID_SERIAL 选。
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
python3 -I tools/push_logcheck/check.py
