package com.tianting.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.tianting.app.core.routeFromDeepLink
import com.tianting.app.ui.AppRoot
import com.tianting.app.ui.theme.resolveDark

class MainActivity : ComponentActivity() {
    private val model get() = (application as TiantingApplication).model

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val dark = resolveDark(model.themePref)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            AppRoot(model)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        model.setForeground(true)
    }

    override fun onStop() {
        model.setForeground(false)
        super.onStop()
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        model.openDeepLink(routeFromDeepLink(uri.scheme, uri.encodedAuthority, uri.encodedPath))
    }
}
