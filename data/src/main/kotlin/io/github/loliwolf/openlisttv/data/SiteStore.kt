package io.github.loliwolf.openlisttv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.loliwolf.openlisttv.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SiteStore(context: Context) {
    private val prefs = context.getSharedPreferences("sites", Context.MODE_PRIVATE)
    private val vault = CredentialVault(context)
    fun sites(): List<Site> = runCatching {
        val list = JSONArray(prefs.getString("list", "[]"))
        (0 until list.length()).map { i -> list.getJSONObject(i).let { Site(it.getString("id"), it.getString("label"), it.getString("url"), it.getString("username"), it.optBoolean("http")) } }
    }.getOrDefault(emptyList())
    fun save(session: Session) {
        vault.put(session.site.id, session.token)
        val all = sites().filterNot { it.id == session.site.id } + session.site
        val array = JSONArray()
        all.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("url", it.url).put("username", it.username).put("http", it.allowHttp)) }
        prefs.edit().putString("list", array.toString()).putString("active", session.site.id).apply()
    }
    fun session(site: Site): Session? = vault.get(site.id)?.let { Session(site, it) }
    fun active(): Site? = sites().find { it.id == prefs.getString("active", "") }
    fun select(site: Site) { prefs.edit().putString("active", site.id).apply() }
    fun signOut(site: Site) { vault.remove(site.id) }
    fun remove(site: Site) {
        signOut(site)
        val array = JSONArray(prefs.getString("list", "[]"))
        val kept = JSONArray()
        for (i in 0 until array.length()) if (array.getJSONObject(i).getString("id") != site.id) kept.put(array.getJSONObject(i))
        prefs.edit().putString("list", kept.toString()).remove("active").apply()
    }
    fun sort(site: Site): SortOrder = runCatching { SortOrder(SortField.valueOf(prefs.getString("${site.id}.sort", "NAME")!!), prefs.getBoolean("${site.id}.descending", false), prefs.getBoolean("${site.id}.folders", true)) }.getOrDefault(SortOrder())
    fun setSort(site: Site, order: SortOrder) { prefs.edit().putString("${site.id}.sort", order.field.name).putBoolean("${site.id}.descending", order.descending).putBoolean("${site.id}.folders", order.foldersFirst).apply() }
    fun slideshow(): SlideshowOptions = runCatching {
        SlideshowOptions(TraversalMode.valueOf(prefs.getString("slide.mode", "ORDERED")!!), prefs.getBoolean("slide.recursive", true), prefs.getBoolean("slide.images", false), prefs.getInt("slide.imageSeconds", 8), prefs.getInt("slide.videoSeconds", 0), prefs.getInt("slide.audioSeconds", 0), prefs.getBoolean("slide.prefetch", true))
    }.getOrDefault(SlideshowOptions())
    fun setSlideshow(o: SlideshowOptions) { prefs.edit().putString("slide.mode", o.mode.name).putBoolean("slide.recursive", o.recursive).putBoolean("slide.images", o.imageOnly).putInt("slide.imageSeconds", o.imageSeconds).putInt("slide.videoSeconds", o.videoSeconds).putInt("slide.audioSeconds", o.audioSeconds).putBoolean("slide.prefetch", o.prefetch).apply() }
}

private class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("sessions.encrypted", Context.MODE_PRIVATE)
    private val alias = "openlisttv.sessions.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(id: String, token: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray()) }
            val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(data, Base64.NO_WRAP)
            if (!prefs.edit().putString(id, encoded).commit()) throw IllegalStateException()
        } catch (_: Exception) { throw ClientFailure(FailureKind.ACCESS, "无法安全保存会话，请检查设备密钥与存储") }
    }
    fun get(id: String): String? {
        val encoded = prefs.getString(id, null) ?: return null
        return try {
            val parts = encoded.split('.')
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                updateAAD(id.toByteArray())
            }.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { remove(id); null }
    }
    fun remove(id: String) { prefs.edit().remove(id).commit() }
}
