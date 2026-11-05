package com.tianting.app.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tianting.app.BuildConfig
import com.tianting.app.ui.theme.ThemePref
import com.tianting.sdk.IMClient
import com.tianting.sdk.IMClientOptions
import com.tianting.sdk.PinyinCollator
import com.tianting.sdk.Platform
import com.tianting.sdk.android.AndroidLocalDatabase
import com.tianting.sdk.android.AndroidPinyin
import com.tianting.sdk.android.bindNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.URI

/** App 的外部依赖；测试替换成假传输层、假登录。 */
class AppDeps(
    val newClient: (isVisible: () -> Boolean) -> IMClient,
    val login: LoginApi,
    val sessions: SessionStore,
    val isOnline: () -> Boolean,
    val bindNetwork: (IMClient) -> Closeable?,
    val collator: () -> PinyinCollator? = { AndroidPinyin.collator() },
    /** 写剪贴板；失败（被系统拒绝）返回 false，界面提示手动复制（F-UI-09）。 */
    val copyText: (String) -> Boolean = { false },
)

fun defaultDeps(context: Context): AppDeps {
    val app = context.applicationContext
    val base = BuildConfig.SERVER_URL.trimEnd('/')
    val ws = URI(base).let { "${if (it.scheme == "https") "wss" else "ws"}://${it.authority}/ws" }
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return AppDeps(
        newClient = { visible ->
            IMClient(
                IMClientOptions(url = ws, platform = Platform.ANDROID, database = AndroidLocalDatabase.open(app), logger = AppLog, isVisible = visible),
            )
        },
        login = DemoLoginApi(base),
        sessions = KeystoreSessionStore(app),
        isOnline = { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true },
        bindNetwork = { it.bindNetwork(app) },
        copyText = { text ->
            try {
                val cb = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("天听", text))
                true
            } catch (_: RuntimeException) {
                false
            }
        },
    )
}

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

/** App 级状态：活过 Activity 重建，所以旋转屏幕不会断连（F-UI-02 规则 2）。 */
class AppModel(context: Context, val deps: AppDeps) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = UiPrefs(appContext)
    private val collator by lazy { deps.collator() }
    private var teardown: Job? = null

    val nav = Navigator()
    val toast = ToastState()

    var im by mutableStateOf<ImSession?>(null)
        private set
    var themePref by mutableStateOf(ThemePref.of(prefs.get(UiPrefs.THEME)))
        private set

    /** 未登录时打开的 deep link：登录后进入（AC-UI-01-3）。 */
    var pendingRoute: String? = null

    @Volatile
    var foreground = true
        private set

    init {
        deps.sessions.load()?.let { begin(it) }
    }

    private fun begin(s: Session) {
        val client = deps.newClient { foreground }
        val session = ImSession(client, s.userId, collator, deps.isOnline, toast::show)
        session.start(s.token, deps.bindNetwork)
        im = session
    }

    fun onLoggedIn(s: Session) {
        deps.sessions.save(s)
        val route = pendingRoute
        pendingRoute = null
        scope.launch {
            teardown?.join()
            begin(s)
            nav.resetTo(route)
        }
    }

    /** 退出登录（F-AUTH-05）：以 1000 关闭、清内存和本地库、清 token；ui.* 偏好保留。 */
    fun logout() {
        val session = im ?: return
        deps.sessions.clear()
        im = null
        toast.text = null
        nav.resetTo(null)
        session.stop()
        teardown = scope.launch {
            runCatching { session.client.disconnect() }
            runCatching { session.client.close() }
        }
    }

    fun openDeepLink(route: String) {
        if (im == null) pendingRoute = route else nav.resetTo(route)
    }

    /** 复制并提示（AC-UI-09-3、AC-UI-09-8、AC-UI-15-1）。 */
    fun copy(text: String) {
        toast.show(if (deps.copyText(text)) "已复制" else "复制失败，请长按文字手动复制")
    }

    fun setTheme(pref: ThemePref) {
        themePref = pref
        prefs.set(UiPrefs.THEME, pref.wire)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        im?.client?.visibilityChanged()
    }

    suspend fun awaitTeardown() {
        teardown?.join()
    }
}
