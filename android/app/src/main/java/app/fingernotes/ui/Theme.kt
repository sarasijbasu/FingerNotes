package app.fingernotes.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Amber = Color(0xFFC77700)

@Composable
fun FingerNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFF5A623), onPrimary = Color(0xFF1B1400))
        else -> lightColorScheme(primary = Amber, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Keep-style note palette, matching the --n-* CSS tokens in the Chrome extension. */
fun noteColor(name: String, dark: Boolean): Color? = when (name) {
    "red" -> if (dark) Color(0xFF77172E) else Color(0xFFFAAFA8)
    "orange" -> if (dark) Color(0xFF692B17) else Color(0xFFF39F76)
    "yellow" -> if (dark) Color(0xFF7C4A03) else Color(0xFFFFF8B8)
    "green" -> if (dark) Color(0xFF264D3B) else Color(0xFFE2F6D3)
    "teal" -> if (dark) Color(0xFF0C625D) else Color(0xFFB4DDD3)
    "blue" -> if (dark) Color(0xFF256377) else Color(0xFFD4E4ED)
    "purple" -> if (dark) Color(0xFF472E5B) else Color(0xFFD3BFDB)
    "pink" -> if (dark) Color(0xFF6C394F) else Color(0xFFF6E2DD)
    "brown" -> if (dark) Color(0xFF4B443A) else Color(0xFFE9E3D4)
    "gray" -> if (dark) Color(0xFF232427) else Color(0xFFEFEFF1)
    else -> null
}

fun parseColor(css: String): Color = runCatching {
    when {
        css.startsWith("#") -> Color(android.graphics.Color.parseColor(css))
        css.startsWith("hsl") -> {
            val n = Regex("[\\d.]+").findAll(css).map { it.value.toFloat() }.toList()
            Color.hsl(n[0] % 360f, n[1] / 100f, n[2] / 100f)
        }
        else -> Color.Gray
    }
}.getOrDefault(Color.Gray)
