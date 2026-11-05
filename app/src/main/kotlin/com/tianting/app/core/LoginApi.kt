package com.tianting.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed interface LoginResult {
    data class Ok(val session: Session) : LoginResult
    data class Failed(val message: String) : LoginResult
}

fun interface LoginApi {
    suspend fun login(username: String, nickname: String?): LoginResult
}

/** 演示登录（PRD-ui F-UI-03）：POST /demo/login，10 秒超时。4034 不是 ws 码，文案在这里映射（F-UI-43 规则 1）。 */
class DemoLoginApi(private val baseUrl: String) : LoginApi {
    private val http = OkHttpClient.Builder().callTimeout(LOGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    override suspend fun login(username: String, nickname: String?): LoginResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", username)
            if (!nickname.isNullOrEmpty()) put("nickname", nickname)
        }.toString().toRequestBody("application/json".toMediaType())
        try {
            http.newCall(Request.Builder().url("$baseUrl/demo/login").post(body).build()).execute().use { res ->
                parseLoginResponse(res.code, res.body?.string().orEmpty())
            }
        } catch (_: java.io.IOException) {
            LoginResult.Failed(NETWORK_ERROR)
        }
    }

    companion object {
        const val LOGIN_TIMEOUT_MS = 10_000L
        const val NETWORK_ERROR = "网络异常，请稍后重试"
    }
}

internal fun parseLoginResponse(status: Int, text: String): LoginResult {
    if (status == 404) return LoginResult.Failed("登录服务未开启")
    val data: JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
    val code = data["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    if (code == 4034) {
        val until = (data["details"] as? JsonObject)?.get("bannedUntil")?.jsonPrimitive?.longOrNull
        return LoginResult.Failed(bannedText(until))
    }
    val token = data["token"]?.jsonPrimitive?.contentOrNull
    val userId = data["userId"]?.jsonPrimitive?.contentOrNull
    if (status !in 200..299 || token.isNullOrEmpty() || userId.isNullOrEmpty()) return LoginResult.Failed(defaultMessage(code ?: 5000))
    return LoginResult.Ok(Session(userId, token))
}

