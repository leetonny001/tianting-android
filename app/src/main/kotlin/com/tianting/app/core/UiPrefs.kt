package com.tianting.app.core

import android.content.Context

/**
 * 设备偏好（PRD-ui F-UI-22、AC-UI-90-2）：非加密 SharedPreferences 里只允许 ui. 前缀的键，退出登录也保留。
 * 账号数据一律不进来；唯一按账号的键是 ui.noticeSeen.{userId}（NF-UI-13）：值只有一个数字，退出登录时删除。
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
    }
}
