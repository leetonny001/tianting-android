// 错误文案（PRD-core 第 5 节、PRD-ui F-UI-19）：先查"命令 + 码"，没有单独列出的用该码的默认文案；从不展示服务端的 msg。
// errors.json 新增 ws 通道的码时，必须在这里补上文案（ErrorsTest 遍历 SDK 的错误码表检查）。
package com.tianting.app.core

import com.tianting.sdk.AccountError
import com.tianting.sdk.FatalReason
import com.tianting.sdk.IMError
import com.tianting.sdk.SessionDialog
import com.tianting.sdk.SessionEnd
import com.tianting.sdk.SessionEndAction
import com.tianting.sdk.SessionEndedError
import com.tianting.sdk.sessionDialogText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private val BY_CODE: Map<Int, String> = mapOf(
    4000 to "请求出错",
    4001 to "当前版本过低，请刷新页面",
    4002 to "请求出错",
    4003 to "请求出错",
    4004 to "账号格式不正确",
    4005 to "不能发送空白消息",
    4006 to "消息过长",
    4007 to "请求出错",
    4008 to "请求出错",
    4009 to "请求出错",
    4010 to "登录已失效，请重新登录",
    4011 to "登录已过期，请重新登录",
    4012 to "账号已被下线",
    4030 to "无权限",
    4031 to "你已被禁言",
    4032 to "请求出错",
    4033 to "你已不在该群",
    4035 to "对方开启了好友验证，你还不是他的好友",
    4036 to "消息已发出，但被对方拒收了",
    4040 to "内容不存在",
    4041 to "请求出错",
    4042 to "用户不存在",
    4043 to "群聊不存在或已解散",
    4045 to "该申请已过期",
    4046 to "对方已不在群里",
    4090 to "请求出错",
    4091 to "你们已经是朋友了",
    4130 to "请求出错",
    4131 to "请求出错",
    4132 to "你的好友数量已达上限",
    4133 to "对方好友数量已达上限",
    4134 to "黑名单已满",
    4135 to "加入的群聊已达上限",
    4136 to "管理员最多 10 人",
    4137 to "对方回复之前，发送的消息数量已达上限",
    4290 to "发送太频繁，请稍后再试",
    4292 to "今天的次数已用完，明天再试",
    5000 to "服务异常，请稍后重试",
    5030 to "服务异常，请稍后重试",
)

// SDK 的"命令 + 错误码"表（PRD-social 9.4 节）之外，只属于某个页面的文案。
private val BY_CMD: Map<String, String> = mapOf(
    "friendApply:4000" to "请先将对方移出黑名单",
    "friendRemark:4040" to "对方已不在你的通讯录中",
    "groupCreate:4130" to "群成员已达上限",
    "groupInvite:4130" to "群成员已达上限",
)

/** 按错误码的默认文案：不认识的 4xxx 提示"请求出错"，5xxx 提示"服务异常，请稍后重试"（C-09）。 */
fun defaultMessage(code: Int): String = BY_CODE[code] ?: if (code >= 5000) "服务异常，请稍后重试" else "请求出错"

/** "命令 + 错误码"文案。 */
fun messageFor(code: Int, cmd: String? = null, strangerMsgLimit: Int = 0, name: String = ""): String {
    if (cmd != null) {
        BY_CMD["$cmd:$code"]?.let { return it }
        com.tianting.sdk.messageFor(cmd, code, strangerMsgLimit, name)?.let { return it }
    }
    return defaultMessage(code)
}

/** 失败的命令的错误码；超时、断线按服务异常提示。 */
fun errCode(e: Throwable): Int = (e as? IMError)?.code ?: 5000

const val MESSAGE_DELETED = "该消息已被删除"

/** 封禁（4034、kicked.reason = suspended）：永久（null）或缺字段时没有解封时间。 */
fun bannedText(until: Long?): String = if (until != null) "账号已被封禁，解封时间：${formatDateTime(until)}" else "账号已被封禁"

/** 全群禁言的禁言条（F-UI-41 规则 3，AC-UI-41-6）。 */
const val GROUP_ALL_MUTED = "群主已开启全员禁言"

/** 禁言条的文案：全群禁言没有解除时间。 */
fun mutedText(mute: com.tianting.sdk.MuteInfo): String =
    if (mute.scope == com.tianting.sdk.MuteScope.GROUP_ALL) GROUP_ALL_MUTED else mutedText(mute.mutedUntil)

/** 禁言条（4031、muted，F-UI-41）：永久（null）没有解除时间。 */
fun mutedText(until: Long?): String = if (until != null) "你已被禁言，解除时间：${formatMonthDayTime(until)}" else defaultMessage(4031)

private val KICK_TEXT = mapOf(
    "tokenInvalid" to "登录已失效，请重新登录",
    "tokenExpired" to "登录已过期，请重新登录",
    "banned" to "账号已被下线",
    "tooManyConnections" to "登录设备过多，当前设备已下线",
)

/** 账号会话结束时的弹窗文字（F-ACC-03 规则 6、6a）。没有 dialog 时按动作和原因；申请注销是用户自己的操作。 */
fun sessionEndText(end: SessionEnd): String {
    if (end.action == SessionEndAction.BAN_NOTICE) return bannedText(null)
    sessionDialogText(end.dialog, end.recoverBefore)?.let { return it }
    if (end.reason == "accountDeleted" && !end.fromKick) return ACCOUNT_DELETION_REQUESTED
    return defaultMessage(4010)
}

/** 被踢弹窗文案（F-UI-18）：账号会话按 SessionEnd（Session 继承 Closed，要先判断）；踢线带日期时统一提示日期（规则 6a）；再按 kicked.reason，不认识时按关闭码。 */
fun fatalText(r: FatalReason): String {
    if (r is FatalReason.Session) return sessionEndText(r.end)
    if (r is FatalReason.Kicked) {
        if (r.reason == "suspended") return bannedText(r.until)
        if (r.recoverBefore != null) sessionDialogText(SessionDialog.RECOVER_BEFORE, r.recoverBefore)?.let { return it }
        KICK_TEXT[r.reason]?.let { return it }
    }
    val code = when (r) {
        is FatalReason.Kicked -> r.code
        is FatalReason.Closed -> r.code
    }
    if (code == 4429) return KICK_TEXT.getValue("tooManyConnections")
    return defaultMessage(code)
}

const val ACCOUNT_DELETION_REQUESTED = "已申请注销账号。7 天内可以用密码登录并撤销"
const val RECOVERY_LOST_WARNING = "恢复码丢失后账号无法找回"

/**
 * securityNotice 的提示。新设备登录的文案来自 PRD（AC-ACC-02-13），其他的是实现时加的，待确认（问题表 A12）。
 * SDK 已经滤掉不认识的 kind，这里返回 null 再防一层：内部的值不给用户看。
 */
fun securityNoticeText(kind: String): String? = when (kind) {
    "newDeviceLogin" -> "你的账号在新设备登录"
    "recoveryCodeRegenerated" -> "你的恢复码已重新生成"
    "refreshReplay" -> "检测到你的账号登录凭证异常"
    "refreshRateLimited" -> "你的账号登录凭证刷新过于频繁"
    "newIpPrefix" -> "你的账号正在新的网络使用"
    "passwordChanged" -> "你的账号密码已被修改"
    "passwordRecovered" -> "你的账号已通过恢复码重设密码"
    else -> null
}

const val PRIVACY_LOAD_FAILED = "暂时打不开，请稍后重试"
const val NETWORK_ERROR_TEXT = "网络异常，请稍后重试"

/** 账号页面（PRD-chat 第 13.4 节）。4018 在不同页面意思不同。 */
enum class AccountPage { LOGIN, REGISTER, RECOVER, PASSWORD_CONFIRM, NICKNAME }

/** 账号接口的错误文案。没有拿到服务端错误码的（网络、超时、代理的页面）都是"网络异常"。从不展示服务端的 msg。 */
fun accountErrorText(e: Throwable, page: AccountPage): String {
    if (e is SessionEndedError) return defaultMessage(4010)
    if (e !is AccountError) return NETWORK_ERROR_TEXT
    return when (e.code) {
        4004 -> "用户名格式不正确"
        4000 -> when {
            e.reason == "passwordWeak" -> "密码太简单"
            page == AccountPage.NICKNAME -> "昵称不符合要求"
            else -> defaultMessage(4000)
        }
        4018 -> when (page) {
            AccountPage.LOGIN -> "用户名或密码错误"
            AccountPage.RECOVER -> "用户名或恢复码错误"
            else -> "密码错误"
        }
        4030 -> when (e.reason) {
            "inviteInvalid" -> "邀请码无效"
            "registerClosed" -> "暂未开放注册"
            else -> defaultMessage(4030)
        }
        4034 -> bannedText(e.details?.long("bannedUntil"))
        4095 -> USERNAME_TAKEN
        4290 -> "操作太频繁，请稍后再试"
        4294 -> tooManyAttemptsText(e.details?.long("retryAfterSeconds"))
        5030 -> "服务暂不可用"
        else -> defaultMessage(e.code)
    }
}

const val USERNAME_TAKEN = "这个用户名不能使用"

/** 4294（AC-ACC-02-9）：按分钟向上取整，不足 1 分钟按 1 分钟。不自动重试。 */
fun tooManyAttemptsText(retryAfterSeconds: Long?): String {
    val minutes = maxOf(1L, ((retryAfterSeconds ?: 60L) + 59) / 60)
    return "尝试次数过多，请 $minutes 分钟后再试"
}

/** 注销冷静期内登录（F-ACC-08 规则 2）。deleteAt 取不到时不写日期。 */
fun deletionPendingText(deleteAt: Long?): String =
    if (deleteAt != null) "账号正在注销，${formatDate(deleteAt)}后不可恢复，是否撤销？" else "账号正在注销，是否撤销？"

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** account_events 的类型（server/internal/account）。不认识的显示"账号安全事件"，不把内部的值给用户看。 */
fun accountEventText(kind: String): String = when (kind) {
    "newDeviceLogin" -> "新设备登录"
    "passwordChanged" -> "修改密码"
    "passwordRecovered" -> "用恢复码找回密码"
    "loggedOutOthers" -> "退出其他设备"
    "recoveryCodeRegenerated" -> "重新生成恢复码"
    "deletionRequested" -> "申请注销账号"
    "deletionCanceled" -> "撤销注销账号"
    "refreshReplay" -> "登录凭证异常"
    "refreshRateLimited" -> "登录凭证刷新过于频繁"
    "newIpPrefix" -> "从新的网络登录"
    else -> "账号安全事件"
}

fun platformText(platform: String?): String = when (platform) {
    "android" -> "Android"
    "ios" -> "iOS"
    "web" -> "网页"
    "pc" -> "电脑"
    else -> ""
}
