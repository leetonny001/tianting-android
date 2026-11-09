// 页面共用的小片段：标签栏、资料和关系的读取。
package com.tianting.app.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tianting.app.ui.Badge
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.LocalNav
import com.tianting.app.ui.T
import com.tianting.app.ui.badgeText
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.ConnState
import com.tianting.sdk.Relation
import com.tianting.sdk.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Tab(val path: String, val label: String, val tag: String)

private val TABS = listOf(Tab("/chats", "消息", "tab-chats"), Tab("/contacts", "通讯录", "tab-contacts"), Tab("/me", "我", "tab-me"))

/** 底部标签栏（F-UI-01、F-UI-23，Android 按 3.5 节用 NavigationBar）。点击已选中的"消息"让列表回到顶部（规则 4）。 */
@Composable
fun TabBar(current: String, onReselectChats: (() -> Unit)? = null) {
    val im = LocalIm.current
    val nav = LocalNav.current
    NavigationBar(containerColor = Tt.colors.surface, windowInsets = WindowInsets(0.dp), tonalElevation = 0.dp) {
        for (t in TABS) {
            val selected = t.path == current
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) {
                        if (t.path == "/chats") onReselectChats?.invoke()
                    } else {
                        nav.setRoot(t.path)
                    }
                },
                modifier = Modifier.tag(t.tag),
                icon = {
                    Box {
                        Icon(
                            when (t.path) {
                                "/chats" -> TtIcons.chats
                                "/contacts" -> TtIcons.contacts
                                else -> TtIcons.me
                            },
                            null,
                        )
                        if (t.path == "/chats" && im.totalUnread > 0) {
                            Badge(badgeText(im.totalUnread), "tab-badge", Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp))
                        }
                        if (t.path == "/contacts" && im.requests.unseen > 0) {
                            Badge(badgeText(im.requests.unseen.toLong()), "tab-contacts-badge", Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp))
                        }
                    }
                },
                label = { T(t.label, style = Tt.type.caption, color = if (selected) Tt.colors.brand else Tt.colors.textSecondary, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Tt.colors.brand,
                    unselectedIconColor = Tt.colors.textSecondary,
                    indicatorColor = Tt.colors.surfaceSelected,
                ),
            )
        }
    }
}

/** 用户资料（F-SOC-10）：先给缓存，连上之后用 getUsers 刷新。不可见、不存在都是 null。 */
@Composable
fun rememberProfile(userId: String): UserProfile? {
    val im = LocalIm.current
    val connected = im.state == ConnState.CONNECTED
    LaunchedEffect(userId, connected) {
        if (connected) im.fetchProfiles(listOf(userId))
    }
    return im.profiles[userId]
}

/** 显示名：好友备注 → 昵称 → userId。 */
@Composable
fun rememberDisplayName(userId: String): String {
    rememberProfile(userId)
    return LocalIm.current.nameOf(userId)
}

/** 本地关系（社交状态变化时重新计算）；SDK 的接口是 suspend，还没取到时为 null。 */
@Composable
fun rememberRelation(userId: String): Relation? {
    val im = LocalIm.current
    val r by produceState<Relation?>(null, userId, im.friends, im.requests, im.blocks) {
        value = try {
            onMain { im.client.relationOf(userId) }
        } catch (_: IllegalStateException) {
            value
        }
    }
    return r
}

/**
 * SDK 的挂起接口在 SDK 线程（tianting-sdk）上完成。从组合里的协程（produceState、LaunchedEffect、rememberCoroutineScope）
 * 调用时，用它回到主线程再写界面状态、导航（问题 A16、A17）：不依赖组合所在的调度器会不会切线程，否则重组和布局会跑在
 * SDK 线程上，和主线程同时进行。
 */
internal suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
