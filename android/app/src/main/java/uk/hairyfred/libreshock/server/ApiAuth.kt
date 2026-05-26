package uk.hairyfred.libreshock.server

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 *  Token management for the Remote API. A single shared 32-char hex token
 *  is generated on first enable and stored in SharedPreferences. External
 *  callers send it in the `Authorization: Bearer <token>` header.
 *
 *  The token is sufficient on a trusted LAN — anyone who has it can fire
 *  stims, so users are advised to keep it private and only enable the
 *  feature on networks they trust. Documented in Settings → Remote API.
 */
object ApiAuth {
    const val PREF_TOKEN = "api_token"
    const val PREF_ENABLED = "api_enabled"
    const val PREF_PORT = "api_port"
    /** Background alarm notifications: when ON the foreground service stays
     *  alive even with the Remote API off, keeping the BLE alarm-fire
     *  listener running while the app is swiped away. Default OFF — the
     *  cost is a persistent notification icon. */
    const val PREF_BG_ALARMS = "bg_alarms_enabled"
    const val DEFAULT_PORT = 8765

    /** True if any feature that needs the foreground service is enabled. */
    fun shouldRunService(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ENABLED, false) ||
            prefs.getBoolean(PREF_BG_ALARMS, false)

    /** Return the stored token, generating one if none exists yet. Safe to
     *  call from any thread — SharedPreferences writes are atomic. */
    fun getOrCreateToken(prefs: SharedPreferences): String {
        prefs.getString(PREF_TOKEN, null)?.let { return it }
        val fresh = generateToken()
        prefs.edit().putString(PREF_TOKEN, fresh).apply()
        return fresh
    }

    /** Replace the stored token with a fresh random one. Any external
     *  caller using the old token will start getting 401s. */
    fun regenerate(prefs: SharedPreferences): String {
        val fresh = generateToken()
        prefs.edit().putString(PREF_TOKEN, fresh).apply()
        return fresh
    }

    /** Read the configured port, falling back to [DEFAULT_PORT]. */
    fun port(prefs: SharedPreferences): Int =
        prefs.getInt(PREF_PORT, DEFAULT_PORT)

    /** Constant-time-ish comparison of a candidate header against the
     *  stored token. Avoids early-exit timing leaks. */
    fun isAuthorized(prefs: SharedPreferences, authHeader: String?): Boolean {
        if (authHeader == null) return false
        val prefix = "Bearer "
        if (!authHeader.startsWith(prefix)) return false
        val supplied = authHeader.substring(prefix.length).trim()
        val expected = prefs.getString(PREF_TOKEN, null) ?: return false
        if (supplied.length != expected.length) return false
        var diff = 0
        for (i in supplied.indices) {
            diff = diff or (supplied[i].code xor expected[i].code)
        }
        return diff == 0
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Convenience for screens that need the libreshock prefs without
     *  knowing the file name. */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("libreshock", Context.MODE_PRIVATE)
}
