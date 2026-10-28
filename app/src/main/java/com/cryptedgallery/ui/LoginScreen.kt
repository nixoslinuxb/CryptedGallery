package com.cryptedgallery.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun LoginScreen(
    isLockedOut: Boolean,
    lockoutTimeMs: Long,
    totalLockoutTimeMs: Long,
    attemptsLeft: Int,
    onUnlock: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    isFirstTime: Boolean
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    
    var showErrorBadge by remember { mutableStateOf(false) }
    
    // Shake Animation State
    val shakeOffset = remember { Animatable(0f) }
    
    fun triggerError() {
        showErrorBadge = true
        scope.launch {
            // Multi-pulse physical feedback
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(150)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            
            repeat(4) {
                shakeOffset.animateTo(15f, tween(50, easing = LinearEasing))
                shakeOffset.animateTo(-15f, tween(50, easing = LinearEasing))
            }
            shakeOffset.animateTo(0f, tween(50, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // BACKGROUND: Modern GPU Mesh Gradient
        AnimatedMeshGradient()

        // GLASS CARD
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .wrapContentHeight()
                .offset(x = shakeOffset.value.dp)
                .shadow(elevation = 30.dp, shape = RoundedCornerShape(32.dp), ambientColor = Color.Cyan),
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(32.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(32.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.Cyan,
                        modifier = Modifier.size(64.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = if (isFirstTime) "Secure Your App" else "System Encrypted",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = if (isFirstTime) "Set a 256-bit AES master password" else "Enter password to decrypt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    if (isLockedOut) {
                        LockoutTimerModern(lockoutTimeMs, totalLockoutTimeMs)
                    } else {
                        AnimatedVisibility(
                            visible = showErrorBadge,
                            enter = slideInVertically { -it } + fadeIn(),
                            exit = slideOutVertically { -it } + fadeOut()
                        ) {
                            SecurityInfoBadge(attemptsLeft)
                        }
                        
                        if (showErrorBadge) Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { 
                                password = it
                                errorText = null
                                showErrorBadge = false
                            },
                            label = { Text("Master Password", color = Color.White.copy(alpha = 0.6f)) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Cyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = { 
                                if (isFirstTime) {
                                    onSetPassword(password)
                                } else {
                                    onUnlock(password)
                                    // If still locked after attempt, it was wrong
                                    scope.launch {
                                        delay(100) 
                                        triggerError()
                                    }
                                }
                            },
                            enabled = password.length >= 4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Cyan,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.Cyan.copy(alpha = 0.3f),
                                disabledContentColor = Color.Black.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = if (isFirstTime) "Enable Encryption" else "Unlock & Decrypt",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LockoutTimerModern(timeMs: Long, totalTimeMs: Long) {
    val progress = (timeMs.toFloat() / totalTimeMs.toFloat()).coerceIn(0f, 1f)
    val minutes = (timeMs / 1000) / 60
    val seconds = (timeMs / 1000) % 60
    
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        // Cooldown Ring
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            drawCircle(
                color = Color.White.copy(alpha = 0.1f),
                style = Stroke(width = 8.dp.toPx())
            )
            drawArc(
                color = Color.Red,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 8.dp.toPx())
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "SECURE LOCK",
                color = Color.Red.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SecurityInfoBadge(attemptsLeft: Int) {
    val color = if (attemptsLeft == 1) Color(0xFFFF5252) else Color.Cyan
    Surface(
        color = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (attemptsLeft == 1) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$attemptsLeft attempts remaining",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
