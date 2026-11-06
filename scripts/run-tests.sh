#!/usr/bin/env bash
# 在已连接的模拟器上跑 UI 测试（只装卸 com.tianting.* 自己的包）。
#   scripts/run-tests.sh ui                  假服务端的 Compose UI Test（不需要服务端）
#   scripts/run-tests.sh e2e                 对真实 im-server 的端到端（自动启停 scripts/test-server.sh）
#   scripts/run-tests.sh kill                真实杀进程（AC-ACC-03-13、03-20、05-7、04-6）：每个场景两次 am instrument，第一次在暂停点退出进程
#   scripts/run-tests.sh shots               截图巡检，PNG 拉到 shots/
#   scripts/run-tests.sh class <类或类#方法>  只跑指定用例
# 设备用 ANDROID_SERIAL 选（默认 adb 唯一的设备，有多台时报错）。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"
[[ -n "${ANDROID_SERIAL:-}" ]] && adb="$adb -s $ANDROID_SERIAL"
pkg=com.tianting.app
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
UI_CLASSES="$pkg.NavLoginTest,$pkg.ConvListTest,$pkg.ChatTest,$pkg.SocialTest,$pkg.M4Test,$pkg.ModerationUiTest,$pkg.NoticesUiTest,$pkg.PolicyTest,$pkg.LayoutScaleTest,$pkg.AccountUiTest,$pkg.SessionUiTest"

# 先编译再用 adb 装：只碰 ANDROID_SERIAL 指定的设备（gradle 的 install 任务会枚举所有已连接设备）。多台设备时没有设置 ANDROID_SERIAL 就报错。
install() {
  ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
  $adb install -r -t app/build/outputs/apk/debug/app-debug.apk > /dev/null
  $adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > /dev/null
}

instrument() { # 其余参数原样传给 am instrument；有失败或进程崩溃就返回非 0。原始输出在 build/instrument.txt
  mkdir -p build
  $adb shell am instrument -w -r "$@" "$runner" > build/instrument.txt 2>&1 || true
  awk '
    /^INSTRUMENTATION_STATUS: test=/ { name=$2; sub(/^test=/,"",name) }
    /^INSTRUMENTATION_STATUS: stack=/ { line=$0; sub(/^INSTRUMENTATION_STATUS: stack=/,"",line); stack=line; n=0; instack=1; next }
    instack && /^INSTRUMENTATION_STATUS/ { instack=0 }
    instack && /com\.tianting\.app\./ && n<3 { stack=stack "\n    " $0; n++ }
    /^INSTRUMENTATION_STATUS_CODE: -[24]/ { print "FAIL " name "\n  " stack }
    /^OK \(|^Tests run:|^INSTRUMENTATION_RESULT: (shortMsg|longMsg)|^Process crashed/ { print }
  ' build/instrument.txt | cut -c1-400
  if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed" build/instrument.txt || ! grep -qE "^OK \(" build/instrument.txt; then
    return 1
  fi
}

case "${1:-}" in
  ui)
    install
    instrument -e class "$UI_CLASSES"
    ;;
  class)
    install
    instrument -e class "$2"
    ;;
  e2e)
    trap 'scripts/test-server.sh stop' EXIT
    scripts/test-server.sh start
    # shellcheck disable=SC1091
    source build/test-server/env
    install
    instrument -e class "${2:-$pkg.ServerE2eTest}" -e ttAdminKey "$TT_ADMIN_KEY" -e ttCallerId "$TT_CALLER_ID" -e ttCallerSecret "$TT_CALLER_SECRET" -e ttAdminUrl "http://10.0.2.2:$TT_ADMIN_PORT" -e ttMetricsUrl "http://10.0.2.2:$TT_METRICS_PORT/metrics"
    ;;
  kill)
    trap 'scripts/test-server.sh stop' EXIT
    scripts/test-server.sh start
    # shellcheck disable=SC1091
    source build/test-server/env
    install
    e2e_args=(-e ttAdminKey "$TT_ADMIN_KEY" -e ttCallerId "$TT_CALLER_ID" -e ttCallerSecret "$TT_CALLER_SECRET" -e ttAdminUrl "http://10.0.2.2:$TT_ADMIN_PORT" -e ttMetricsUrl "http://10.0.2.2:$TT_METRICS_PORT/metrics")
    fail=0
    for c in refreshSaved refreshResponseLost savePage logoutInFlight; do
      echo "== $c"
      # 第一阶段以进程退出结束，输出里的崩溃是预期的；它有没有走到暂停点由第二阶段的标记文件判断。
      instrument -e class "$pkg.AccountKillE2e#${c}_phase1" "${e2e_args[@]}" > /dev/null || true
      instrument -e class "$pkg.AccountKillE2e#${c}_phase2" "${e2e_args[@]}" || fail=1
    done
    exit $fail
    ;;
  shots)
    install
    instrument -e class "$pkg.ScreenshotTour"
    rm -rf shots && mkdir -p shots
    $adb pull "/sdcard/Android/data/$pkg/files/shots/." shots/
    ;;
  *)
    echo "usage: $0 ui|e2e|kill|shots|class <name>" >&2
    exit 2
    ;;
esac
