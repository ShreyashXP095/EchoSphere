package com.echosphere.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.echosphere.app.audio.youtube.PllTelemetry
import com.echosphere.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun SyncModal(
    currentNudgeMs: Long,
    onNudgeChanged: (Long) -> Unit,
    pllTelemetry: PllTelemetry,
    clockOffsetMicros: Long,
    lastRttMicros: Long,
    onTriggerChirpCalibration: () -> Unit,
    onDismiss: () -> Unit
) {
    var isCalibrating by remember { mutableStateOf(false) }

    val statusColor = when {
        abs(currentNudgeMs) <= 2 -> AccentGreen
        abs(currentNudgeMs) <= 10 -> AccentAmber
        else -> AccentRed
    }

    val statusText = when {
        abs(currentNudgeMs) <= 2 -> "Acoustic Phase Lock (≤ 2.0 ms)"
        abs(currentNudgeMs) <= 10 -> "Minor Comb Filtering (Engaging Micro-Shift)"
        else -> "Noticeable Offset (Dynamic PLL Active)"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet)),
                width = 1.5.dp
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sync & Calibration",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Phase Status Chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Manual Sync Nudge Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "MANUAL SYNC NUDGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "${if (currentNudgeMs >= 0) "+" else ""}$currentNudgeMs ms",
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Slider(
                    value = currentNudgeMs.toFloat(),
                    onValueChange = { onNudgeChanged(it.roundToLong()) },
                    valueRange = -150f..150f,
                    steps = 300,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = SurfaceElevatedDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("-150 ms", color = TextMuted, fontSize = 10.sp)
                    TextButton(
                        onClick = { onNudgeChanged(0L) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Reset to 0ms", color = ElectricCyan, fontSize = 11.sp)
                    }
                    Text("+150 ms", color = TextMuted, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Acoustic Chirp Auto-Calibration Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "One-Tap Acoustic Calibration",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            text = "Emits a 15ms FM sweep (2-6 kHz) to detect acoustic room delay and physical OEM DAC latency.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        Button(
                            onClick = {
                                isCalibrating = true
                                onTriggerChirpCalibration()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonPurple,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Calibrate via Microphone", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Diagnostic Telemetry Grid
                Text(
                    text = "REAL-TIME DIAGNOSTIC TELEMETRY",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, letterSpacing = 1.sp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryBox(
                        title = "Clock Offset (θ)",
                        value = "${clockOffsetMicros / 1000} ms",
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryBox(
                        title = "Network RTT",
                        value = "${lastRttMicros / 1000} ms",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryBox(
                        title = "PLL Drift (Edrift)",
                        value = "${pllTelemetry.driftErrorMs} ms",
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryBox(
                        title = "Playback Rate",
                        value = "${pllTelemetry.currentRate}×",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryBox(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevatedDark)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(title, color = TextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
