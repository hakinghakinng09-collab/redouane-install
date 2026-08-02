package com.redouaneinstall.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Brand {
    val Red = Color(0xFFE53935)
    val RedDark = Color(0xFFB71C1C)
    val Bg = Color(0xFF0E0E10)
    val Surface = Color(0xFF19191E)
    val Surface2 = Color(0xFF232329)
    val OnBg = Color(0xFFF2F2F4)
    val Muted = Color(0xFF9C9CA6)
    val Green = Color(0xFF4CAF50)
    val GradientHeader = Brush.linearGradient(listOf(Color(0xFFB71C1C), Color(0xFFE53935)))
}

private val scheme = darkColorScheme(
    primary = Brand.Red,
    onPrimary = Color.White,
    secondary = Color(0xFFFFAB91),
    background = Brand.Bg,
    surface = Brand.Surface,
    surfaceVariant = Brand.Surface2,
    onBackground = Brand.OnBg,
    onSurface = Brand.OnBg,
    onSurfaceVariant = Brand.Muted
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
