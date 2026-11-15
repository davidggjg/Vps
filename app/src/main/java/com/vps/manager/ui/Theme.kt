package com.vps.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** פלטת צבעים בהשפעת iOS — כחול מערכת, אפורים רכים, שחור עמוק. */
object Ios {
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val orange = Color(0xFFFF9F0A)
    val purple = Color(0xFFBF5AF2)
    val gray = Color(0xFF8E8E93)

    val bgTop = Color(0xFF0B0E14)
    val bgBottom = Color(0xFF05070C)
    val lightTop = Color(0xFFF2F3F7)
    val lightBottom = Color(0xFFE6E9F2)
}

val GlassRadius = 24.dp

private val darkScheme = darkColorScheme(
    primary = Ios.blue,
    onPrimary = Color.White,
    background = Ios.bgBottom,
    onBackground = Color(0xFFF5F6FA),
    surface = Color(0xFF12151D),
    onSurface = Color(0xFFF5F6FA),
    onSurfaceVariant = Color(0xFFB9BCC6),
    error = Ios.red
)

private val lightScheme = lightColorScheme(
    primary = Ios.blue,
    onPrimary = Color.White,
    background = Ios.lightBottom,
    onBackground = Color(0xFF0B0E14),
    surface = Color.White,
    onSurface = Color(0xFF0B0E14),
    onSurfaceVariant = Color(0xFF53565E),
    error = Ios.red
)

private val typography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp)
)

/** גודל גופן המסוף שהמשתמש בחר (ניתן לשינוי מהתפריט). */
val LocalTerminalFontSize = androidx.compose.runtime.compositionLocalOf { 13f }

/** סגנון המסוף — מתעדכן לפי הבחירה של המשתמש. */
val MonoStyle: TextStyle
    @Composable get() {
        val size = LocalTerminalFontSize.current
        return TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = size.sp,
            lineHeight = (size * 1.45f).sp
        )
    }

/** כל האפליקציה בעברית ומימין לשמאל, גם אם הטלפון באנגלית. */
@Composable
fun VpsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // מכבדים את גודל הגופן של הטלפון, אבל מגבילים אותו כדי שהמסך לא יישבר.
    val d = LocalDensity.current
    val safeDensity = Density(d.density, d.fontScale.coerceIn(0.85f, 1.15f))
    MaterialTheme(
        colorScheme = if (dark) darkScheme else lightScheme,
        typography = typography
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalDensity provides safeDensity
        ) {
            content()
        }
    }
}
