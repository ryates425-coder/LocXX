package com.locxx.app.nakama

import com.heroiclabs.nakama.DefaultSession
import com.heroiclabs.nakama.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Device auth over Nakama's **HTTP** API (`/v2/...`), same port as WebSocket (7350 / Azure 443).
 * [com.heroiclabs.nakama.DefaultClient] uses **gRPC** (default 7349); single-port Azure ingress targets HTTP only,
 * so gRPC to that port yields **UNIMPLEMENTED**.
 */
internal fun authenticateDeviceSessionRest(
    host: String,
    port: Int,
    useSsl: Boolean,
    serverKey: String,
    deviceId: String,
    username: String,
): Session {
    val scheme = if (useSsl) "https" else "http"
    val name = URLEncoder.encode(username.ifBlank { "Player" }, Charsets.UTF_8.name())
    val url =
        "$scheme://$host:$port/v2/account/authenticate/device?create=true&username=$name"
    val basic = Base64.getEncoder().encodeToString("$serverKey:".toByteArray(Charsets.UTF_8))
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val jsonBody = JSONObject().put("id", deviceId).toString()
    val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Basic $basic")
        .post(body)
        .build()
    client.newCall(request).execute().use { resp ->
        val respText = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            error("Nakama auth HTTP ${resp.code}: $respText")
        }
        val root = JSONObject(respText)
        val token = root.getString("token")
        val refresh = root.optString("refresh_token", "")
        return DefaultSession.restore(token, refresh)
    }
}
