// 路由表（PRD-ui 3.2 节）和 deep link 校验（PRD-ui 3.6 节 AC-UI-01-3、AC-UI-01-7）。
// convId、userId 在路径里做百分号编码（单聊 ID 含 :）；路径里不放 token、附言、备注（NF-UI-13）。
package com.tianting.app.core

import java.net.URLDecoder
import java.net.URLEncoder

val ROUTE_PATTERNS = listOf(
    "/chats",
    "/chat/:convId",
    "/chat/:convId/info",
    "/chat/:convId/notice",
    "/chat/:convId/members",
    "/chat/:convId/invite",
    "/groups/new",
    "/contacts",
    "/contacts/requests",
    "/contacts/groups",
    "/contacts/add",
    "/contacts/apply/:userId",
    "/user/:userId",
    "/user/:userId/remark",
    "/me",
    "/me/notices",
    "/me/settings",
    "/me/settings/blocks",
    "/me/settings/privacy",
    "/me/settings/appearance",
)

private fun matches(pattern: String, path: String): Boolean {
    val p = pattern.split('/')
    val s = path.split('/')
    return p.size == s.size && p.indices.all { if (p[it].startsWith(":")) s[it].isNotEmpty() else p[it] == s[it] }
}

fun isKnownRoute(path: String): Boolean = ROUTE_PATTERNS.any { matches(it, path) }

/** 只接受路由表里的站内路径，其他一律回到 /chats。 */
fun safeRoute(raw: String?): String {
    if (raw.isNullOrEmpty() || raw[0] != '/' || raw.startsWith("//") || '\\' in raw) return "/chats"
    val path = raw.substringBefore('?').substringBefore('#')
    if (path.contains("%2F", ignoreCase = true) || path.contains("%5C", ignoreCase = true)) return "/chats"
    return if (isKnownRoute(path)) path else "/chats"
}

/**
 * deep link：tianting://chat/{convId} 对应 /chat/{convId}。只接受 tianting:// 下的已知路由（AC-UI-01-7）。
 * 入参是 Uri 的编码形式：scheme、authority、encodedPath，方便脱离 Android 测试。
 */
fun routeFromDeepLink(scheme: String?, encodedAuthority: String?, encodedPath: String?): String {
    if (scheme != "tianting" || encodedAuthority.isNullOrEmpty()) return "/chats"
    return safeRoute("/$encodedAuthority${encodedPath.orEmpty()}")
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

fun decodeParam(s: String): String = try {
    URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
} catch (_: IllegalArgumentException) {
    ""
}

fun chatPath(convId: String) = "/chat/${enc(convId)}"
fun infoPath(convId: String) = "${chatPath(convId)}/info"
fun noticePath(convId: String) = "${chatPath(convId)}/notice"
fun membersPath(convId: String) = "${chatPath(convId)}/members"
fun invitePath(convId: String) = "${chatPath(convId)}/invite"
fun userPath(userId: String) = "/user/${enc(userId)}"
fun remarkPath(userId: String) = "${userPath(userId)}/remark"
fun applyPath(userId: String) = "/contacts/apply/${enc(userId)}"

val USER_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
const val MAX_APPLY_MESSAGE = 50
const val MAX_REMARK = 32
const val MAX_NICKNAME = 32
const val MAX_GROUP_NAME = 50
const val MAX_NOTICE = 1000
const val MAX_GROUP_BATCH = 50
const val MIN_GROUP_PICK = 2

fun groupIdOf(convId: String) = convId.removePrefix("group:")
fun isGroupConv(convId: String) = convId.startsWith("group:")
