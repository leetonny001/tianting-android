package com.tianting.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tianting.app.core.REPORT_ASK_BLOCK
import com.tianting.app.core.REPORT_DONE
import com.tianting.app.core.REPORT_HINT
import com.tianting.app.core.REPORT_NOTE_CLIPPED
import com.tianting.app.core.REPORT_REASONS
import com.tianting.app.core.errCode
import com.tianting.app.core.messageFor
import com.tianting.app.core.reportErrorFinal
import com.tianting.app.core.reportErrorText
import com.tianting.app.core.reportNote
import com.tianting.app.core.reportNoteInput
import com.tianting.app.ui.Confirm
import com.tianting.app.ui.FieldError
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.SecondaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.TtInput
import com.tianting.app.ui.TtSheet
import com.tianting.app.ui.dataMark
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import com.tianting.app.ui.theme.TtDimen
import com.tianting.app.ui.theme.TtIcons
import com.tianting.sdk.REPORT_NOTE_MAX
import com.tianting.sdk.ReportReason
import com.tianting.sdk.ReportTarget
import com.tianting.sdk.countCodePoints

/** 一次举报：对象，以及成功后要询问拉黑的人（单聊消息、资料卡举报用户；群消息、群不问，frontend-chat 第 79 条）。 */
data class ReportRequest(val target: ReportTarget, val askBlock: String? = null)

private enum class ReportStage { FORM, BLOCK }

/**
 * 举报单（PRD-chat F-MOD-03 规则 8，FE-694）：底部弹层，理由单选（6 项，默认不选）、补充说明（可选，0～200 码点，计数显示）、
 * 提示"所选内容将提交给平台审核"。提交中按钮禁用、弹层不能关。成功轻提示"已提交，感谢你的反馈"，askBlock 不在黑名单里时接着询问
 * 是否拉黑，拉黑失败单独提示，不影响举报结果。4040、4030、4292 提示后关闭；其他失败（5000、4290、超时、断线）留着，可以再点提交。
 * 不自动重试；note 不进日志（SDK 保证，这里也不写）。
 */
@Composable
fun ReportSheet(req: ReportRequest, onClose: () -> Unit) {
    val im = LocalIm.current
    val app = LocalApp.current
    var reason by remember(req) { mutableStateOf<ReportReason?>(null) }
    var note by remember(req) { mutableStateOf("") }
    var clipped by remember(req) { mutableStateOf(false) }
    var busy by remember(req) { mutableStateOf(false) }
    var stage by remember(req) { mutableStateOf(ReportStage.FORM) }
    var blockTarget by remember(req) { mutableStateOf<String?>(null) }

    fun submit() {
        val r = reason ?: return
        if (busy) return
        busy = true
        val text = reportNote(note)
        im.launchCmd({ e ->
            busy = false
            app.toast.show(reportErrorText(e))
            if (reportErrorFinal(e)) onClose()
        }) {
            im.client.report(req.target, r, text)
            busy = false
            app.toast.show(REPORT_DONE)
            // 已经在黑名单里的不问（按提交成功时的黑名单）
            val ask = req.askBlock?.takeIf { id -> im.blocks.none { it.userId == id } }
            if (ask != null) {
                blockTarget = ask
                stage = ReportStage.BLOCK
            } else {
                onClose()
            }
        }
    }

    val ask = blockTarget
    if (stage == ReportStage.BLOCK && ask != null) {
        Confirm(
            REPORT_ASK_BLOCK + "\n" + BLOCK_TEXT,
            "加入黑名单",
            "report-block-dialog",
            onCancel = onClose,
            onOk = {
                onClose()
                im.launchCmd({ e -> app.toast.show(messageFor(errCode(e), "block")) }) { im.client.block(ask) }
            },
        )
        return
    }

    TtSheet("report-sheet", { if (!busy) onClose() }, canDismiss = !busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TtIcons.report, null, Modifier.size(20.dp), tint = Tt.colors.textPrimary)
            T("举报", Modifier.tag("report-title"), style = Tt.type.title)
        }
        T("请选择举报理由", style = Tt.type.small, color = Tt.colors.textSecondary)
        Column(Modifier.tag("report-reasons").selectableGroup()) {
            for ((value, label) in REPORT_REASONS) {
                val selected = reason == value
                Row(
                    Modifier.fillMaxWidth().heightIn(min = TtDimen.sizeTouchMin.dp).tag("report-reason-${value.wire}")
                        .semantics { if (selected) dataMark = "selected" }
                        .selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = { reason = value }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = !busy,
                        colors = RadioButtonDefaults.colors(selectedColor = Tt.colors.brand, unselectedColor = Tt.colors.textTertiary),
                    )
                    T(label, Modifier.padding(start = 8.dp))
                }
            }
        }
        T("补充说明（可选）", style = Tt.type.small, color = Tt.colors.textSecondary)
        TtInput(
            note,
            { v ->
                val r = reportNoteInput(note, v)
                note = r.value
                clipped = r.clipped
            },
            "report-note",
            singleLine = false,
            minLines = 3,
            enabled = !busy,
        )
        T("${countCodePoints(note)}/$REPORT_NOTE_MAX", Modifier.tag("report-count").align(Alignment.End), style = Tt.type.caption, color = Tt.colors.textTertiary)
        if (clipped) FieldError(REPORT_NOTE_CLIPPED, "report-clipped")
        T(REPORT_HINT, Modifier.tag("report-hint"), style = Tt.type.small, color = Tt.colors.textSecondary)
        PrimaryButton(if (busy) "提交中…" else "提交", Modifier.fillMaxWidth().tag("report-submit"), enabled = reason != null && !busy) { submit() }
        SecondaryButton("取消", Modifier.fillMaxWidth().tag("report-cancel"), enabled = !busy) { onClose() }
    }
}
