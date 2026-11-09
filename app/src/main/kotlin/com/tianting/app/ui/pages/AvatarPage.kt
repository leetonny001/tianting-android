// 换头像（F-ACC-06 规则 3、AC-ACC-06-6）："我"页点头像 → 系统照片选择器选一张 → 裁剪成正方形（拖动、双指缩放）→ 512×512 jpeg
// → kind=avatar 上传 → PATCH profile。本次登录马上用上传的那张显示（服务端地址在测试环境不是 https，同 H5 的 H13）；
// SDK 改头像后重新拉自己的资料（tianting-sdk-kotlin，问题 H13）。
package com.tianting.app.ui.pages

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tianting.app.core.ImageProcessError
import com.tianting.app.core.ImageProcessing
import com.tianting.app.core.PickKind
import com.tianting.app.core.PickFailed
import com.tianting.app.core.STORAGE_FULL
import com.tianting.app.ui.LocalApp
import com.tianting.app.ui.LocalIm
import com.tianting.app.ui.PrimaryButton
import com.tianting.app.ui.SecondaryButton
import com.tianting.app.ui.T
import com.tianting.app.ui.tag
import com.tianting.app.ui.theme.Tt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val PREVIEW_LONG = 2048 // 解码给裁剪用的尺寸：输出只有 512，2048 足够

/** "我"页的头像：账号会话、打开了文件功能时点它换头像。 */
@Composable
fun MeAvatar(name: String, avatar: String?) {
    val im = LocalIm.current
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val editable = im.isAccount && im.filesEnabled

    fun load(uri: android.net.Uri) {
        val media = im.media ?: return
        busy = true
        scope.launch {
            try {
                val bmp = onMain {
                    val p = media.copyIn(uri)
                    try {
                        withContext(Dispatchers.IO) { ImageProcessing.decodeOriented(p.file, PREVIEW_LONG) }
                    } finally {
                        withContext(Dispatchers.IO) { p.file.delete() }
                    }
                }
                editing = bmp
            } catch (e: CancellationException) {
                throw e
            } catch (e: PickFailed) {
                app.toast.show(if (e.storage) STORAGE_FULL else com.tianting.app.core.SOURCE_MISSING)
            } catch (e: ImageProcessError) {
                app.toast.show("无法处理这张图片，请换一张")
            } catch (e: OutOfMemoryError) {
                app.toast.show("无法处理这张图片，请换一张")
            } finally {
                busy = false
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    val modifier = if (editable) {
        Modifier.tag("me-avatar").clickable(enabled = !busy) {
            val hook = app.deps.pickForTest
            if (hook != null) hook(PickKind.AVATAR)?.firstOrNull()?.let { load(it) } else launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.semantics { contentDescription = "更换头像" }
    } else {
        Modifier.tag("me-avatar")
    }
    Box(modifier) { com.tianting.app.ui.Avatar(im.self, name, 64.dp, src = avatar, local = im.media?.localAvatar) }
    editing?.let { bmp ->
        AvatarCrop(bmp, onCancel = {
            editing = null
            bmp.recycle()
        }) { x, y, side ->
            editing = null
            save(im, app, bmp, x, y, side, context.cacheDir)
        }
    }
}

/** 裁剪、上传、绑定。失败提示"头像没有修改成功，请稍后重试"（F-ACC-06 异常）。 */
private fun save(im: com.tianting.app.core.ImSession, app: com.tianting.app.core.AppModel, bmp: Bitmap, x: Int, y: Int, side: Int, cache: File) {
    val media = im.media ?: return
    val files = im.files ?: return
    im.launchCmd({ e ->
        com.tianting.app.core.AppLog.debug("avatar failed: ${(e as? com.tianting.sdk.IMError)?.code ?: e.javaClass.simpleName}")
        app.toast.show(if (com.tianting.app.core.MediaOps.isNoSpace(e)) STORAGE_FULL else "头像没有修改成功，请稍后重试")
    }) {
        val out = File(File(cache, "tt-avatar"), UUID.randomUUID().toString() + ".jpg")
        try {
            withContext(Dispatchers.IO) { ImageProcessing.avatar(bmp, x, y, side, out) }
        } finally {
            bmp.recycle()
        }
        val fileId = media.uploadAvatar(files, out)
        app.account.updateProfile(avatarFileId = fileId)
        media.localAvatar?.takeIf { it != out }?.let { old -> withContext(Dispatchers.IO) { old.delete() } }
        media.localAvatar = out
        app.toast.show("头像已更新")
    }
}

/** 裁剪框：正方形视口里拖动、双指缩放，图片始终盖满视口。返回原图上的 (x, y, 边长)。 */
@Composable
private fun AvatarCrop(bmp: Bitmap, onCancel: () -> Unit, onDone: (Int, Int, Int) -> Unit) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    val density = LocalDensity.current
    val viewDp = 280.dp
    val view = with(density) { viewDp.toPx() }
    val base = view / min(bmp.width, bmp.height) // 盖满视口的缩放
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(o: Offset, z: Float): Offset {
        val s = base * z
        val maxX = max(0f, (bmp.width * s - view) / 2)
        val maxY = max(0f, (bmp.height * s - view) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Tt.colors.surface).padding(16.dp).tag("avatar-crop"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            T("裁剪头像", style = Tt.type.title)
            Box(
                Modifier.size(viewDp).clipToBounds().border(2.dp, Tt.colors.brand).tag("avatar-crop-box")
                    .pointerInput(bmp) {
                        detectTransformGestures { _, pan, z, _ ->
                            zoom = (zoom * z).coerceIn(1f, 4f)
                            offset = clamp(offset + pan, zoom)
                        }
                    }
                    .semantics { contentDescription = "拖动和双指缩放选择头像范围" },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = base * zoom
                    val w = bmp.width * s
                    val h = bmp.height * s
                    drawImage(
                        image,
                        dstOffset = IntOffset(((view - w) / 2 + offset.x).roundToInt(), ((view - h) / 2 + offset.y).roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("取消", Modifier.weight(1f).tag("avatar-cancel"), onClick = onCancel)
                PrimaryButton("保存", Modifier.weight(1f).tag("avatar-save")) {
                    val s = base * zoom
                    val left = (view - bmp.width * s) / 2 + offset.x
                    val top = (view - bmp.height * s) / 2 + offset.y
                    val side = (view / s).roundToInt()
                    onDone((-left / s).roundToInt(), (-top / s).roundToInt(), side)
                }
            }
        }
    }
}
