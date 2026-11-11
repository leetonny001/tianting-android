# tianting-android

天听 Android 客户端（Kotlin + Compose）。**天听**是自研的独立 IM 产品。

天听的 Android 客户端，信息架构和基本功能见 tianting-server 的 PRD。

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
scripts/run-tests.sh e2e com.tianting.app.FilesE2eTest   # 图片和文件：真实 im-server + 假 S3（scripts/fake_s3.py）
IM_ACCESS_TTL=30s scripts/run-tests.sh e2e com.tianting.app.AccountE2eTest#tc_acc_03_12_real_online_kick_refreshes_quietly  # 在线时服务端踢 tokenExpired（默认服务端跳过）
scripts/run-tests.sh shots             # 截图巡检（浅色、深色），PNG 在 shots/
scripts/release-logcat-check.sh        # release 的 R8 构建真实收发后，设备日志搜不到特征串和 token
scripts/push-logcat-check.sh           # 同上，真实收一条系统通知
python3 -I tools/ac_coverage/coverage.py ../tianting-server/docs/prd/PRD-ui-v1.0.md  # AC 统计
```

连本地服务端：debug 构建默认连 `http://10.0.2.2:18280`（`-Ptianting.devServer=…` 可改），`scripts/test-server.sh start` 在这个端口起测试服务端（密钥每次随机，不进仓库）。

`release` 构建：`-Ptianting.serverUrl=https://…` 指定服务端，`-Ptianting.demoLogin=true` 打开演示登录的构建开关（默认关闭；和服务端 `GET /v1/config` 的 `demoLogin` 同时为真才显示演示表单，否则只有账号登录）；明文流量全部禁用。

深链接：`tianting://chat/{convId}`（convId 百分号编码）等，只接受路由表里的路径，其余进 `/chats`；未登录时先登录再进入。

## 图片和文件（C2）

"+"（`features.files` 打开时）：系统照片选择器（`PickMultipleVisualMedia(9)`，不要存储权限）、SAF。选中的内容先复制进 `cache/tt-pick`，图片轮到它时再处理（`core/ImageProcessing.kt`：按 EXIF 方向转正后重新编码、去掉 EXIF；长边 4096；缩略图 480、≤ 512 KiB；GIF 不重新编码），SDK 复制进 `files/tianting-attachments`（沙盒，发出后删除，失败保留，退出登录清空）。下载用 SDK 的 OkHttp 到 `cache/tianting-files`（键 fileId + variant），"打开"只对 pdf、txt、图片（`FileProvider`），"保存"用 SAF。"我"页点头像换头像（裁剪成 512×512）。

`scripts/test-server.sh` 同时启动假 S3（`127.0.0.1:18283`，只用标准库），服务端 `IM_FILES_ENABLED=1`；`run-tests.sh` 用 `adb reverse` 把模拟器的同一端口转到 Mac，debug 的网络安全配置只多放行 `127.0.0.1` 的明文（F-FILE-01 规则 10）。`TT_FILES=0` 关掉。

## 撤回、引用、@（C4）

服务端 `GET /v1/config` 的 `features.msgActions` 打开才有（关闭时消息菜单只有"复制"和失败消息的"重发""删除"）。菜单项来自 SDK 的 `messageActions`（撤回按 `ts` 和校正后的服务端时间），长按时现算；撤回、删除的提示行没有菜单。撤回文案用 SDK 的 `recallNotice` + `recallText`（聊天页和列表摘要同一张表）；正在全屏看、正在下载的那条被撤回时关闭全屏 / 中止下载并提示"该消息已被撤回"。引用条只记 msgId，按纯文本显示；点击引用 200 条以内翻页跳转。@ 的输入模型在 `core/Mentions.kt`（文本 + 区间，组字中不改模型，退格整段删除），成员选择是底部面板（`searchGroupMembers`）；草稿结构化（文字、@、引用，只在内存）。

`scripts/test-server.sh` 默认 `IM_MSG_ACTIONS_ENABLED=1`；`run-tests.sh` 的 e2e 还把服务端端口 `adb reverse` 到模拟器（`ttImLoopback`），对方的 IMClient 走这条路，App 开飞行模式时它不断。`TT_SERVER_STATE_DIR` 可以把服务端二进制、库、日志放到仓库外。

```bash
scripts/run-tests.sh e2e com.tianting.app.C4E2eTest      # 撤回、引用、@ 对真实 im-server
scripts/run-tests.sh kill C4KillE2e.recallWhileKilled    # App 被杀时被撤回（AC-RCL-03-4）
```

## 置顶、免打扰、删除会话（C5）

服务端 `GET /v1/config` 的 `features.convSettings` 打开才有会话菜单（长按，辅助操作里有同样三项）："置顶 / 取消置顶""免打扰 / 取消免打扰""删除"，文字按当前状态；关闭时没有菜单，已有的置顶、免打扰照常显示。SDK 乐观更新，失败回滚，页面只提示（4220"最多置顶 20 个会话"，4290"操作过于频繁"），不自动重试。置顶项背景 `color.surface.pinned`（和 tianting-web 同一个 token），免打扰会话未读显示小红点、摘要前"[N 条]"、右侧 `bell-off` 图标；摘要前缀"[有人@我]" > "[草稿]" > "[N 条]"。"消息"标签角标用 SDK 的 `totalUnread`（免打扰会话只算 @ 我）。删除先确认；本端删除成功或其他设备删除（`IMEvent.ConvDeleted`）时清这台设备的草稿（含打开着的输入框），失败消息、上传、缓存文件由 SDK 清。App 没有提示音和系统通知代码，`AppDeps.alert` 是留给 C3 的出口（现在不调用）。

`scripts/test-server.sh` 默认 `IM_CONV_SETTINGS_ENABLED=1`。

```bash
scripts/run-tests.sh e2e com.tianting.app.C5E2eTest        # 多端同步、删除后重新出现、清除缓存文件，对真实 im-server
scripts/run-tests.sh kill C5KillE2e.deletedWhileKilled      # D2 离线时被删除，冷启动后本地库和缓存搜不到（AC-CVS-03-10）
```

## 举报、内容拦截（C6）

服务端 `GET /v1/config` 的 `features.moderation` 打开才有"举报"（老服务端没有这个字段按关闭）：消息菜单（排在"撤回"后面，按 SDK 的 `canReport`，页面不自己判断）、别人撤回的提示行后面的"举报"（可读名称"举报这条消息"）、资料卡右上角菜单、群信息页（当前成员）。举报单是底部弹层（`ui/pages/ReportSheet.kt`）：理由单选 6 项（默认不选）、补充说明（可选，按码点最多 200，粘贴超长截断并提示）、提示"所选内容将提交给平台审核"；提交中按钮禁用、弹层不能关；成功轻提示"已提交，感谢你的反馈"，单聊消息和资料卡举报用户成功后（对方不在黑名单）询问是否拉黑；4040、4030、4292 提示后关闭，其他失败留着可以再点，不自动重试。4510 和开关无关：发送被拦截时消息标记失败、输入框清空、提示"消息包含不当内容，无法发送"，失败标记不能点，菜单只有"复制""删除"（SDK `canRetry`）；群名、群公告、建群、好友申请附言、注册、改昵称被拦截时提示"内容包含不当信息，请修改后重试"，表单内容保留。

`scripts/test-server.sh` 默认 `IM_MOD_ENABLED=1`（`IM_REPORT_KEY` 每次随机），调用方带 `moderation.words`，用例只用特征串。

```bash
scripts/run-tests.sh e2e com.tianting.app.C6E2eTest        # 举报、4510，对真实 im-server
```

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
