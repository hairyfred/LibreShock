package uk.hairyfred.libreshock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Full-screen overlay shown when the watch reports an alarm is firing
 * (NotifyOpcode.ALARM_FIRING). The user can stop or snooze.
 *
 * Not dismissable by tap-outside or back press — has to be resolved via one
 * of the two buttons (or by stopping/snoozing on the watch itself, which
 * will arrive as a 0x55/0x56 notification and clear the firingAlarmId state).
 *
 * When [requiresQrScan] is true, the Stop button is replaced with a "Scan
 * QR to stop" button that launches the camera. The watch only stops if the
 * scanned QR content exactly matches [ALARM_QR_CONTENT].
 */
/** Origin hint passed back to [onStop] so the celebration burst can fire
 *  from somewhere visually connected to the user's action — Stop button
 *  for direct dismissal, middle of the screen for a scan or puzzle. */
enum class StopOrigin { BUTTON, QR_SCAN, PUZZLE }

@Composable
fun AlarmFiringDialog(
    alarmId: Int,
    requiresQrScan: Boolean,
    requiresPuzzle: Boolean,
    onStop: (StopOrigin) -> Unit,
    onSnooze: () -> Unit,
    onOpenPuzzle: () -> Unit,
) {
    var scanError by remember { mutableStateOf<String?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        when {
            content == null -> { /* user cancelled, no-op */ }
            content == ALARM_QR_CONTENT -> {
                scanError = null
                onStop(StopOrigin.QR_SCAN)
            }
            else -> scanError = "Wrong QR code. Scan the LibreShock alarm QR."
        }
    }

    Dialog(
        onDismissRequest = { /* ignore */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Alarm firing",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        requiresQrScan -> "Alarm #$alarmId is firing. Scan the LibreShock QR to stop."
                        requiresPuzzle -> "Alarm #$alarmId is firing. Solve a puzzle to stop."
                        else -> "Alarm #$alarmId is firing on the watch"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                scanError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(24.dp))
                // Stacked full-width buttons — easier to hit at wake-up time
                // and avoids the "Solve puzzle" / "Scan QR" labels getting
                // squeezed onto two lines next to a short "Snooze".
                Button(
                    onClick = {
                        when {
                            requiresQrScan -> {
                                val options = ScanOptions().apply {
                                    setPrompt("Scan the LibreShock alarm QR")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setCaptureActivity(PortraitCaptureActivity::class.java)
                                }
                                scanLauncher.launch(options)
                            }
                            requiresPuzzle -> onOpenPuzzle()
                            else -> onStop(StopOrigin.BUTTON)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        when {
                            requiresQrScan -> "Scan QR"
                            requiresPuzzle -> "Solve puzzle"
                            else -> "Stop"
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Snooze") }
            }
        }
    }
}
