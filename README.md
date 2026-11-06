# tianting-android

天听 Android 客户端（Kotlin + Compose）。**天听**是自研的微信式 IM 产品。

天听的 Android 客户端，UI 和基本功能参考微信。

| | |
|---|---|
| 状态 | 首版：M3 + M4 + 第二批（禁言条、已删除占位、封禁弹窗、系统通知）。宽屏（平板）后续 |
| 技术 | Kotlin + Jetpack Compose，接入 tianting-sdk-kotlin（composite build 源码依赖） |
| 服务端、协议、需求文档 | [tianting-server](https://github.com/leetonny001/tianting-server)（UI 规范：`docs/prd/PRD-ui`） |
| 工作队列 / 日报 | tianting-server 的 `docs/process/QUEUE.md`、`docs/reports/daily/` |

## 结构

| 路径 | 内容 |
|---|---|
| `app/src/main/kotlin/.../core/` | 不含界面的部分：`AppModel`（App 级状态、登录生命周期）、`ImSession`（把 SDK 的 Flow 变成 Compose state）、`Navigator` / `Routes`（路径与 tianting-web 一一对应、deep link 校验）、`Errors`（文案表）、`SessionStore`（Keystore 加密的会话和"待退出"，实现 SDK 的接口）、`UiPrefs`（只有 `ui.` 键）、`Notices`（系统通知的列表、横幅、未查看）、`LoginApi` |
| `.../ui/AppRoot.kt` | 根组合：主题、路由表、被踢弹窗、轻提示 |
| `.../ui/Components.kt` | 顶栏、头像、按钮、输入框、弹窗等；`data-*` 对应的 semantics key |
| `.../ui/pages/` | 全部页面。`testTag` 与 web 的 `data-testid` 同名 |
| `.../ui/theme/` | `Tokens.gen.kt`（由 `design/tokens.json` 生成）、`Theme.kt`、`TtIcons.kt`（Lucide）。颜色字面量只允许在这个目录 |
| `app/src/androidTest/` | Compose UI Test（假服务端）、`ServerE2eTest`（真实 im-server）、`ScreenshotTour` |
| `app/src/test/` | JVM 单元测试：路由校验、文案表、时间、对比度 / 色相 |
| `design/tokens.json` | tianting-web `src/theme/tokens.json` 的副本，`scripts/sync-tokens.sh` 同步 |
| `scripts/`、`tools/` | 测试服务端、跑测试、release Logcat 检查、AC 统计（Python 脚本只用标准库，`python3 -I` 运行） |

## 开发

需要 JDK 21、Android SDK，同级目录检出 `tianting-sdk-kotlin`（或用 `TIANTING_SDK_DIR` 指定）。SDK 的 settings 依赖环境变量判断是否包含 `:sdk-android`，所以必须设置 `ANDROID_HOME`。

```bash
export ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:assembleDebug
./gradlew :app:checkTianting :app:lintDebug :app:testDebugUnitTest   # 代码规则、Lint、单元测试（不需要设备）
scripts/run-tests.sh ui                # 模拟器：Compose UI Test（假服务端）
scripts/run-tests.sh e2e               # 模拟器 + 真实 im-server（自动编译、启动、关闭，需要 Go 和服务端源码）
scripts/run-tests.sh kill              # 模拟器 + 真实 im-server：真实杀进程的场景，每个场景两次 am instrument
scripts/run-tests.sh shots             # 截图巡检（浅色、深色），PNG 在 shots/
scripts/release-logcat-check.sh        # release 的 R8 构建真实收发后，设备日志搜不到特征串和 token
scripts/push-logcat-check.sh           # 同上，真实收一条系统通知
python3 -I tools/ac_coverage/coverage.py ../tianting-server/docs/prd/PRD-ui-v1.0.md  # AC 统计
```

连本地服务端：debug 构建默认连 `http://10.0.2.2:18280`（`-Ptianting.devServer=…` 可改），`scripts/test-server.sh start` 在这个端口起测试服务端（密钥每次随机，不进仓库）。

`release` 构建：`-Ptianting.serverUrl=https://…` 指定服务端，`-Ptianting.demoLogin=true` 打开演示登录的构建开关（默认关闭；和服务端 `GET /v1/config` 的 `demoLogin` 同时为真才显示演示表单，否则只有账号登录）；明文流量全部禁用。

深链接：`tianting://chat/{convId}`（convId 百分号编码）等，只接受路由表里的路径，其余进 `/chats`；未登录时先登录再进入。

## 系统通知

`scripts/test-server.sh` 打开 `IM_PUSH_ENABLED`。推送游标在 no-backup 目录的文件里（`AndroidPushCursorStore`），退出、被踢、库重建都不清；`ui.noticeSeen.{userId}` 只有一个数字，退出登录时删除，被踢时不删。列表只在内存，重启后为空，页面底部有说明（PRD-live F-PUSH-12 规则 6）。

## 已知缺口

群操作（改群名、公告、邀请、设管理员等）的提示消息，服务端不推给操作者自己的连接，SDK 也没有在 ack 后补拉，所以操作者本机要等下一条消息触发缺口补拉（或重连）才看到（tianting-web 和 sdk-ts 相同）。

## 代码规则（`:app:checkTianting`，跑在每次构建之前）

主题目录外不出现颜色字面量（AC-UI-22-3）；`Tokens.gen.kt` 和 `design/tokens.json` 一致；只有 `UiPrefs` 能用 SharedPreferences（AC-UI-90-2）；只能用 `AppLog` 写日志，release 不输出（AC-UI-90-12）；无禁用品牌词（AC-UI-90-15）。

## CI

GitHub Actions 跑代码规则、Lint、单元测试和 assemble。需要读取私有的 tianting-sdk-kotlin，配置只读部署密钥（由仓库所有者运行）：

```bash
scripts/setup-ci-deploy-key.sh
```

没配置时 `android` job 跳过。模拟器上的 UI Test 和端到端 CI 不跑，只在本机执行。

## 天听的项目

| 仓库 | 内容 |
|---|---|
| [tianting-server](https://github.com/leetonny001/tianting-server) | Go 服务端、协议、需求 / 架构 / 测试文档 |
| [tianting-sdk-ts](https://github.com/leetonny001/tianting-sdk-ts) | SDK（TypeScript）：H5、PC |
| [tianting-sdk-kotlin](https://github.com/leetonny001/tianting-sdk-kotlin) | SDK（Kotlin）：Android |
| [tianting-sdk-swift](https://github.com/leetonny001/tianting-sdk-swift) | SDK（Swift）：iOS |
| [tianting-web](https://github.com/leetonny001/tianting-web) | H5 客户端 |
| [tianting-desktop](https://github.com/leetonny001/tianting-desktop) | PC 客户端 |
| [tianting-android](https://github.com/leetonny001/tianting-android) | Android 客户端 |
| [tianting-ios](https://github.com/leetonny001/tianting-ios) | iOS 客户端 |
| [tianting-admin](https://github.com/leetonny001/tianting-admin) | 管理后台 H5 |
| [tianting-ops](https://github.com/leetonny001/tianting-ops) | 运维后台 H5 |
