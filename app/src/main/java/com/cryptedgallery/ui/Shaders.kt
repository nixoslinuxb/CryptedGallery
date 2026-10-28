package com.cryptedgallery.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import org.intellij.lang.annotations.Language

@Language("AGSL")
private const val MESH_GRADIENT_SHADER = """
    uniform float2 iResolution;
    uniform float iTime;
    layout(color) uniform half4 color1;
    layout(color) uniform half4 color2;
    layout(color) uniform half4 color3;
    layout(color) uniform half4 color4;

    half4 main(in float2 fragCoord) {
        float2 uv = fragCoord / iResolution.xy;
        
        // Create moving centers for each color blob
        float2 p1 = vec2(0.5 + 0.3 * sin(iTime * 0.5), 0.5 + 0.3 * cos(iTime * 0.3));
        float2 p2 = vec2(0.5 + 0.3 * cos(iTime * 0.2), 0.2 + 0.2 * sin(iTime * 0.7));
        float2 p3 = vec2(0.2 + 0.4 * sin(iTime * 0.4), 0.8 + 0.1 * cos(iTime * 0.5));
        float2 p4 = vec2(0.8 + 0.1 * cos(iTime * 0.6), 0.7 + 0.2 * sin(iTime * 0.3));

        // Calculate distances to these centers
        float d1 = 1.0 - smoothstep(0.0, 0.8, distance(uv, p1));
        float d2 = 1.0 - smoothstep(0.0, 0.8, distance(uv, p2));
        float d3 = 1.0 - smoothstep(0.0, 0.8, distance(uv, p3));
        float d4 = 1.0 - smoothstep(0.0, 0.8, distance(uv, p4));

        // Blend colors based on distance
        half4 finalColor = mix(color1, color2, d2 / (d1 + d2 + 0.001));
        finalColor = mix(finalColor, color3, d3 / (d1 + d2 + d3 + 0.001));
        finalColor = mix(finalColor, color4, d4 / (d1 + d2 + d3 + d4 + 0.001));

        return finalColor;
    }
"""

@Composable
fun AnimatedMeshGradient(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFF0F2027),
        Color(0xFF203A43),
        Color(0xFF2C5364),
        Color(0xFF16222A)
    )
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(MESH_GRADIENT_SHADER) }
        val brush = remember { ShaderBrush(shader) }
        
        val infiniteTransition = rememberInfiniteTransition(label = "MeshGradientTime")
        val time by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 100f,
            animationSpec = infiniteRepeatable(
                animation = tween(20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "time"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .drawWithCache {
                    shader.setFloatUniform("iTime", time)
                    shader.setFloatUniform("iResolution", size.width, size.height)
                    
                    colors.forEachIndexed { index, color ->
                        shader.setColorUniform(
                            "color${index + 1}",
                            android.graphics.Color.valueOf(color.red, color.green, color.blue, color.alpha)
                        )
                    }

                    onDrawBehind {
                        drawRect(brush)
                    }
                }
        )
    } else {
        // Fallback for older devices
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(colors)
                )
        )
    }
}
