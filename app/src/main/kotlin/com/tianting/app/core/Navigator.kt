package com.tianting.app.core

import androidx.compose.runtime.mutableStateListOf
import com.tianting.sdk.ApplySource
import com.tianting.sdk.Profile

/**
 * 页面之间传递的非敏感上下文（PRD-ui 3.2 节"路由状态"）：申请来源、手里已有的资料、成员页的管理模式。不进路径。
 */
data class RouteState(
    val source: ApplySource? = null,
    val sourceRef: String? = null,
    val profile: Profile? = null,
    val mode: MemberMode = MemberMode.VIEW,
)

enum class MemberMode { VIEW, KICK, ADMIN, TRANSFER }

/**
 * 路由栈：路径和 tianting-web 的路由一一对应（PRD-ui 3.2 节），当前页就是栈顶。
 * 一级页（/chats、/contacts、/me）互相切换不入栈；系统返回键和顶栏返回都走 back()。
 */
class Navigator {
    class Entry(val id: Long, val path: String, val state: RouteState)

    private var nextId = 1L
    val stack = mutableStateListOf(entry("/chats", RouteState()))

    private fun entry(path: String, state: RouteState) = Entry(nextId++, path, state)

    val current: Entry get() = stack.last()

    fun go(path: String, state: RouteState = RouteState(), replace: Boolean = false) {
        if (replace && stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(entry(path, state))
    }

    /** 切一级页：只留这一页。 */
    fun setRoot(path: String) {
        stack.clear()
        stack.add(entry(path, RouteState()))
    }

    /** 登录后 / 打开 deep link：/chats 打底，返回键能回到会话列表。 */
    fun resetTo(route: String?) {
        stack.clear()
        stack.add(entry("/chats", RouteState()))
        if (route != null && route != "/chats") stack.add(entry(route, RouteState()))
    }

    /** 退回上一页；没有上一页时：其他一级页回 /chats，/chats 返回 false（交给系统退出）。 */
    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (current.path != "/chats") {
            setRoot("/chats")
            return true
        }
        return false
    }
}
