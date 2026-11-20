package dev.abelman.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TvDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val properties: Map<String, String> = emptyMap(),
    val credentials: String = "",
) {
    fun json() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("address", address)
        put("port", port)
        put("properties", JSONObject(properties))
        put("credentials", credentials)
    }

    companion object {
        fun from(json: JSONObject): TvDevice {
            val properties = json.optJSONObject("properties") ?: JSONObject()
            return TvDevice(
                json.getString("id"), json.getString("name"), json.getString("address"),
                json.getInt("port"), properties.keys().asSequence().associateWith { properties.getString(it) },
                json.optString("credentials"),
            )
        }
    }
}

/** Pairing keys never leave app-private storage and are encrypted by Android Keystore. */
class DeviceStore(context: Context) {
    private val preferences = context.getSharedPreferences("paired_devices", Context.MODE_PRIVATE)
    private val alias = "apple_tv_pairings_v1"

    var activeId: String?
        get() = preferences.getString("active", null)
        set(value) { preferences.edit().putString("active", value).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun load(): List<TvDevice> {
        val encrypted = preferences.getString("devices", null) ?: return emptyList()
        val iv = preferences.getString("iv", null) ?: error("Missing device-store IV")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val json = JSONArray(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        return (0 until json.length()).map { TvDevice.from(json.getJSONObject(it)) }
    }

    fun save(devices: Collection<TvDevice>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = JSONArray(devices.map { it.json() }).toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(data)
        check(preferences.edit()
            .putString("devices", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Could not persist pairing" }
    }
}
