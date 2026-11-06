package com.tianting.app.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianting.app.BuildConfig
import com.tianting.app.ui.theme.ThemePref
import com.tianting.sdk.AccountClient
import com.tianting.sdk.AccountClientOptions
import com.tianting.sdk.AccountConfig
import com.tianting.sdk.AccountError
import com.tianting.sdk.AccountEvent
import com.tianting.sdk.AccountSession
import com.tianting.sdk.AuthResult
import com.tianting.sdk.HttpTransport
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.PinyinCollator
import com.tianting.sdk.Platform
import com.tianting.sdk.SessionEndAction
import com.tianting.sdk.SessionEndedError
import com.tianting.sdk.SessionMode
import com.tianting.sdk.SessionStore
import com.tianting.sdk.android.AndroidLocalDatabase
import com.tianting.sdk.android.AndroidPinyin
import com.tianting.sdk.android.AndroidPushCursorStore
import com.tianting.sdk.android.bindNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.URI

/** App 的外部依赖；测试替换成假传输层、假登录、假账号接口。 */
class AppDeps(
    val newClient: (isVisible: () -> Boolean) -> IMClient,
    val login: LoginApi,
    /** 和 account 用同一个存储：演示登录的 Session 由 App 写，账号的由 SDK 写。 */
    val sessions: SessionStore,
    val isOnline: () -> Boolean,
    val bindNetwork: (IMClient) -> Closeable?,
    val account: AccountClient,
    /** 启动时就绑定，登录页也要有："待退出"要在联网时补发。 */
    val bindAccountNetwork: (AccountClient) -> Closeable? = { null },
    val collator: () -> PinyinCollator? = { AndroidPinyin.collator() },
    /** 写剪贴板；失败（被系统拒绝）返回 false，界面提示手动复制（F-UI-09）。 */
    val copyText: (String) -> Boolean = { false },
    /** 恢复码：同样写系统剪贴板，但标记为敏感内容，系统的剪贴板预览不显示。 */
    val copySecret: (String) -> Boolean = copyText,
    /** 构建开关（build.gradle.kts）。和 GET /v1/config 的 demoLogin 同时为真才显示演示表单（F-ACC-07 规则 4）。 */
    val demoBuild: Boolean = BuildConfig.DEMO_LOGIN,
)

/**
 * http、sessions 只有对真实服务端的端到端测试才换（丢响应、在落盘后杀进程）。
 */
fun defaultDeps(context: Context, http: HttpTransport? = null, sessions: SessionStore = KeystoreSessionStore(context.applicationContext)): AppDeps {
    val app = context.applicationContext
    val base = BuildConfig.SERVER_URL.trimEnd('/')
    val ws = URI(base).let { "${if (it.scheme == "https") "wss" else "ws"}://${it.authority}/ws" }
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun clip(text: String, sensitive: Boolean): Boolean = try {
        val cb = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("天听", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cb.setPrimaryClip(clip)
        true
    } catch (_: RuntimeException) {
        false
    }
    return AppDeps(
        newClient = { visible ->
            IMClient(
                IMClientOptions(url = ws, platform = Platform.ANDROID, database = AndroidLocalDatabase.open(app), logger = AppLog, isVisible = visible, pushCursorStore = AndroidPushCursorStore(app)),
            )
        },
        login = DemoLoginApi(base),
        sessions = sessions,
        isOnline = { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true },
        bindNetwork = { it.bindNetwork(app) },
        account = AccountClient(
            AccountClientOptions(
                baseUrl = base,
                platform = Platform.ANDROID,
                deviceName = Build.MODEL?.take(DEVICE_NAME_MAX),
                store = sessions,
                pendingLogoutStore = KeystorePendingLogoutStore(app),
                http = http,
                logger = AppLog,
            ),
        ),
        bindAccountNetwork = { it.bindNetwork(app) },
        copyText = { clip(it, sensitive = false) },
        copySecret = { clip(it, sensitive = true) },
    )
}

private const val DEVICE_NAME_MAX = 60 // 服务端上限 64 个码点，取 60 个 UTF-16 单元不会超
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE，API 33 才有常量，老系统忽略这个键

/** 轻提示（F-UI-19 规则 1）：显示 2 秒；同时只显示 1 条，新的替换旧的。计时在 ToastHost。 */
class ToastState {
    var text by mutableStateOf<String?>(null)
    var serial by mutableIntStateOf(0)
        private set

    fun show(value: String) {
        text = value
        serial++
    }
}

/** GET /v1/config 的结果。失败时登录页只显示重试，不猜测模式（F-ACC-07 异常）。 */
sealed interface ConfigState {
    data object Loading : ConfigState
    data object Failed : ConfigState
    data class Loaded(val config: AccountConfig) : ConfigState
}

/** 保存恢复码页（F-ACC-05 规则 1）。显示时盖住其他页面，确认之前进不了会话列表。 */
sealed interface RecoveryPrompt {
    /** 响应带来的恢复码。toString 不带码：不能进日志。 */
    class Show(val code: String, val gen: Long) : RecoveryPrompt {
        override fun toString(): String = "Show(gen=$gen)"
    }

    /** 服务端说最新一代没确认，手里没有码（响应丢失或在保存页被杀）：输入密码重新生成。 */
    data object NeedPassword : RecoveryPrompt
}

/** 哪种操作换来的登录态：决定隐私说明提示（F-ACC-10 规则 2）。 */
enum class AuthKind { LOGIN, REGISTER, RECOVER }

/** App 级状态：活过 Activity 重建，所以旋转屏幕不会断连（F-UI-02 规则 2）。 */
class AppModel(context: Context, val deps: AppDeps) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = UiPrefs(appContext)
    private val collator by lazy { deps.collator() }
    private var teardown: Job? = null
    private var closingClient: Deferred<Unit>? = null // 退出后还没关的旧客户端。只运行一次，新会话开始之前要关（它清的本地库和新会话共用）
    private var accountNetwork: Closeable? = null
    private var lastAckAt = 0L

    val nav = Navigator()
    val toast = ToastState()
    val account: AccountClient get() = deps.account

    var im by mutableStateOf<ImSession?>(null)
        private set
    var themePref by mutableStateOf(ThemePref.of(prefs.get(UiPrefs.THEME)))
        private set
    var config by mutableStateOf<ConfigState>(ConfigState.Loading)
        private set
    var recovery by mutableStateOf<RecoveryPrompt?>(null)
        private set

    /** 还没有连接时会话结束（登录页、保存页）要显示的文字。已连接时走 ImSession.fatal。 */
    var endedText by mutableStateOf<String?>(null)
        private set

    /** 隐私说明版本更新，这次登录要提示（F-ACC-10 规则 2）。 */
    var privacyUpdate by mutableStateOf<String?>(null)
        private set
    private var privacyUpdateUser: String? = null

    /** 登录流程里当前页有顶栏（注册、找回、隐私说明）。只影响根背景。 */
    var authTopBar by mutableStateOf(false)

    /** 未登录时打开的 deep link：登录后进入（AC-UI-01-3）。 */
    var pendingRoute: String? = null

    @Volatile
    var foreground = true
        private set

    init {
        prefs.remove(UiPrefs.LEGACY_PRIVACY_VERSION)
        accountNetwork = deps.bindAccountNetwork(account)
        // 先开始收集再 start：events 没有回放，启动时刷新可能立刻结束会话。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { account.events.collect { onAccountEvent(it) } }
        // 和之前一样同步读存储：已登录时不闪登录页。start 不等网络，刷新在后台。
        runBlocking { account.start() }
        begin()
        if (im == null) finishInterruptedWipe()
        refreshConfig()
    }

    /** 上一次成功的配置。已登录时拉配置失败用它（规则 4）；登录页不用，见 ConfigState。 */
    val cachedConfig: AccountConfig?
        get() = (config as? ConfigState.Loaded)?.config ?: prefs.get(UiPrefs.CONFIG)?.let { raw ->
            try {
                CONFIG_JSON.decodeFromString(AccountConfig.serializer(), raw)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** 拉配置。已经拿到过时界面继续用旧值，失败才换成重试。 */
    fun refreshConfig() {
        scope.launch {
            try {
                val c = account.getConfig()
                prefs.set(UiPrefs.CONFIG, CONFIG_JSON.encodeToString(AccountConfig.serializer(), c))
                config = ConfigState.Loaded(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.debug("config failed: ${e.javaClass.simpleName}")
                config = ConfigState.Failed
            }
        }
    }

    /** 点"重试"：回到加载中。 */
    fun retryConfig() {
        config = ConfigState.Loading
        refreshConfig()
    }

    /** 连接前确认有会话：没登录过就不 connect，否则 SDK 发 Fatal 走清本地数据的路径（K7）。 */
    private fun begin() {
        val s = account.session.value ?: return
        val isAccount = s.mode == SessionMode.ACCOUNT
        val client = deps.newClient { foreground }
        val session = ImSession(client, s.userId, isAccount, collator, deps.isOnline, toast::show, prefs, ::onRecoveryCodeUnacked)
        session.start(deps.bindNetwork) { c -> if (isAccount) c.connect(account.tokenProvider) else c.connect(s.accessToken) }
        im = session
    }

    private fun onAccountEvent(e: AccountEvent) {
        when (e) {
            is AccountEvent.Ended -> {
                // 退出是用户自己的操作，界面已经回到登录页。已连接时 IMClient 发 Fatal，弹窗走 ImSession.fatal。
                if (e.end.action != SessionEndAction.LOGOUT && im == null) endedText = sessionEndText(e.end)
            }
            AccountEvent.RecoveryCodeUnacked -> onRecoveryCodeUnacked()
            else -> Unit
        }
    }

    /** 强制进保存页。已经在保存页、或者刚确认完（确认之前发出的响应晚到）都不算。 */
    private fun onRecoveryCodeUnacked() {
        if (recovery != null || account.session.value?.mode != SessionMode.ACCOUNT) return
        if (SystemClock.elapsedRealtime() - lastAckAt < ACK_SETTLE_MS) return
        recovery = RecoveryPrompt.NeedPassword
    }

    /** 演示登录成功。 */
    fun onLoggedIn(s: Session) {
        val route = pendingRoute
        pendingRoute = null
        scope.launch {
            closingClient?.await()
            val saved = AccountSession(mode = SessionMode.DEMO, userId = s.userId, accessToken = s.token)
            account.restore(saved) // 先于落盘：进行中的 logout 由此知道有了新会话，不再清存储
            deps.sessions.save(saved)
            begin()
            nav.resetTo(route)
        }
    }

    /**
     * 账号登录、注册、找回。放在 App 的 scope：页面没了也要把恢复码接住。
     * 不等上一次退出的响应（断网时最长 30 秒）：SDK 的 logout 只结束它开始时的会话（问题 A1）。旧客户端先关掉，它还绑着 token，又和新会话共用本地库。
     */
    fun authenticate(kind: AuthKind, onFailed: (Throwable) -> Unit, op: suspend (AccountClient) -> AuthResult) {
        scope.launch {
            try {
                closingClient?.await()
                val r = op(account)
                if (kind == AuthKind.REGISTER) markPrivacySeen(r.userId)
                val code = r.recoveryCode
                val gen = r.recoveryCodeGen
                if (code != null && gen != null) {
                    recovery = RecoveryPrompt.Show(code, gen)
                } else {
                    enterApp()
                }
                if (kind != AuthKind.REGISTER) checkPrivacyVersion(r.userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed(e)
            }
        }
    }

    /** 保存页确认之后、或者不需要确认时进入会话列表。已经在连接的（改密后的保存页）不用再连。 */
    private fun enterApp() {
        if (im != null) return
        val route = pendingRoute
        pendingRoute = null
        begin()
        if (im != null) nav.resetTo(route)
    }

    /** "我已保存"。stale 时 SDK 结束会话，弹窗走 Ended 或 Fatal（AC-ACC-05-15），这里不用提示。 */
    fun ackRecovery(prompt: RecoveryPrompt.Show, onFailed: (Throwable) -> Unit) {
        scope.launch {
            try {
                account.ackRecoveryCode(prompt.gen)
                lastAckAt = SystemClock.elapsedRealtime()
                recovery = null
                enterApp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? AccountError)?.reason != "recoveryCodeStale" && e !is SessionEndedError) onFailed(e)
            }
        }
    }

    /** 保存页输入密码重新生成，"账号安全"也走这里：拿到码就进保存页。 */
    fun regenerateRecovery(password: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) {
        accountOp(onFailed) {
            val r = account.regenerateRecoveryCode(password)
            recovery = RecoveryPrompt.Show(r.recoveryCode, r.recoveryCodeGen)
            onDone()
        }
    }

    /** 已登录时的账号操作（改密、退出其他设备、改昵称、注销）。放在 App 的 scope：响应回来时页面可能已经不在了。 */
    fun accountOp(onFailed: (Throwable) -> Unit, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailed(e)
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) {
        accountOp(onFailed) {
            showRecoveryOf(account.changePassword(oldPassword, newPassword))
            onDone()
        }
    }

    /** 换 sid 的响应带恢复码时进保存页（F-ACC-04 规则 3）。 */
    private fun showRecoveryOf(r: AuthResult) {
        val code = r.recoveryCode
        val gen = r.recoveryCodeGen
        if (code != null && gen != null) recovery = RecoveryPrompt.Show(code, gen)
    }

    fun logoutOthers(password: String, onDone: () -> Unit, onFailed: (Throwable) -> Unit) {
        accountOp(onFailed) {
            showRecoveryOf(account.logoutOthers(password))
            onDone()
        }
    }

    /** 没有连接时会话结束的弹窗点了确定。 */
    fun dismissEnded() {
        endedText = null
        recovery = null
    }

    /**
     * 退出登录（F-AUTH-05、F-ACC-04 规则 1）：界面先回登录页，不等 logout 的响应（断网时最长等 30 秒，K2）。
     * SDK 按顺序做：先写待退出、清 token，发 logout，收到响应或超时后再由这里以 1000 关闭、清内存和本地库；响应回来之前又登录时，登录前就关。
     * ui.* 偏好保留，只有 ui.noticeSeen 这个账号的键删除。被踢、凭证失效时 keepNoticeSeen=true：重新登录后不重复提醒（PRD-live F-PUSH-12 规则 5）。
     * 推送游标不在本地库，都不清（4.9 节）。
     */
    fun logout(keepNoticeSeen: Boolean = false) {
        val session = im
        if (!keepNoticeSeen) session?.notices?.clearSeen()
        im = null
        recovery = null
        endedText = null
        privacyUpdate = null
        toast.text = null
        nav.resetTo(null)
        session?.stop()
        if (session != null) prefs.set(UiPrefs.WIPE_PENDING, "1")
        val closing = session?.let { closeLater(it.client) }
        if (closing != null) closingClient = closing
        val previous = teardown
        teardown = scope.launch {
            runCatching { account.logout() }
            closing?.await()
            previous?.join()
        }
    }

    /** 以 1000 关闭、清本地库。不会自己开始；await 多次也只运行一次。 */
    private fun closeLater(client: IMClient): Deferred<Unit> = scope.async(start = CoroutineStart.LAZY) {
        runCatching { client.disconnect() }
        runCatching { client.close() }
        prefs.remove(UiPrefs.WIPE_PENDING)
    }

    /** 等 logout 响应时（最长 30 秒）被杀：token 已经清了，本地库还没清。下次启动没有会话时补上，否则缓存要留到下次登录。 */
    private fun finishInterruptedWipe() {
        if (prefs.get(UiPrefs.WIPE_PENDING) == null) return
        val closing = closeLater(deps.newClient { foreground })
        closingClient = closing
        teardown = scope.launch { closing.await() }
    }

    /** 这次登录要不要提示隐私说明版本更新。服务端没有记录确认的接口（问题单），确认按账号记在本机，和 H5、iOS 相同。 */
    private fun checkPrivacyVersion(userId: String) {
        val version = (config as? ConfigState.Loaded)?.config?.privacyNotice?.version ?: return
        if (prefs.get(privacyAckKey(userId)) != version) {
            privacyUpdateUser = userId
            privacyUpdate = version
        }
    }

    private fun markPrivacySeen(userId: String) {
        (config as? ConfigState.Loaded)?.config?.privacyNotice?.version?.let { prefs.set(privacyAckKey(userId), it) }
    }

    /** 提示框点了确认。没点（返回键关掉）就是没确认，下次登录再提示；第一批不做强制。 */
    fun confirmPrivacyUpdate() {
        val user = privacyUpdateUser
        val version = privacyUpdate
        if (user != null && version != null) prefs.set(privacyAckKey(user), version)
        privacyUpdate = null
    }

    fun dismissPrivacyUpdate() {
        privacyUpdate = null
    }

    fun openDeepLink(route: String) {
        if (im == null) pendingRoute = route else nav.resetTo(route)
    }

    /** 复制并提示（AC-UI-09-3、AC-UI-09-8、AC-UI-15-1）。 */
    fun copy(text: String) {
        toast.show(if (deps.copyText(text)) "已复制" else "复制失败，请长按文字手动复制")
    }

    /** 恢复码的复制成功和失败（第 13.4 节）。 */
    fun copySecret(text: String) {
        toast.show(if (deps.copySecret(text)) "已复制" else "复制失败，请长按文字手动复制")
    }

    fun setTheme(pref: ThemePref) {
        themePref = pref
        prefs.set(UiPrefs.THEME, pref.wire)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        im?.client?.visibilityChanged()
        if (value) {
            account.foreground() // 登录页也要补发"待退出"；已连接时和 IMClient 转过来的那次合并
            if (config == ConfigState.Failed) refreshConfig()
        }
    }

    suspend fun awaitTeardown() {
        teardown?.join()
    }

    /** 测试换掉 App 模型时释放线程和网络监听。之后不能再用。正式运行时它和进程一样长。 */
    suspend fun close() {
        awaitTeardown()
        accountNetwork?.close()
        accountNetwork = null
        scope.cancel()
        account.close()
    }

    private companion object {
        const val ACK_SETTLE_MS = 5_000L
        val CONFIG_JSON = Json { ignoreUnknownKeys = true }
    }
}
