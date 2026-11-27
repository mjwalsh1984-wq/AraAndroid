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

    fun save(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("cloud_token_enc", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): String {
        val packedText = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString("cloud_token_enc", null) ?: return ""
        return try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

object AraCloudClient {
    private const val endpoint = "https://ara-api-9p0i.onrender.com/v1/chat"

    fun send(context: Context, token: String, history: List<Pair<String, String>>): String {
        require(token.isNotBlank()) { "Add the cloud connection token in Developer settings first." }
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
            setRequestProperty("Authorization", "Bearer $token")
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

    fun saveToken(context: Context, token: String) = AraTokenVault.save(context, token)
    fun loadToken(context: Context): String = AraTokenVault.load(context)
}
