package com.tianting.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(val userId: String, val token: String)

interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

/**
 * 登录凭证（PRD-ui 3.4、AC-UI-16-3）：token 用 Android Keystore 的 AES-GCM 密钥加密后存到 noBackup 目录的文件，不进 SharedPreferences。
 * PRD 建议的 EncryptedSharedPreferences 已被 androidx 标为废弃，且它的密钥集本身存在 SharedPreferences 里，这里不用。
 * 解不开（系统升级丢了密钥、文件损坏）就当没有登录。
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val file = File(context.noBackupFilesDir, FILE)

    override fun load(): Session? = try {
        if (!file.isFile) {
            null
        } else {
            val raw = Base64.decode(file.readText(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
            val o = JSONObject(String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8))
            Session(o.getString("userId"), o.getString("token"))
        }
    } catch (e: Exception) {
        AppLog.warn("session: unreadable (${e.javaClass.simpleName})")
        clear()
        null
    }

    override fun save(session: Session) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = JSONObject().put("userId", session.userId).put("token", session.token).toString()
        file.writeText(Base64.encodeToString(cipher.iv + cipher.doFinal(body.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
    }

    override fun clear() {
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

    companion object {
        const val FILE = "tianting.session"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "tianting-session"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
