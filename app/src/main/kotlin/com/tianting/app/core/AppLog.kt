package com.tianting.app.core

import android.util.Log
import com.tianting.app.BuildConfig
import com.tianting.sdk.Logger

/**
 * 全部日志都走这里（构建时 checkTianting 检查）：release 什么都不写（AC-UI-90-12）。
 * 只传错误码、异常类型这种不含用户内容的字符串；不传消息、附言、备注和 token（NF-UI-16）。
 */
internal object AppLog : Logger {
    private const val TAG = "Tianting"
    private val enabled get() = BuildConfig.DEBUG

    override fun debug(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    override fun warn(message: String) {
        if (enabled) Log.w(TAG, message)
    }

    override fun error(message: String) {
        if (enabled) Log.e(TAG, message)
    }
}
