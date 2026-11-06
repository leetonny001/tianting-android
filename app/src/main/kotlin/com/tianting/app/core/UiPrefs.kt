package com.tianting.app.core

import android.content.Context
import com.tianting.sdk.fnv1a32

/**
 * 设备偏好（PRD-ui F-UI-22、AC-UI-90-2）：非加密 SharedPreferences 里只允许 ui. 前缀的键，退出登录也保留。
 * 账号数据一律不进来。按账号的键只有两个：ui.noticeSeen.{userId}（NF-UI-13），值只有一个数字，退出登录时删除；
 * ui.privacyAck.{用户名的哈希}（F-ACC-10 规则 2，和 H5 同名），值只有版本号，退出登录保留，所以键里不放用户名。
 * 其他账号数据一律不进来。整个 App 只有这个文件能调 getSharedPreferences（构建时检查）。
 */
class UiPrefs(context: Context) : NoticePrefs {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(checked(key), null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(checked(key), value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(checked(key)).apply()
    }

    private fun checked(key: String): String {
        require(key.startsWith("ui.")) { "only ui.* keys are allowed" }
        return key
    }

    companion object {
        const val FILE = "tianting_ui"
        const val THEME = "ui.theme"

        /** GET /v1/config 上次成功的结果：已登录时拉取失败用它（F-ACC-07 规则 4）。不含账号数据和密钥。 */
        const val CONFIG = "ui.config"

        /** 之前一台设备一个值，不分账号（问题表 A2）。启动时删除，确认记录从 privacyAckKey 开始。 */
        const val LEGACY_PRIVACY_VERSION = "ui.privacyVersion"

        /** 退出登录时本地库还没清完（等 logout 响应）。只有一个标记，没有账号数据。 */
        const val WIPE_PENDING = "ui.wipePending"
    }
}

/** 这个账号在这台设备上确认过的隐私说明版本（F-ACC-10 规则 2）。 */
fun privacyAckKey(userId: String): String = "ui.privacyAck.${fnv1a32(userId).toString(16)}"
