package org.madmail.mobile.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SshCredential(val privateKey: ByteArray, val passphrase: ByteArray)
internal data class ApiCredential(val endpoint: String, val token: String)
internal object ApiEndpoint {
    fun normalize(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port == 0 || uri.port > 65535) return null
        return uri.toASCIIString().trimEnd('/') + if (uri.path in listOf("", "/")) "/api/admin" else ""
    }
}
/** Per-profile envelope encryption. The AES wrapping key never leaves Android Keystore. */
internal class RemoteCredentials(context: Context, private val id: String) {
    private val prefs = context.getSharedPreferences("remote-credentials", Context.MODE_PRIVATE)
    private val alias = "madmail-remote-$id"
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized private fun write(kind: String, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("$id/$kind".toByteArray()) }
        val plain = value.toString().toByteArray()
        try {
            val envelope = JSONObject().put("iv", encoder.encodeToString(cipher.iv)).put("data", encoder.encodeToString(cipher.doFinal(plain)))
            check(prefs.edit().putString("$id/$kind", envelope.toString()).commit()) { "Couldn’t save credentials." }
        } finally { plain.fill(0) }
    }
    @Synchronized private fun read(kind: String): JSONObject? {
        val stored = prefs.getString("$id/$kind", null) ?: return null
        val envelope = JSONObject(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decoder.decode(envelope.getString("iv")))); updateAAD("$id/$kind".toByteArray()) }
        val plain = cipher.doFinal(decoder.decode(envelope.getString("data")))
        return try { JSONObject(String(plain)) } finally { plain.fill(0) }
    }
    fun hasKey() = prefs.contains("$id/ssh")
    fun publicKey(): String? = read("ssh")?.getString("public")
    @Synchronized fun generate(): String {
        publicKey()?.let { return it }
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        try {
            val private = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toByteArray()
            val public = ByteArrayOutputStream().also { pair.writePublicKey(it, "madmail-mobile") }.toString("UTF-8").trim()
            try { write("ssh", JSONObject().put("private", encoder.encodeToString(private)).put("passphrase", "").put("public", public)) }
            finally { private.fill(0) }
            return public
        } finally { pair.dispose() }
    }
    fun importKey(private: ByteArray, passphrase: ByteArray): String {
        require(private.size in 1..65536) { "Choose an SSH private key under 64 KiB." }
        val pair = KeyPair.load(JSch(), private, null)
        try {
            check(!pair.isEncrypted || pair.decrypt(passphrase)) { "Wrong passphrase." }
            val public = ByteArrayOutputStream().also { pair.writePublicKey(it, "madmail-mobile") }.toString("UTF-8").trim()
            write("ssh", JSONObject().put("private", encoder.encodeToString(private)).put("passphrase", encoder.encodeToString(passphrase)).put("public", public))
            return public
        } finally { pair.dispose() }
    }
    fun ssh(): SshCredential {
        val value = checkNotNull(read("ssh")) { "Generate or import an SSH key first." }
        return SshCredential(decoder.decode(value.getString("private")), decoder.decode(value.getString("passphrase")))
    }
    fun saveApi(endpoint: String, token: String) {
        require(token.isNotBlank() && token.length <= 8192 && token.none { it.isISOControl() })
        write("api", JSONObject().put("endpoint", requireNotNull(ApiEndpoint.normalize(endpoint))).put("token", token))
    }
    fun saveTunnelToken(token: String) { require(token.isNotBlank() && token.length <= 8192 && token.none { it.isISOControl() }); write("tunnel", JSONObject().put("token", token)) }
    fun tunnelToken(): String? = read("tunnel")?.getString("token")
    fun api(): ApiCredential? = read("api")?.let { ApiCredential(it.getString("endpoint"), it.getString("token")) }
    fun delete() {
        check(prefs.edit().remove("$id/ssh").remove("$id/api").remove("$id/tunnel").commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
