package com.vps.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** משטח "זכוכית": שקיפות עדינה, מסגרת דקה וזוהר קל — כמו ב-iOS. */
@Composable
fun Modifier.glass(
    radius: Dp = GlassRadius,
    alpha: Float = 1f
): Modifier {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(radius)
    val fill = if (dark)
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f * alpha), Color.White.copy(alpha = 0.04f * alpha))
        )
    else
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f * alpha), Color.White.copy(alpha = 0.60f * alpha))
        )
    val stroke = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f)
    return this
        .clip(shape)
        .background(fill, shape)
        .border(0.8.dp, stroke, shape)
}

/** רקע מדורג עם כתמי אור מטושטשים — נותן לזכוכית מה לשבור. */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (dark) listOf(Ios.bgTop, Ios.bgBottom)
                        else listOf(Ios.lightTop, Ios.lightBottom)
                    )
                )
        )
        Box(
            Modifier
                .offset(x = (-80).dp, y = (-60).dp)
                .size(320.dp)
                .blur(120.dp)
                .background(Ios.blue.copy(alpha = if (dark) 0.35f else 0.22f), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .offset(x = 180.dp, y = 420.dp)
                .size(280.dp)
                .blur(130.dp)
                .background(Ios.purple.copy(alpha = if (dark) 0.28f else 0.18f), RoundedCornerShape(50))
        )
        content()
    }
}
