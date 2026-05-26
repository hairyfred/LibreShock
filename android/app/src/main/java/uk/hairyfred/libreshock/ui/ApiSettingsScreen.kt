package uk.hairyfred.libreshock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.hairyfred.libreshock.server.ApiAuth
import uk.hairyfred.libreshock.server.RemoteApiService
import java.net.NetworkInterface

/**
 *  Sub-screen reached from Settings → Remote API. Holds all controls for
 *  the optional HTTP server: enable toggle, port number, token display
 *  (with copy and regenerate), plus a worked example showing how to call
 *  the API from another device. Server lifecycle is driven entirely by
 *  the toggle — flipping it on starts the foreground service, flipping
 *  it off stops it.
 */
@Composable
fun ApiSettingsScreen(
    padding: PaddingValues,
) {
    val context = LocalContext.current
    val prefs = remember { ApiAuth.prefs(context) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(ApiAuth.PREF_ENABLED, false))
    }
    var portText by remember {
        mutableStateOf(prefs.getInt(ApiAuth.PREF_PORT, ApiAuth.DEFAULT_PORT).toString())
    }
    var token by remember { mutableStateOf(ApiAuth.getOrCreateToken(prefs)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ----- Enable toggle -----
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Enable Remote API", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs a small HTTP server on this phone that lets " +
                            "other devices on your network trigger vibrate, " +
                            "beep, zap and alarm-stop on the watch. Off by " +
                            "default. While on, Android shows a persistent " +
                            "notification so the BLE connection survives the " +
                            "app being backgrounded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { wantOn ->
                    enabled = wantOn
                    prefs.edit().putBoolean(ApiAuth.PREF_ENABLED, wantOn).apply()
                    if (ApiAuth.shouldRunService(prefs)) {
                        // Restart so the service re-reads prefs and
                        // (de)activates the Ktor server side accordingly,
                        // even if the bg-alarms duty is what's keeping it
                        // alive in the other state.
                        RemoteApiService.stop(context)
                        RemoteApiService.start(context)
                    } else {
                        RemoteApiService.stop(context)
                    }
                })
            }
        }

        // ----- Port -----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Port", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Local port the server binds to. Default 8765. Changes " +
                        "take effect next time you enable the toggle.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { new ->
                        portText = new.filter { it.isDigit() }.take(5)
                        portText.toIntOrNull()?.let { p ->
                            if (p in 1..65535) {
                                prefs.edit().putInt(ApiAuth.PREF_PORT, p).apply()
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }

        // ----- Token -----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Auth token", style = MaterialTheme.typography.titleMedium)
                Text(
                    "External callers send this in the " +
                        "`Authorization: Bearer <token>` header. Keep it " +
                        "private — anyone with it can fire stims while " +
                        "the server is running.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    token,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        copyToClipboard(context, "LibreShock API token", token)
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        token = ApiAuth.regenerate(prefs)
                    }) { Text("Regenerate") }
                }
            }
        }

        // ----- How to use -----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("How to use", style = MaterialTheme.typography.titleMedium)
                val ip = remember { detectWifiIp() }
                val port = prefs.getInt(ApiAuth.PREF_PORT, ApiAuth.DEFAULT_PORT)
                val host = ip ?: "<phone-ip>"
                Text(
                    "From another device on the same wifi:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "# Linux / macOS / Git Bash:\n" +
                        "curl -X POST http://$host:$port/api/v1/vibrate " +
                        "\\\n  -H 'Authorization: Bearer $token' " +
                        "\\\n  -H 'Content-Type: application/json' " +
                        "\\\n  -d '{\"intensity\": 50}'",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text(
                    "# PowerShell — note Invoke-RestMethod, not curl.\n" +
                        "# Bare 'curl' is an alias for Invoke-WebRequest\n" +
                        "# with different flags. curl.exe also works but its\n" +
                        "# JSON-body quoting on Windows is finicky.\n" +
                        "Invoke-RestMethod -Method Post `\n" +
                        "  -Uri http://$host:$port/api/v1/vibrate `\n" +
                        "  -Headers @{ Authorization='Bearer $token' } `\n" +
                        "  -ContentType 'application/json' `\n" +
                        "  -Body '{\"intensity\": 50}'",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text(
                    "Endpoints:\n" +
                        "  GET  /api/v1/status      — connection, battery, model\n" +
                        "  POST /api/v1/vibrate     {intensity 0-100}\n" +
                        "  POST /api/v1/beep        {intensity 0-100}\n" +
                        "  POST /api/v1/zap         {intensity 0-100}\n" +
                        "  POST /api/v1/alarm/stop  (no body)\n" +
                        "  POST /api/v1/burst       {action, intensity, count, gap_ms}\n" +
                        "\nFull spec: docs/API.md in the project repo.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                if (ip == null) {
                    Text(
                        "Your phone's wifi IP couldn't be detected automatically " +
                            "— check Settings → Network & internet → your wifi " +
                            "for the address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ----- Security caveat -----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Security note", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The server binds to all network interfaces. Token auth " +
                        "is the only thing stopping anyone on the same network " +
                        "from firing stims. Only enable this on networks you " +
                        "trust (home wifi). Don't enable on public wifi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

/** Best-effort detection of this phone's LAN IPv4 address. Returns null if
 *  not on a network. */
private fun detectWifiIp(): String? {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                // Take the first IPv4 we find — skip IPv6 link-local etc.
                if (host.contains(':')) continue
                return host
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
