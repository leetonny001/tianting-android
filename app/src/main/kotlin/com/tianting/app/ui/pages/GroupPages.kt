package com.tianting.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianting.app.core.*
import com.tianting.app.ui.*
import com.tianting.app.ui.Badge
import com.tianting.app.ui.theme.*
import com.tianting.sdk.*
import com.tianting.sdk.Friend
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance

private val GONE_TEXT = mapOf("dissolved" to "群聊已解散", "left" to "你已退出该群聊")

/** 聊天信息页：单聊只读（F-UI-12）；群聊是群设置（F-UI-36），已不在群里时只显示群名和提示。 */
@Composable
fun ChatInfoPage(convId: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val parsed = remember(convId) {
        try {
            parseConvIdStrict(convId, im.self)
        } catch (_: ConvIdError) {
            null
        }
    }
    if (parsed == null) {
        LaunchedEffect(Unit) { nav.setRoot("/chats") }
        return
    }
    val group = parsed.convType == ConvType.GROUP
    val to = parsed.to
    val conv = im.convs.firstOrNull { it.convId == convId }
    val snapshot by produceState<ConversationSnapshot?>(null, convId, conv == null, im.removedTick) { value = im.client.conversationSnapshot(convId) }
    var gone by remember { mutableStateOf(false) }
    if (group && snapshot == null && !gone) {
        GroupSettings(convId) { gone = true }
        return
    }
    val profile = rememberProfile(if (group) im.self else to)
    val local = if (group) to else im.nameOf(to)
    val name = if (!group && local != to) local else conv?.name ?: snapshot?.name ?: to
    Page {
        TopBar(title = "聊天信息", onBack = { nav.back() })
        TtRow(
            tag = "info-head",
            onClick = if (group) null else ({ nav.go(userPath(to), RouteState(source = ApplySource.CONV)) }),
            minHeight = 96.dp,
        ) {
            Avatar(to, name, 64.dp, group = group, src = if (group) null else profile?.avatar)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(name, Modifier.weight(1f, fill = false).tag("info-name"), style = Tt.type.title)
                    if (!group && profile?.official == true) OfficialTag()
                }
                T(if (group) "群聊" else "账号：$to", Modifier.tag("info-sub"), style = Tt.type.small, color = Tt.colors.textSecondary)
            }
        }
        if (group && conv == null) {
            T(GONE_TEXT[snapshot?.reason] ?: "你已不在该群", Modifier.tag("info-removed").fillMaxWidth().padding(16.dp), color = Tt.colors.textSecondary, align = TextAlign.Center)
        }
    }
}

/** 群资料（F-UI-36 规则 1）：进入和重连时查一次；之后由 groupChanged 带来。gone 是 4033 / 4043。 */
class GroupInfoState(private val im: ImSession, private val groupId: String) {
    var info by mutableStateOf<GroupInfo?>(null)
    var gone by mutableStateOf(false)

    suspend fun refresh() {
        try {
            info = im.client.getGroupInfo(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = errCode(e)
            if (e is IMError && (code == 4033 || code == 4043)) gone = true
        }
    }
}

@Composable
fun rememberGroupInfo(groupId: String): GroupInfoState {
    val im = LocalIm.current
    val s = remember(groupId) { GroupInfoState(im, groupId) }
    val connected = im.state == ConnState.CONNECTED
    LaunchedEffect(groupId, connected) { if (connected) s.refresh() }
    LaunchedEffect(groupId) {
        im.events.collect { e ->
            when {
                e is IMEvent.GroupChanged && e.groupId == groupId -> s.info = e.info
                e is IMEvent.ConvRemoved && e.convId == groupConvId(groupId) -> s.gone = true
            }
        }
    }
    return s
}

/** 群成员分页（F-UI-38）：loadMore 加载下一页并按 userId 去重；reload 从头拉到之前的数量。收到 groupChanged 时自动 reload。 */
class GroupMembersState(private val im: ImSession, private val groupId: String) {
    var members by mutableStateOf<List<GroupMemberInfo>>(emptyList())
    var loaded by mutableStateOf(false)
    var hasMore by mutableStateOf(false)
    private var cursor = ""
    private var generation = 0
    private var busy = false

    private fun merge(list: List<GroupMemberInfo>, page: List<GroupMemberInfo>): List<GroupMemberInfo> {
        val seen = list.mapTo(HashSet()) { it.userId }
        return list + page.filter { it.userId !in seen }
    }

    private fun publish(list: List<GroupMemberInfo>, next: String) {
        cursor = next
        members = list
        hasMore = next.isNotEmpty()
        loaded = true
    }

    suspend fun reload() {
        val mine = ++generation
        val keep = members.size
        busy = true
        try {
            var list = emptyList<GroupMemberInfo>()
            var next = ""
            do {
                val page = im.client.loadGroupMembers(groupId, next.ifEmpty { null })
                if (mine != generation) return
                list = merge(list, page.members)
                next = page.nextCursor
            } while (next.isNotEmpty() && list.size < keep)
            publish(list, next)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 保留现有列表；连接恢复或下一次 groupChanged 时重查
        } finally {
            if (mine == generation) busy = false
        }
    }

    suspend fun loadMore() {
        if (busy || cursor.isEmpty()) return
        val mine = generation
        busy = true
        try {
            val page = im.client.loadGroupMembers(groupId, cursor)
            if (mine == generation) publish(merge(members, page.members), page.nextCursor)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 下次滚动时重试
        } finally {
            if (mine == generation) busy = false
        }
    }
}

@Composable
fun rememberGroupMembers(groupId: String): GroupMembersState {
    val im = LocalIm.current
    val s = remember(groupId) { GroupMembersState(im, groupId) }
    val connected = im.state == ConnState.CONNECTED
    LaunchedEffect(groupId, connected) { if (connected) s.reload() }
    LaunchedEffect(groupId) { im.events.filterIsInstance<IMEvent.GroupChanged>().collect { if (it.groupId == groupId) s.reload() } }
    return s
}

private const val GRID_SIZE = 20

private enum class GroupConfirm { LEAVE, DISSOLVE }

/** 群设置（F-UI-36）：入口按我的角色和托管状态显示。已不在群里时回到只读提示。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupSettings(convId: String, onGone: () -> Unit) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val groupId = groupIdOf(convId)
    val state = rememberGroupInfo(groupId)
    val members = rememberGroupMembers(groupId)
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<GroupConfirm?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) onGone() }
    val info = state.info
    if (info == null) {
        Page {
            TopBar(title = "聊天信息", onBack = { nav.back() })
            RowsSkeleton("group-skeleton")
        }
        return
    }
    val role = info.myRole
    val manager = role == GroupRole.OWNER || role == GroupRole.ADMIN
    val selfOwned = !info.managed && role == GroupRole.OWNER
    val canInvite = !info.managed || info.owner.isNotEmpty()
    val rowState = RouteState(source = ApplySource.GROUP, sourceRef = groupId)

    fun act(cmd: String, op: suspend () -> Unit, done: suspend () -> Unit) {
        if (inFlight) return
        inFlight = true
        im.launchCmd({ e ->
            inFlight = false
            val code = errCode(e)
            app.toast.show(messageFor(code, cmd))
            if (code == 4030) im.launchCmd { state.refresh() }
        }) {
            op()
            done()
            inFlight = false
        }
    }

    Page {
        TopBar(title = "聊天信息（${info.memberCount}）", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FlowRow(Modifier.tag("member-grid").fillMaxWidth().background(Tt.colors.surface).padding(8.dp)) {
                for (m in members.members.take(GRID_SIZE)) {
                    val name = im.nameOf(m.userId).let { if (it == m.userId && m.nickname.isNotEmpty()) m.nickname else it }
                    Column(
                        Modifier.width(72.dp).tag("grid-member").semantics { dataUser = m.userId }.clickable { nav.go(userPath(m.userId), rowState) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(m.userId, name, 48.dp, src = m.avatar)
                        T(name, style = Tt.type.caption, color = Tt.colors.textSecondary, maxLines = 1)
                    }
                }
                if (canInvite) GridOp("+", "邀请成员", "grid-invite") { nav.go(invitePath(convId)) }
                if (manager) GridOp("−", "移出成员", "grid-kick") { nav.go(membersPath(convId), RouteState(mode = MemberMode.KICK)) }
            }
            TtRow(tag = "all-members", onClick = { nav.go(membersPath(convId)) }) { T("查看全部成员（${info.memberCount}）") }
            Spacer(Modifier.height(12.dp))
            TtRow(
                Modifier.semantics { dataEditable = manager.toString() },
                tag = "group-name",
                onClick = if (manager) ({ renaming = info.name }) else null,
            ) {
                T("群聊名称")
                T(info.name, Modifier.weight(1f).tag("group-name-value"), color = Tt.colors.textSecondary, maxLines = 1, align = TextAlign.End)
            }
            TtRow(tag = "group-notice", onClick = { nav.go(noticePath(convId)) }) {
                Column {
                    T("群公告")
                    T(info.notice.ifEmpty { "暂无群公告" }, Modifier.tag("group-notice-preview"), style = Tt.type.small, color = Tt.colors.textSecondary, maxLines = 2)
                }
            }
            if (role == GroupRole.OWNER) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.tag("group-manage")) {
                    TtRow(tag = "manage-admin", onClick = { nav.go(membersPath(convId), RouteState(mode = MemberMode.ADMIN)) }) { T("设置管理员") }
                    TtRow(tag = "manage-transfer", onClick = { nav.go(membersPath(convId), RouteState(mode = MemberMode.TRANSFER)) }) { T("转让群主") }
                }
            }
            Spacer(Modifier.height(12.dp))
            TtRow(tag = "group-leave", onClick = { confirm = GroupConfirm.LEAVE }) { T("退出群聊", Modifier.fillMaxWidth(), color = Tt.colors.danger, align = TextAlign.Center) }
            if (selfOwned) TtRow(tag = "group-dissolve", onClick = { confirm = GroupConfirm.DISSOLVE }) { T("解散群聊", Modifier.fillMaxWidth(), color = Tt.colors.danger, align = TextAlign.Center) }
        }
    }
    val draft = renaming
    if (draft != null) {
        val len = countCodePoints(trimText(draft))
        val tooLong = len > MAX_GROUP_NAME
        TtSheet("rename-sheet", { renaming = null }) {
            T("修改群聊名称", style = Tt.type.title)
            TtInput(draft, { renaming = it }, "rename-input")
            if (tooLong) FieldError("最多 $MAX_GROUP_NAME 个字")
            PrimaryButton("保存", Modifier.fillMaxWidth().tag("rename-save"), enabled = !tooLong && len > 0) {
                val name = trimText(draft)
                renaming = null
                if (name != info.name) act("groupUpdate", { im.client.updateGroup(groupId, name = name) }, { state.refresh() })
            }
        }
    }
    when (confirm) {
        GroupConfirm.LEAVE -> Confirm(
            if (selfOwned) "你是群主，退出后群主将自动转让给其他成员" else "退出后，将不再接收此群聊消息",
            "退出",
            "leave-confirm",
            danger = true,
            onCancel = { confirm = null },
            onOk = {
                confirm = null
                act("groupLeave", { im.client.leaveGroup(groupId) }, { nav.setRoot("/chats") })
            },
        )
        GroupConfirm.DISSOLVE -> Confirm(
            "解散后，所有成员将被移出群聊，且不能恢复",
            "解散",
            "dissolve-confirm",
            danger = true,
            onCancel = { confirm = null },
            onOk = {
                confirm = null
                act("groupDissolve", { im.client.dissolveGroup(groupId) }, { nav.setRoot("/chats") })
            },
        )
        null -> Unit
    }
}

@Composable
private fun GridOp(text: String, description: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.width(72.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick, Modifier.tag(tag).size(48.dp).semantics { contentDescription = description }) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(TtDimen.radiusMd.dp)).background(Tt.colors.bg), contentAlignment = Alignment.Center) {
                T(text, style = Tt.type.large, color = Tt.colors.textSecondary)
            }
        }
    }
}

/** 群公告（F-UI-37）：纯文本，保留换行；群主、管理员可编辑，空字符串表示清除。 */
@Composable
fun GroupNoticePage(convId: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val groupId = groupIdOf(convId)
    val state = rememberGroupInfo(groupId)
    val info = state.info
    var draft by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val canEdit = info?.myRole == GroupRole.OWNER || info?.myRole == GroupRole.ADMIN
    val len = draft?.let { countCodePoints(trimText(it)) } ?: 0
    val tooLong = len > MAX_NOTICE
    LaunchedEffect(state.gone) { if (state.gone) nav.back() }

    fun save() {
        val d = draft
        if (inFlight || tooLong || d == null) return
        val next = trimText(d)
        if (next == info?.notice) {
            draft = null
            return
        }
        inFlight = true
        im.launchCmd({ e ->
            inFlight = false
            val code = errCode(e)
            app.toast.show(messageFor(code, "groupUpdate"))
            if (code == 4030) {
                draft = null
                im.launchCmd { state.refresh() }
            }
        }) {
            im.client.updateGroup(groupId, notice = next)
            draft = null
            state.refresh()
            inFlight = false
        }
    }

    Page {
        TopBar(
            title = "群公告",
            onBack = { nav.back() },
            right = {
                if (canEdit && info != null) {
                    if (draft == null) TopBarAction("编辑", "notice-edit") { draft = info.notice } else TopBarAction("完成", "notice-save", enabled = !tooLong) { save() }
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val d = draft
            when {
                info == null -> RowsSkeleton("notice-skeleton")
                d != null -> {
                    TtInput(d, { draft = it }, "notice-input", singleLine = false, minLines = 8)
                    T("已输入 $len / $MAX_NOTICE", Modifier.tag("notice-count").align(Alignment.End), style = Tt.type.caption, color = if (tooLong) Tt.colors.danger else Tt.colors.textTertiary)
                }
                else -> {
                    if (info.notice.isNotEmpty()) {
                        T(info.notice, Modifier.tag("notice-text"))
                        val by = rememberDisplayName(info.noticeBy)
                        T(by + if (info.noticeAt > 0) " ${formatTime(info.noticeAt)}" else "", Modifier.tag("notice-meta"), style = Tt.type.small, color = Tt.colors.textSecondary)
                    } else {
                        T("暂无群公告", Modifier.tag("notice-empty").fillMaxWidth(), color = Tt.colors.textSecondary, align = TextAlign.Center)
                    }
                    if (!canEdit) T("仅群主和管理员可编辑", Modifier.tag("notice-readonly").fillMaxWidth(), style = Tt.type.small, color = Tt.colors.textSecondary, align = TextAlign.Center)
                }
            }
        }
    }
}

private val ROLE_LABEL = mapOf(GroupRole.OWNER to "群主", GroupRole.ADMIN to "管理员")

/** 我的角色能进入的管理模式（F-UI-36 规则 2）。角色被改之后停留在本页时回到查看模式。 */
private fun allowedMode(mode: MemberMode, role: GroupRole?): MemberMode = when {
    mode == MemberMode.KICK && (role == GroupRole.OWNER || role == GroupRole.ADMIN) -> MemberMode.KICK
    (mode == MemberMode.ADMIN || mode == MemberMode.TRANSFER) && role == GroupRole.OWNER -> mode
    else -> MemberMode.VIEW
}

/** 群成员列表（F-UI-38）：顺序以服务端为准，不做本地排序和过滤。 */
@Composable
fun GroupMembersPage(convId: String, initialMode: MemberMode) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val groupId = groupIdOf(convId)
    val state = rememberGroupInfo(groupId)
    val members = rememberGroupMembers(groupId)
    var rawMode by remember { mutableStateOf(initialMode) }
    val mode = allowedMode(rawMode, state.info?.myRole)
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    var kickConfirm by remember { mutableStateOf(false) }
    var transferTo by remember { mutableStateOf<GroupMemberInfo?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(state.gone) { if (state.gone) nav.back() }
    // 滚动到底部前 5 项时加载下一页；一页撑不满屏幕时直接加载。
    LaunchedEffect(list, members) {
        snapshotFlow {
            val i = list.layoutInfo
            members.hasMore && (i.visibleItemsInfo.lastOrNull()?.index ?: 0) >= i.totalItemsCount - 5
        }.collect { if (it) members.loadMore() }
    }
    val toView = {
        picked = emptyList()
        rawMode = MemberMode.VIEW
    }

    fun run(cmd: String, op: suspend () -> Unit, done: () -> Unit = {}) {
        if (inFlight) return
        inFlight = true
        im.launchCmd({ e ->
            inFlight = false
            val code = errCode(e)
            app.toast.show(messageFor(code, cmd))
            if (code == 4030 || code == 4046) {
                im.launchCmd {
                    state.refresh()
                    members.reload()
                }
            }
        }) {
            op()
            done()
            state.refresh()
            members.reload()
            inFlight = false
        }
    }

    fun selectable(m: GroupMemberInfo): Boolean = when {
        m.role == GroupRole.OWNER || m.userId == im.self -> false
        mode == MemberMode.KICK -> state.info?.myRole == GroupRole.OWNER || m.role == GroupRole.MEMBER
        else -> mode == MemberMode.ADMIN || mode == MemberMode.TRANSFER
    }

    fun pick(m: GroupMemberInfo) {
        when {
            mode == MemberMode.ADMIN -> run("groupSetAdmin", { im.client.setGroupAdmin(groupId, m.userId, m.role != GroupRole.ADMIN) })
            mode == MemberMode.TRANSFER -> transferTo = m
            m.userId in picked -> picked = picked - m.userId
            picked.size >= MAX_GROUP_BATCH -> app.toast.show("一次最多移出 $MAX_GROUP_BATCH 人")
            else -> picked = picked + m.userId
        }
    }

    val title = when (mode) {
        MemberMode.VIEW -> "群成员" + (state.info?.let { "（${it.memberCount}）" } ?: "")
        MemberMode.KICK -> "移出成员"
        MemberMode.ADMIN -> "设置管理员"
        MemberMode.TRANSFER -> "转让群主"
    }
    Page {
        TopBar(
            title = title,
            onBack = { if (mode == MemberMode.VIEW) nav.back() else if (initialMode == MemberMode.VIEW) toView() else nav.back() },
            right = {
                if (mode == MemberMode.KICK) TopBarAction("移出（${picked.size}）", "kick-submit", enabled = picked.isNotEmpty(), danger = true) { kickConfirm = true }
            },
        )
        if (!members.loaded) {
            RowsSkeleton("members-skeleton")
        } else {
            LazyColumn(Modifier.weight(1f).tag("member-list"), state = list) {
                items(members.members, key = { it.userId }) { m ->
                    val can = mode == MemberMode.VIEW || selectable(m)
                    val rowState = RouteState(source = ApplySource.GROUP, sourceRef = groupId)
                    TtRow(
                        Modifier.semantics { dataUser = m.userId },
                        tag = "member-row",
                        onClick = if (!can) null else if (mode == MemberMode.VIEW) ({ nav.go(userPath(m.userId), rowState) }) else ({ pick(m) }),
                        minHeight = 56.dp,
                    ) {
                        if (mode == MemberMode.KICK) {
                            Checkbox(checked = m.userId in picked, onCheckedChange = null, enabled = can, modifier = Modifier.tag("member-check"), colors = checkboxColors())
                        }
                        val name = im.nameOf(m.userId).let { if (it == m.userId && m.nickname.isNotEmpty()) m.nickname else it }
                        Avatar(m.userId, name, 40.dp, src = m.avatar)
                        T(name + if (m.userId == im.self) " （我）" else "", Modifier.weight(1f, fill = false).tag("member-name"), maxLines = 1)
                        if (m.official) OfficialTag()
                        ROLE_LABEL[m.role]?.let { T(it, Modifier.tag("member-role"), style = Tt.type.caption, color = Tt.colors.textTertiary) }
                    }
                }
            }
        }
    }
    if (kickConfirm) {
        Confirm("确定将 ${picked.size} 人移出群聊？", "移出", "kick-confirm", danger = true, onCancel = { kickConfirm = false }, onOk = {
            kickConfirm = false
            val ids = picked
            run("groupKick", { im.client.kickFromGroup(groupId, ids) }, toView)
        })
    }
    val t = transferTo
    if (t != null) {
        val name = im.nameOf(t.userId).let { if (it == t.userId && t.nickname.isNotEmpty()) t.nickname else it }
        Confirm("确定将群主转让给 $name？转让后你将成为普通成员", "转让", "transfer-confirm", onCancel = { transferTo = null }, onOk = {
            transferTo = null
            run("groupTransfer", { im.client.transferGroupOwner(groupId, t.userId) }, toView)
        })
    }
}

@Composable
internal fun checkboxColors() = CheckboxDefaults.colors(checkedColor = Tt.colors.brandFill, checkmarkColor = Tt.colors.onBrand, uncheckedColor = Tt.colors.borderInput, disabledCheckedColor = Tt.colors.divider, disabledUncheckedColor = Tt.colors.divider)

/** 好友多选（F-UI-35 规则 2、F-UI-39 规则 1）：按显示名或 userId 本地过滤（不区分大小写），分组同通讯录。 */
@Composable
private fun ColumnScope.FriendPicker(selected: List<String>, onToggle: (String) -> Unit, joined: Set<String> = emptySet(), errors: Set<String> = emptySet()) {
    val im = LocalIm.current
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(im.friends, query) {
        val q = query.trim().lowercase()
        val list = if (q.isEmpty()) im.friends else im.friends.filter { it.displayName.lowercase().contains(q) || it.userId.lowercase().contains(q) }
        groupByPinyin(list, im.collator, { it.displayName }, { it.userId })
    }
    Column(Modifier.background(Tt.colors.surface).padding(16.dp, 8.dp)) { TtInput(query, { query = it }, "pick-search", placeholder = "搜索") }
    if (selected.isNotEmpty()) {
        Row(Modifier.tag("pick-strip").fillMaxWidth().background(Tt.colors.surface).horizontalScroll(rememberScrollState()).padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in selected) {
                val f = im.friends.firstOrNull { it.userId == id }
                Avatar(id, f?.displayName ?: id, 36.dp, src = f?.profile?.avatar)
            }
        }
    }
    if (!im.socialLoaded) {
        RowsSkeleton("pick-skeleton")
        return
    }
    LazyColumn(Modifier.weight(1f).tag("pick-list")) {
        for (g in groups) {
            item("g-${g.group}") { T(g.group, Modifier.fillMaxWidth().padding(16.dp, 4.dp), style = Tt.type.caption, color = Tt.colors.textSecondary) }
            items(g.items, key = { "p-${it.userId}" }) { f ->
                val inGroup = f.userId in joined
                val error = f.userId in errors
                TtRow(
                    Modifier.semantics {
                        dataUser = f.userId
                        if (error) dataError = "true"
                    },
                    tag = "pick-row",
                    onClick = if (inGroup) null else ({ onToggle(f.userId) }),
                    minHeight = 56.dp,
                ) {
                    Checkbox(checked = inGroup || f.userId in selected, onCheckedChange = null, enabled = !inGroup, modifier = Modifier.tag("pick-check"), colors = checkboxColors())
                    Avatar(f.userId, f.displayName, 40.dp, src = f.profile?.avatar)
                    T(f.displayName, Modifier.weight(1f), color = if (error) Tt.colors.danger else Tt.colors.textPrimary, maxLines = 1)
                    if (inGroup) T("已在群里", Modifier.tag("pick-joined"), style = Tt.type.caption, color = Tt.colors.textTertiary)
                }
            }
        }
    }
}

/** 建群 / 邀请的错误文案（F-UI-35 规则 5、F-UI-39 规则 3）：带 userId 时用对方显示名，是自己时用"你"。 */
internal fun memberErrorText(code: Int, userId: String?, cmd: String, nameOf: (String) -> String, self: String): String {
    if (code == 4135 && userId == self) return "你加入的群聊已达上限"
    if (userId == null) return messageFor(code, if (code == 4035 || code == 4135) null else cmd)
    return messageFor(code, cmd, name = nameOf(userId))
}

/** ack 的 failed 文案：最多 3 条，其余合并（F-UI-39 规则 3）。 */
internal fun inviteResultText(added: Int, failed: List<GroupInviteFailure>, nameOf: (String) -> String, self: String): String {
    val parts = ArrayList<String>()
    if (added > 0) parts += "已邀请 $added 人"
    for (f in failed.take(3)) parts += memberErrorText(f.code, f.userId, "groupInvite", nameOf, self)
    if (failed.size > 3) parts += "另有 ${failed.size - 3} 人邀请失败"
    return parts.joinToString("；")
}

/** 邀请成员（F-UI-39）：已加载到的群成员显示"已在群里"；没加载到的由服务端忽略。 */
@Composable
fun GroupInvitePage(convId: String) {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val groupId = groupIdOf(convId)
    val members = rememberGroupMembers(groupId)
    val joined = remember(members.members) { members.members.mapTo(HashSet()) { it.userId } }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var errors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inFlight by remember { mutableStateOf(false) }

    fun invite() {
        if (inFlight) return
        inFlight = true
        im.launchCmd({ e ->
            inFlight = false
            app.toast.show(messageFor(errCode(e), "groupInvite"))
        }) {
            val r = im.client.inviteToGroup(groupId, selected)
            app.toast.show(inviteResultText(r.added.size, r.failed, im::nameOf, im.self))
            inFlight = false
            if (r.added.isNotEmpty()) nav.back() else errors = r.failed.mapTo(HashSet()) { it.userId }
        }
    }

    Page {
        TopBar(title = "邀请成员", onBack = { nav.back() }, right = { TopBarAction("完成（${selected.size}）", "pick-done", enabled = selected.isNotEmpty()) { invite() } })
        FriendPicker(
            selected,
            { id ->
                when {
                    id in selected -> selected = selected - id
                    selected.size >= MAX_GROUP_BATCH -> app.toast.show("一次最多邀请 $MAX_GROUP_BATCH 人")
                    else -> selected = selected + id
                }
            },
            joined,
            errors,
        )
    }
}

/** 发起群聊（F-UI-35）。 */
@Composable
fun GroupNewPage() {
    val im = LocalIm.current
    val nav = LocalNav.current
    val app = LocalApp.current
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var errors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var naming by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var inFlight by remember { mutableStateOf(false) }
    val tooLong = countCodePoints(trimText(name)) > MAX_GROUP_NAME

    fun create() {
        if (inFlight || tooLong) return
        inFlight = true
        im.launchCmd({ e ->
            inFlight = false
            naming = false
            val userId = (e as? IMError)?.userId
            app.toast.show(memberErrorText(errCode(e), userId, "groupCreate", im::nameOf, im.self))
            if (userId != null) errors = setOf(userId)
        }) {
            val created = im.client.createGroup(selected, trimText(name).ifEmpty { null })
            inFlight = false
            naming = false
            nav.go(chatPath(created.conv.convId), replace = true)
        }
    }

    Page {
        TopBar(title = "发起群聊", onBack = { nav.back() }, right = { TopBarAction("完成（${selected.size}）", "pick-done", enabled = selected.size >= MIN_GROUP_PICK) { naming = true } })
        FriendPicker(selected, { id -> selected = if (id in selected) selected - id else selected + id }, errors = errors)
    }
    if (naming) {
        TtSheet("group-name-sheet", { naming = false }) {
            T("群聊名称（选填）", style = Tt.type.title)
            TtInput(name, { name = it }, "group-name-input")
            if (tooLong) FieldError("最多 $MAX_GROUP_NAME 个字", "group-name-error")
            PrimaryButton("创建", Modifier.fillMaxWidth().tag("group-create"), enabled = !tooLong && !inFlight) { create() }
        }
    }
}
