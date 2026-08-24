package no.stink.bydshare

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Connection + auth settings, stored encrypted. Secrets (CF service-token secret,
 * OverDrive device token) never leave the device and never touch git.
 *
 * Edge auth is pluggable so the app can ship publicly: most users run "None"
 * (a host with no Cloudflare Access, or a LAN address); those behind CF Access
 * pick "Cloudflare service token". Room to add more flows later.
 */
object Settings {

    enum class EdgeAuthType { NONE, CLOUDFLARE }

    private const val FILE = "byd-share-secure"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var baseUrl: String
        get() = prefs.getString("baseUrl", "").orEmpty()
        set(v) = prefs.edit().putString("baseUrl", v.trim().trimEnd('/')).apply()

    var edgeAuth: EdgeAuthType
        get() = runCatching { EdgeAuthType.valueOf(prefs.getString("edgeAuth", "NONE")!!) }
            .getOrDefault(EdgeAuthType.NONE)
        set(v) = prefs.edit().putString("edgeAuth", v.name).apply()

    var cfClientId: String
        get() = prefs.getString("cfClientId", "").orEmpty()
        set(v) = prefs.edit().putString("cfClientId", v.trim()).apply()

    var cfClientSecret: String
        get() = prefs.getString("cfClientSecret", "").orEmpty()
        set(v) = prefs.edit().putString("cfClientSecret", v.trim()).apply()

    /** The 8-char OverDrive access code (secret). The deviceId is fetched from the car. */
    var accessCode: String
        get() = prefs.getString("accessCode", "").orEmpty()
        set(v) = prefs.edit().putString("accessCode", v.trim()).apply()

    /**
     * Skip authentication entirely: no access-code/JWT flow, no edge auth. For running
     * against a trusted, unauthenticated endpoint — chiefly OverDrive's loopback HTTP
     * server when this app runs ON the head unit (baseUrl http://127.0.0.1:8080). The
     * tunnel path (phone) leaves this off.
     */
    var noAuth: Boolean
        get() = prefs.getBoolean("noAuth", false)
        set(v) = prefs.edit().putBoolean("noAuth", v).apply()

    /** Enough configured to reach the car. */
    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && (noAuth ||
            (accessCode.isNotEmpty() &&
                (edgeAuth == EdgeAuthType.NONE ||
                    (cfClientId.isNotEmpty() && cfClientSecret.isNotEmpty()))))
}
