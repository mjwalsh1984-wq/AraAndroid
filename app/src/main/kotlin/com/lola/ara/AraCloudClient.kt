package com.lola.ara

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private object AraTokenVault {
    private const val alias = "ara_cloud_token_v1"
    private const val prefsName = "ara_private_preferences"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun saveValue(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun loadValue(context: Context, name: String): String {
        val packedText = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(name, null) ?: return ""
        return try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun saveToken(context: Context, value: String) = saveValue(context, "cloud_token_enc", value)
    fun loadToken(context: Context): String = loadValue(context, "cloud_token_enc")
    fun saveSession(context: Context, value: String) = saveValue(context, "neon_session_cookie_enc", value)
    fun loadSession(context: Context): String = loadValue(context, "neon_session_cookie_enc")
    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .remove("cloud_token_enc").remove("neon_session_cookie_enc").apply()
    }
}

object AraCloudClient {
    private const val endpoint = "https://ara-api-9p0i.onrender.com/v1/chat"
    private const val memoryEndpoint = "https://ara-api-9p0i.onrender.com/v1/memory"
    private const val authEndpoint = "https://ep-soft-wildflower-bek9u1u8.neonauth.c-9.us-east-2.aws.neon.tech/neondb/auth"

    private fun authCall(path: String, method: String, body: JSONObject? = null, cookie: String? = null): HttpURLConnection {
        val connection = (URL("$authEndpoint/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (cookie != null) setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return connection
    }

    private fun cookiesFrom(connection: HttpURLConnection): String =
        connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value ?: emptyList() }
            .mapNotNull { it.substringBefore(";").takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("; ")

    private fun requestJwt(cookie: String): String {
        val connection = authCall("get-session", "GET", cookie = cookie)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Neon session could not be restored (HTTP $status).")
            return connection.getHeaderField("set-auth-jwt")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Neon Auth did not return a signed API token.")
        } finally { connection.disconnect() }
    }

    fun authenticate(context: Context, email: String, password: String, name: String, createAccount: Boolean): String {
        require(email.isNotBlank() && password.length >= 8) { "Enter your email and a password of at least 8 characters." }
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (createAccount) body.put("name", name.trim().ifBlank { "Ara owner" })
        val connection = authCall(if (createAccount) "sign-up/email" else "sign-in/email", "POST", body)
        val cookie = try {
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = try { JSONObject(responseText).optString("message").ifBlank { JSONObject(responseText).optString("code") } } catch (_: Exception) { "" }
                throw IllegalStateException(detail.ifBlank { "Neon sign-in failed (HTTP $status)." })
            }
            cookiesFrom(connection)
        } finally { connection.disconnect() }
        if (cookie.isBlank()) throw IllegalStateException("Neon Auth did not create a session cookie.")
        val token = requestJwt(cookie)
        AraTokenVault.saveSession(context, cookie)
        AraTokenVault.saveToken(context, token)
        return token
    }

    fun currentToken(context: Context): String {
        val cookie = AraTokenVault.loadSession(context)
        if (cookie.isBlank()) return AraTokenVault.loadToken(context)
        return try {
            val token = requestJwt(cookie)
            AraTokenVault.saveToken(context, token)
            token
        } catch (_: Exception) {
            AraTokenVault.loadToken(context)
        }
    }

    fun signOut(context: Context) = AraTokenVault.clear(context)

    fun send(context: Context, token: String, history: List<Pair<String, String>>): String {
        val activeToken = currentToken(context).ifBlank { token }
        require(activeToken.isNotBlank()) { "Sign in to Neon Auth in Developer settings first." }
        val bodyMessages = JSONArray()
        history.takeLast(30).forEach { (role, content) ->
            bodyMessages.put(JSONObject().put("role", role).put("content", content))
        }
        val payload = JSONObject().put("messages", bodyMessages).toString()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 50000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $activeToken")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val code = try { JSONObject(response).optString("error", "http_$status") } catch (_: Exception) { "http_$status" }
                throw IllegalStateException("Ara cloud error: $code")
            }
            return JSONObject(response).optString("reply").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Ara returned an empty reply.")
        } finally { connection.disconnect() }
    }

    private fun memoryRequest(context: Context, token: String, method: String, notes: String? = null): JSONObject {
        val activeToken = currentToken(context).ifBlank { token }
        require(activeToken.isNotBlank()) { "Sign in to Neon Auth in Developer settings first." }
        val connection = (URL(memoryEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $activeToken")
            if (method == "PUT") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (method == "PUT") {
                val payload = JSONObject().put("notes", notes.orEmpty()).toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val code = try { JSONObject(response).optString("error", "http_$status") } catch (_: Exception) { "http_$status" }
                throw IllegalStateException("Ara memory error: $code")
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally { connection.disconnect() }
    }

    fun loadMemory(context: Context, token: String): String = memoryRequest(context, token, "GET").optString("notes")
    fun saveMemory(context: Context, token: String, notes: String) { memoryRequest(context, token, "PUT", notes) }
    fun deleteMemory(context: Context, token: String) { memoryRequest(context, token, "DELETE") }

    fun saveToken(context: Context, token: String) = AraTokenVault.saveToken(context, token)
    fun loadToken(context: Context): String = AraTokenVault.loadToken(context)
}
