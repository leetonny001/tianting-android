package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tianting.app.core.ImSession
import com.tianting.app.core.Navigator
import com.tianting.app.core.formatTime
import com.tianting.app.ui.EmptyState
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.LocalNav
import com.tianting.app.ui.Page
import com.tianting.app.ui.T
import com.tianting.app.ui.TopBar
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt

/** 原生的列表只在内存（PRD-live F-PUSH-12 规则 6）。 */
const val NOTICES_FOOTER = "只显示本次打开以来收到的通知"

/** 我 → 系统通知（PRD-ui F-UI-44）：按 ts 倒序，最多 100 条；打开时未查看清零，停留期间不计数。文本按纯文本渲染（NF-UI-12）。 */
@Composable
fun NoticesPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    DisposableEffect(im) {
        im.notices.enterPage()
        onDispose { im.notices.leavePage() }
    }
    val list = im.noticeState.list
    Page {
        TopBar(title = "系统通知", onBack = { nav.back() })
        Column(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                EmptyState("暂无系统通知", "notices-empty", Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f).tag("notices")) {
                    items(list, key = { it.pushId }) { n ->
                        Column(Modifier.fillMaxWidth().background(Tt.colors.surface).tag("notice-item")) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                T(n.text, Modifier.tag("notice-text"))
                                T(formatTime(n.ts), Modifier.padding(top = 4.dp), style = Tt.type.caption, color = Tt.colors.textSecondary)
                            }
                            HorizontalDivider(color = Tt.colors.divider)
                        }
                    }
                }
            }
            T(NOTICES_FOOTER, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).tag("notices-footer"), style = Tt.type.caption, color = Tt.colors.textSecondary)
        }
    }
}

/** 系统通知横幅（PRD-ui F-UI-44 规则 2）：容器顶部，5 秒；新的替换旧的；点击进系统通知页。 */
@Composable
fun NoticeBannerHost(im: ImSession, nav: Navigator) {
    val banner = im.noticeState.banner ?: return
    Column(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Tt.colors.brandFill).tag("notice-banner")
            .clickable {
                im.notices.dismissBanner()
                if (nav.current.path != "/me/notices") nav.go("/me/notices")
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        T(banner.text, Modifier.tag("notice-banner-text"), style = Tt.type.small, color = Tt.colors.onBrand)
    }
}
