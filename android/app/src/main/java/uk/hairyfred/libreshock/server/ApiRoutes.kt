package uk.hairyfred.libreshock.server

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import org.json.JSONObject
import uk.hairyfred.libreshock.ble.ConnectionState
import uk.hairyfred.libreshock.ble.DeviceInfo
import uk.hairyfred.libreshock.ble.ShockDevice

/**
 *  Ktor routes for the Remote API. Mirrors a subset of the in-app instant
 *  actions and the alarm-stop action. Auth, JSON parsing, BLE-not-connected
 *  handling and parameter validation all live here.
 *
 *  Endpoints (v1):
 *    GET  /api/v1/status      → {connected, battery, watch_model, ...}
 *    POST /api/v1/vibrate     {"intensity": 0-100}
 *    POST /api/v1/beep        {"intensity": 0-100}
 *    POST /api/v1/zap         {"intensity": 0-100}
 *    POST /api/v1/alarm/stop  (no body)
 *    POST /api/v1/burst       {"action": vibrate|beep|zap, "intensity":
 *                              0-100, "count": 1-50, "gap_ms": 50-5000}
 *
 *  Each call to vibrate/beep/zap fires ONE action. The watch ignores
 *  count/timing bytes in the instant-action protocol (verified May
 *  2026), so the API doesn't pretend to support them. For multi-pulse,
 *  use /burst (server-side loop) or call the single endpoint N times.
 *
 *  All require `Authorization: Bearer <token>`. JSON responses with shape
 *  `{"ok": true}` on success, `{"error": "..."}` otherwise.
 */
fun Application.installRemoteApiRoutes(context: Context, device: ShockDevice) {
    routing {
        route("/api/v1") {
            post("/vibrate") {
                if (!checkAuth(call, context)) return@post
                if (!checkConnected(call, device)) return@post
                val intensity = parseIntensity(call) ?: return@post
                device.vibrate(intensity = intensity)
                call.respondJson(HttpStatusCode.OK, "ok" to true)
            }
            post("/beep") {
                if (!checkAuth(call, context)) return@post
                if (!checkConnected(call, device)) return@post
                val intensity = parseIntensity(call) ?: return@post
                device.beep(intensity = intensity)
                call.respondJson(HttpStatusCode.OK, "ok" to true)
            }
            post("/zap") {
                if (!checkAuth(call, context)) return@post
                if (!checkConnected(call, device)) return@post
                val intensity = parseIntensity(call) ?: return@post
                device.zap(intensity = intensity)
                call.respondJson(HttpStatusCode.OK, "ok" to true)
            }
            post("/alarm/stop") {
                if (!checkAuth(call, context)) return@post
                if (!checkConnected(call, device)) return@post
                device.stopAlarm()
                call.respondJson(HttpStatusCode.OK, "ok" to true)
            }
            get("/status") {
                if (!checkAuth(call, context)) return@get
                val connected = device.connectionState.value is ConnectionState.Connected
                val battery = device.batteryUpdates.replayCache.firstOrNull()
                // Cache the device-info reads; they never change at
                // runtime so one BLE read on first /status is enough.
                val info: DeviceInfo? = if (connected) {
                    statusInfoCache ?: runCatching {
                        device.readDeviceInfo(deviceName = null)
                    }.getOrNull()?.also { statusInfoCache = it }
                } else null
                call.respondJson(
                    HttpStatusCode.OK,
                    "connected" to connected,
                    "battery" to (battery ?: JSONObject.NULL),
                    "watch_model" to (info?.model ?: JSONObject.NULL),
                    "ble_name" to (info?.name ?: JSONObject.NULL),
                    "firmware" to (info?.firmwareRevision ?: JSONObject.NULL),
                )
            }
            post("/burst") {
                if (!checkAuth(call, context)) return@post
                if (!checkConnected(call, device)) return@post
                val body = call.receiveText()
                val json = try { JSONObject(body) } catch (_: Exception) {
                    call.respondJson(HttpStatusCode.BadRequest, "error" to "invalid JSON")
                    return@post
                }
                val actionName = json.optString("action", "")
                if (actionName !in setOf("vibrate", "beep", "zap")) {
                    call.respondJson(HttpStatusCode.BadRequest,
                        "error" to "action must be one of vibrate / beep / zap")
                    return@post
                }
                val intensity = json.optInt("intensity", -1)
                val count = json.optInt("count", 1)
                val gapMs = json.optInt("gap_ms", 200)
                if (intensity !in 0..100) {
                    call.respondJson(HttpStatusCode.BadRequest,
                        "error" to "intensity must be 0-100"); return@post
                }
                if (count !in 1..50) {
                    call.respondJson(HttpStatusCode.BadRequest,
                        "error" to "count must be 1-50"); return@post
                }
                if (gapMs !in 50..5000) {
                    call.respondJson(HttpStatusCode.BadRequest,
                        "error" to "gap_ms must be 50-5000"); return@post
                }
                repeat(count) { i ->
                    if (i > 0) delay(gapMs.toLong())
                    when (actionName) {
                        "vibrate" -> device.vibrate(intensity = intensity)
                        "beep" -> device.beep(intensity = intensity)
                        "zap" -> device.zap(intensity = intensity)
                    }
                }
                call.respondJson(HttpStatusCode.OK, "ok" to true, "fired" to count)
            }
        }
    }
}

/** Module-level cache for device-info strings (model, ble_name, firmware).
 *  Never changes at runtime so we only read once. Reset on app process
 *  death — that's fine, the next /status will refill it. */
private var statusInfoCache: DeviceInfo? = null

/** Returns true if the token matches; otherwise responds 401 and returns false. */
private suspend fun checkAuth(call: ApplicationCall, context: Context): Boolean {
    val header = call.request.headers["Authorization"]
    if (ApiAuth.isAuthorized(ApiAuth.prefs(context), header)) return true
    call.respondJson(HttpStatusCode.Unauthorized, "error" to "auth")
    return false
}

/** Returns true if the watch is connected; otherwise responds 503 + false. */
private suspend fun checkConnected(call: ApplicationCall, device: ShockDevice): Boolean {
    if (device.connectionState.value is ConnectionState.Connected) return true
    call.respondJson(HttpStatusCode.ServiceUnavailable, "error" to "not connected")
    return false
}

private suspend fun parseIntensity(call: ApplicationCall): Int? {
    val body = call.receiveText()
    val json = try { JSONObject(body) } catch (_: Exception) {
        call.respondJson(HttpStatusCode.BadRequest, "error" to "invalid JSON")
        return null
    }
    val intensity = json.optInt("intensity", -1)
    if (intensity !in 0..100) {
        call.respondJson(HttpStatusCode.BadRequest,
            "error" to "intensity must be 0-100")
        return null
    }
    return intensity
}

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode, vararg fields: Pair<String, Any>,
) {
    val obj = JSONObject()
    for ((k, v) in fields) obj.put(k, v)
    respondText(text = obj.toString(),
        contentType = ContentType.Application.Json, status = status)
}
