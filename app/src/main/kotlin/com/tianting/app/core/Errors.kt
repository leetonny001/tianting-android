// 错误文案（PRD-core 第 5 节、PRD-ui F-UI-19）：先查"命令 + 码"，没有单独列出的用该码的默认文案；从不展示服务端的 msg。
// errors.json 新增 ws 通道的码时，必须在这里补上文案（ErrorsTest 遍历 SDK 的错误码表检查）。
package com.tianting.app.core

import com.tianting.sdk.FatalReason
import com.tianting.sdk.IMError

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

/** 禁言条（4031、muted，F-UI-41）：永久（null）没有解除时间。 */
fun mutedText(until: Long?): String = if (until != null) "你已被禁言，解除时间：${formatMonthDayTime(until)}" else defaultMessage(4031)

private val KICK_TEXT = mapOf(
    "tokenInvalid" to "登录已失效，请重新登录",
    "tokenExpired" to "登录已过期，请重新登录",
    "banned" to "账号已被下线",
    "tooManyConnections" to "登录设备过多，当前设备已下线",
)

/** 被踢弹窗文案（F-UI-18）：先按 kicked.reason，不认识时按关闭码。 */
fun fatalText(r: FatalReason): String {
    if (r is FatalReason.Kicked) {
        if (r.reason == "suspended") return bannedText(r.until)
        KICK_TEXT[r.reason]?.let { return it }
    }
    val code = when (r) {
        is FatalReason.Kicked -> r.code
        is FatalReason.Closed -> r.code
    }
    if (code == 4429) return KICK_TEXT.getValue("tooManyConnections")
    return defaultMessage(code)
}
