package app.fingernotes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.fingernotes.core.Attachment
import app.fingernotes.data.Repository
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/* ---------- markdown-lite renderer (docs/PROTOCOL.md §4) ---------- */
private val INLINE = Regex("""(\*\*[^*\n]+\*\*|\*[^*\n]+\*|https?://[^\s<>()]+)""")

fun markdownToAnnotated(text: String, link: Color): AnnotatedString = buildAnnotatedString {
    text.split("\n").forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val bullet = Regex("""^\s*[-•]\s+""").find(raw)
        val line = if (bullet != null) { append("  •  "); raw.substring(bullet.range.last + 1) } else raw
        var last = 0
        for (m in INLINE.findAll(line)) {
            append(line.substring(last, m.range.first))
            val t = m.value
            when {
                t.startsWith("**") && t.length > 4 -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
                t.startsWith("*") && t.length > 2 -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
                else -> withLink(LinkAnnotation.Url(t, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(t) }
            }
            last = m.range.last + 1
        }
        append(line.substring(last))
    }
}

@Composable
fun Markdown(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(markdownToAnnotated(text, MaterialTheme.colorScheme.primary), maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
}

/* ---------- QR ---------- */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        runCatching {
            BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 900, 900, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1))
        }.getOrNull()
    }
    if (bmp == null) Text("QR too large — use copy/paste instead", color = MaterialTheme.colorScheme.error)
    else Box(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Image(bmp.asImageBitmap(), "QR code", Modifier.size(260.dp), filterQuality = FilterQuality.None)
    }
}

/* ---------- encrypted media ---------- */
@Composable
fun MediaImage(repo: Repository, id: String, modifier: Modifier = Modifier, maxHeight: Int = 220) {
    val bmp by produceState<Bitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { repo.mediaBytes(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }
    bmp?.let {
        Image(it.asImageBitmap(), null, modifier.fillMaxWidth().heightIn(max = maxHeight.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    } ?: Text("Image (syncing…)", style = MaterialTheme.typography.labelSmall)
}

@Composable
fun AudioChip(repo: Repository, att: Attachment) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    DisposableEffect(att.id) {
        onDispose { player?.release(); file?.delete() }
    }
    var play by remember { mutableStateOf(false) }
    LaunchedEffect(play) {
        if (play) {
            val bytes = withContext(Dispatchers.IO) { repo.mediaBytes(att.id) } ?: run { play = false; return@LaunchedEffect }
            val f = File(ctx.cacheDir, "play-${att.id}").apply { writeBytes(bytes) } // transient plaintext in private cache
            file = f
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare(); start()
                setOnCompletionListener { play = false }
            }
        } else {
            player?.release(); player = null; file?.delete()
        }
    }
    AssistChip(onClick = { play = !play }, label = { Text(att.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        leadingIcon = { Icon(if (play) Icons.Default.Stop else Icons.Default.PlayArrow, null) })
}

/* ---------- PIN entry ---------- */
@Composable
fun PinForm(
    title: String,
    subtitle: String? = null,
    confirm: Boolean = false,
    button: String = "Unlock",
    error: String? = null,
    biometric: (() -> Unit)? = null,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var localErr by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (confirm) Icons.Default.Lock else Icons.Default.Fingerprint, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        OutlinedTextField(
            pin, { pin = it }, label = { Text("PIN or password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.padding(top = 16.dp).widthIn(max = 320.dp).fillMaxWidth(),
        )
        if (confirm) OutlinedTextField(
            pin2, { pin2 = it }, label = { Text("Repeat") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp).fillMaxWidth(),
        )
        (localErr ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            onClick = {
                localErr = null
                if (confirm && pin != pin2) localErr = "PINs do not match" else { onSubmit(pin); pin = "" }
            },
            enabled = pin.length >= 4,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(button) }
        biometric?.let {
            LaunchedEffect(Unit) { it() }
            OutlinedButton(onClick = it, modifier = Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, null); Text("  Use biometrics")
                }
            }
        }
    }
}
