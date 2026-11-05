package com.tianting.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tianting.app.core.AppModel
import com.tianting.app.core.defaultDeps
import com.tianting.app.ui.httpsOnlyClient

class TiantingApplication : Application(), ImageLoaderFactory {
    /** 测试可以换掉（换之前先 logout）。 */
    lateinit var model: AppModel

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this, defaultDeps(this))
    }

    /** 头像只走 https（AC-UI-21-5）：Coil 用的 OkHttp 拒绝其他 scheme。 */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).okHttpClient(httpsOnlyClient()).build()
}
