package uk.hairyfred.libreshock.ui

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of a "is there a newer release?" check against GitHub. */
data class UpdateInfo(
    val latestTag: String,    // e.g. "v0.1.9"
    val htmlUrl: String,      // e.g. "https://github.com/.../releases/tag/v0.1.9"
    val isNewer: Boolean,     // latestTag > current versionName?
)

/** Why a check failed — used so the UI can show a tailored hint. */
enum class UpdateCheckError {
    /** UnknownHostException etc. — either the device has no network or the
     *  user has blocked LibreShock from network access in App info. */
    NoNetwork,
    /** Anything else: timeout, parse error, non-200 response. */
    Other,
}

sealed interface UpdateCheckResult {
    data class Ok(val info: UpdateInfo) : UpdateCheckResult
    data class Failed(val error: UpdateCheckError) : UpdateCheckResult
}

/**
 *  Checks GitHub's "latest release" endpoint for the LibreShock repo and
 *  compares it against the running APK's [BuildConfig.VERSION_NAME].
 *
 *  No data is sent to GitHub — it's a plain GET to a public REST endpoint.
 *  The check is rate-limited to once every 24h via SharedPreferences so
 *  even users with the setting on won't hammer the API.
 */
object UpdateChecker {
    private const val OWNER = "hairyfred"
    private const val REPO = "Libreshock"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$OWNER/$REPO/releases"

    /** Pref key for "check automatically" toggle. Default OFF — the user
     *  has to opt in, since update checks need network access which some
     *  users block at the system level. */
    const val PREF_AUTO_CHECK = "update_check_enabled"
    const val AUTO_CHECK_DEFAULT = false

    /** Pref key for the unix-ms timestamp of the last successful check. */
    private const val PREF_LAST_CHECK_MS = "update_last_check_ms"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000  // 24h

    /** True if the user opted in AND we last checked > 24h ago. */
    fun shouldAutoCheck(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(PREF_AUTO_CHECK, AUTO_CHECK_DEFAULT)) return false
        val last = prefs.getLong(PREF_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    /** Force a fresh check on the next call regardless of timing. */
    fun resetLastCheck(prefs: SharedPreferences) {
        prefs.edit().remove(PREF_LAST_CHECK_MS).apply()
    }

    /** Compare two version strings like "0.1.9" / "v0.2.0". Returns 1 if a>b,
     *  -1 if a<b, 0 if equal. Tolerates extra "-rc1" / "+meta" suffixes. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> = v
            .removePrefix("v")
            .split('-', '+').first()
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val ai = pa.getOrElse(i) { 0 }
            val bi = pb.getOrElse(i) { 0 }
            if (ai != bi) return if (ai > bi) 1 else -1
        }
        return 0
    }

    /** GET the latest-release JSON, parse out tag_name + html_url, compare
     *  against [currentVersion]. Returns null on any network / parse error
     *  (the UI just stays quiet — never blocks anything). */
    suspend fun check(
        currentVersion: String,
        prefs: SharedPreferences,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LibreShock/$currentVersion")
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "connection setup failed", e)
            return@withContext UpdateCheckResult.Failed(classify(e))
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w("UpdateChecker", "HTTP $code from $API_URL")
                return@withContext UpdateCheckResult.Failed(UpdateCheckError.Other)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").ifEmpty {
                Log.w("UpdateChecker", "no tag_name in response")
                return@withContext UpdateCheckResult.Failed(UpdateCheckError.Other)
            }
            val html = json.optString("html_url", RELEASES_PAGE_URL)
            val newer = compareVersions(tag, currentVersion) > 0
            prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply()
            Log.i("UpdateChecker",
                "latest=$tag current=$currentVersion newer=$newer")
            UpdateCheckResult.Ok(UpdateInfo(latestTag = tag, htmlUrl = html, isNewer = newer))
        } catch (e: Exception) {
            Log.w("UpdateChecker",
                "read/parse failed: ${e.javaClass.simpleName}: ${e.message}", e)
            UpdateCheckResult.Failed(classify(e))
        } finally {
            conn.disconnect()
        }
    }

    private fun classify(e: Exception): UpdateCheckError = when (e) {
        is java.net.UnknownHostException -> UpdateCheckError.NoNetwork
        is java.net.SocketTimeoutException -> UpdateCheckError.NoNetwork
        is java.net.SocketException -> UpdateCheckError.NoNetwork
        else -> UpdateCheckError.Other
    }
}
