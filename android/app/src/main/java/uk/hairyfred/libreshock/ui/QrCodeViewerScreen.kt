package uk.hairyfred.libreshock.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import uk.hairyfred.libreshock.R
import java.io.File
import java.io.FileOutputStream

/** Raw GitHub URL for the bundled QR PNG. Opening it in a browser triggers
 *  a direct download since GitHub serves raw.githubusercontent.com with
 *  Content-Disposition: attachment. */
private const val QR_GITHUB_RAW_URL =
    "https://raw.githubusercontent.com/hairyfred/Libreshock/main/docs/images/libreshock-qr.png"

/**
 *  Shows the bundled LibreShock alarm-stop QR code as a dialog overlay
 *  so the underlying alarm-edit form state is preserved when the user
 *  dismisses it. Three actions: print/share via local file, download via
 *  browser (raw GitHub URL), and close.
 */
@Composable
fun QrCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LibreShock alarm QR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Print this QR and stick it somewhere you'd rather not visit at " +
                        "wake-up time. The app will only stop a QR-guarded alarm if " +
                        "it scans this exact code.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Note: this is the LibreShock QR. It's different from the QR the " +
                        "official Pavlok app uses — scanning the Pavlok QR with " +
                        "LibreShock won't stop the alarm, and vice versa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Image(
                    painter = painterResource(id = R.drawable.libreshock_qr),
                    contentDescription = "LibreShock alarm QR code",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(12.dp),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { shareQrPng(context) }) { Text("Print / share") }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(QR_GITHUB_RAW_URL))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }) { Text("Download") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun shareQrPng(context: android.content.Context) {
    val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(
        context.resources, R.drawable.libreshock_qr, null,
    ) ?: return
    val side = listOf(drawable.intrinsicWidth, drawable.intrinsicHeight, 512).max()
    val bitmap = android.graphics.Bitmap.createBitmap(
        side, side, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)

    val dir = File(context.cacheDir, "qr").apply { mkdirs() }
    val file = File(dir, "libreshock-qr.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "LibreShock alarm QR")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share or print QR")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    )
}
