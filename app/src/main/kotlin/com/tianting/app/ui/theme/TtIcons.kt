// 图标（PRD-ui 6.6 节）：全部来自 Lucide（ISC 许可，见 THIRD_PARTY_NOTICES.md），stroke-width 1.75。路径和 lucide-react 相同。
// 描边色只是占位：Icon(tint = …) 用 token 着色。
package com.tianting.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = "M$x1 ${y1}L$x2 $y2"

private fun lucide(name: String, vararg d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (p in d) {
            addPath(
                pathData = addPathNodes(p),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

internal object TtIcons {
    val chats by lazy {
        lucide(
            "message-square-text",
            "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z",
            "M7 11h10", "M7 15h6", "M7 7h8",
        )
    }
    val contacts by lazy {
        lucide(
            "book-user",
            "M15 13a3 3 0 1 0-6 0",
            "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
            circle(12f, 8f, 2f),
        )
    }
    val me by lazy { lucide("circle-user-round", "M17.925 20.056a6 6 0 0 0-11.851.001", circle(12f, 11f, 4f), circle(12f, 12f, 10f)) }
    val plus by lazy { lucide("circle-plus", circle(12f, 12f, 10f), "M8 12h8", "M12 8v8") }
    val addFriend by lazy { lucide("user-plus", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", circle(9f, 7f, 4f), line(19f, 8f, 19f, 14f), line(22f, 11f, 16f, 11f)) }
    val users by lazy { lucide("users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M16 3.128a4 4 0 0 1 0 7.744", "M22 21v-2a4 4 0 0 0-3-3.87", circle(9f, 7f, 4f)) }
    val back by lazy { lucide("chevron-left", "m15 18-6-6 6-6") }
    val more by lazy { lucide("ellipsis", circle(12f, 12f, 1f), circle(19f, 12f, 1f), circle(5f, 12f, 1f)) }
    val failed by lazy { lucide("circle-alert", circle(12f, 12f, 10f), line(12f, 8f, 12f, 12f), line(12f, 16f, 12.01f, 16f)) }
    val loading by lazy { lucide("loader-circle", "M21 12a9 9 0 1 1-6.219-8.56") }
    // 图片和文件（PRD-chat 第 13.3 节，FE-635、FE-636）
    val image by lazy { lucide("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21") }
    val file by lazy { lucide("file", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4") }
    val close by lazy { lucide("x", "M18 6 6 18", "m6 6 12 12") }
    val download by lazy { lucide("download", "M12 15V3", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5") }
    val smile by lazy { lucide("smile", circle(12f, 12f, 10f), "M8 14s1.5 2 4 2 4-2 4-2", line(9f, 9f, 9.01f, 9f), line(15f, 9f, 15.01f, 9f)) }
    // 举报（PRD-chat F-MOD-03 规则 8，frontend-chat 第 80 条：Lucide flag，PRD-ui 6.6 节清单随 BE-497 第 4 条；同 H5）
    val report by lazy {
        lucide("flag", "M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528")
    }
    val newFriend by lazy { lucide("user-round-plus", "M2 21a8 8 0 0 1 13.292-6", circle(10f, 8f, 5f), "M19 16v6", "M22 19h-6") }
    // 会话列表的免打扰（PRD-chat F-CVS-02 规则 5，C5；PRD-ui 6.6 节清单随 BE-488，和 H5 同一个图标）
    val notifyOff by lazy {
        lucide(
            "bell-off",
            "M10.268 21a2 2 0 0 0 3.464 0",
            "M17 17H4a1 1 0 0 1-.74-1.673C4.59 13.956 6 12.499 6 8a6 6 0 0 1 .258-1.742",
            "m2 2 20 20",
            "M8.668 3.01A6 6 0 0 1 18 8c0 2.687.77 4.653 1.707 6.05",
        )
    }
    val groups by lazy {
        lucide(
            "messages-square",
            "M16 10a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 14.286V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z",
            "M20 9a2 2 0 0 1 2 2v10.286a.71.71 0 0 1-1.212.502l-2.202-2.202A2 2 0 0 0 17.172 19H10a2 2 0 0 1-2-2v-1",
        )
    }
}
