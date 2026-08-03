package com.redouaneinstall.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val scale = remember { Animatable(1.2f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(800, delayMillis = 200))
        delay(2400)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(Color(0xFF4A0A0A), Color(0xFF1A0505), Color(0xFF0E0E10)),
                center = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "REDUANE",
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFF2D2D),
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.scale(scale.value)
            )
            Text(
                "EL MOUKHTATIFI",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFD6D6),
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Box(
                modifier = Modifier.padding(top = 32.dp).fillMaxSize(0.35f)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFFF2D2D), Color.Transparent)))
            )
        }
    }
}
