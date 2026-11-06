package com.tianting.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tianting.sdk.AccountSession
import com.tianting.sdk.PendingLogout
import com.tianting.sdk.PendingLogoutStore
import com.tianting.sdk.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore 的 AES-GCM 密钥加密后存到 noBackup 目录的文件（PRD-ui 3.4、AC-UI-16-3，PRD-chat F-ACC-03 规则 8），不进 SharedPreferences。
 * PRD 建议的 EncryptedSharedPreferences 已被 androidx 标为废弃，且它的密钥集本身存在 SharedPreferences 里，这里不用。
 * 先写临时文件再改名：新 token 先落盘再使用（R-CF-09），写到一半被杀不能把旧值也弄坏。
 */
internal class EncryptedFile(context: Context, name: String) {
    private val file = File(context.noBackupFilesDir, name)
    private val tmp = File(context.noBackupFilesDir, "$name.tmp")

    /** 没有文件返回 null。解不开时抛异常，由调用方决定怎么办。 */
    fun read(): String? {
        if (!file.isFile) return null
        val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }

    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        tmp.writeText(Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        check(tmp.renameTo(file)) { "rename failed" }
    }

    fun delete() {
        tmp.delete()
        file.delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "tianting-session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * 登录凭证。内容是 AccountSession.toJson（含 refreshToken、mode）；老版本留下的 {userId, token} 由 fromJson 按演示会话读出来（R-CF-11）。
 * 解不开（系统升级丢了密钥、文件损坏）就当没有登录。
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val file = EncryptedFile(context, FILE)

    override suspend fun load(): AccountSession? = try {
        file.read()?.let { text ->
            AccountSession.fromJson(text) ?: run {
                AppLog.warn("session: unreadable content")
                file.delete()
                null
            }
        }
    } catch (e: Exception) {
        AppLog.warn("session: unreadable (${e.javaClass.simpleName})")
        file.delete()
        null
    }

    override suspend fun save(session: AccountSession) = file.write(session.toJson())

    override suspend fun clear() = file.delete()

    companion object {
        const val FILE = "tianting.session"
    }
}

/** "待退出"（F-ACC-04 规则 1）：只有 sid 和 refresh token，不算登录状态，但 refresh token 同样要加密。解不开就当没有，这条会话等到期。 */
class KeystorePendingLogoutStore(context: Context) : PendingLogoutStore {
    private val file = EncryptedFile(context, FILE)

    override suspend fun load(): List<PendingLogout> = try {
        val a = file.read()?.let { JSONArray(it) }
        if (a == null) {
            emptyList()
        } else {
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                PendingLogout(o.getString("sid"), o.getString("refreshToken"))
            }
        }
    } catch (e: Exception) {
        AppLog.warn("pending logout: unreadable (${e.javaClass.simpleName})")
        file.delete()
        emptyList()
    }

    override suspend fun save(items: List<PendingLogout>) {
        if (items.isEmpty()) {
            file.delete()
            return
        }
        val a = JSONArray()
        for (p in items) a.put(JSONObject().put("sid", p.sid).put("refreshToken", p.refreshToken))
        file.write(a.toString())
    }

    companion object {
        const val FILE = "tianting.pending-logout"
    }
}
