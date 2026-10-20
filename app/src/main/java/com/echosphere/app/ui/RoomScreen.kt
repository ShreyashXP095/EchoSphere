package com.echosphere.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.echosphere.app.data.model.*
import com.echosphere.app.ui.theme.*

// Curated demo tracks for zero-friction testing
data class DemoTrack(val videoId: String, val title: String, val artist: String)

val CURATED_DEMO_TRACKS = listOf(
    DemoTrack("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
    DemoTrack("5NV6Rdv1a3I", "Get Lucky", "Daft Punk ft. Pharrell"),
    DemoTrack("kgx4WGK0oNU", "Resonance (Synthwave)", "HOME"),
    DemoTrack("jfKfPfyJRdk", "Lofi Beats to Relax", "Lofi Girl")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomState: RoomState,
    isHost: Boolean,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onAddTrack: (videoId: String, title: String, artist: String) -> Unit,
    onVoteTrack: (itemId: String) -> Unit,
    onSwitchMode: (PlaybackMode) -> Unit,
    onShowQrModal: () -> Unit,
    onOpenSyncModal: () -> Unit,
    onLeaveRoom: () -> Unit
) {
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var customVideoInput by remember { mutableStateOf("") }
    var customTitleInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = roomState.roomName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(
                                containerColor = if (isHost) NeonViolet else SurfaceElevatedDark
                            ) {
                                Text(
                                    text = if (isHost) "HOST" else "CLIENT",
                                    color = TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = "${roomState.connectedPeers.size} phones phase-locked",
                            style = MaterialTheme.typography.labelSmall.copy(color = ElectricCyan)
                        )
                    }
                },
                actions = {
                    if (isHost) {
                        IconButton(onClick = onShowQrModal) {
                            Icon(Icons.Default.QrCode, contentDescription = "Show QR", tint = ElectricCyan)
                        }
                    }
                    IconButton(onClick = onOpenSyncModal) {
                        Icon(Icons.Default.Tune, contentDescription = "Sync Tuning", tint = TextPrimary)
                    }
                    IconButton(onClick = onLeaveRoom) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Leave Session", tint = AccentRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            // Mode Selector Bar (Mode A: YouTube vs Mode B: System Audio)
            ModeSelectorPills(
                currentMode = roomState.activeMode,
                isHost = isHost,
                onModeSelected = onSwitchMode
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Now Playing Card
            NowPlayingBanner(
                currentTrack = roomState.currentTrack,
                playbackState = roomState.playbackState,
                isHost = isHost,
                onTogglePlayPause = onTogglePlayPause,
                onPlayNext = onPlayNext
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Collaborative Queue Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PARTY QUEUE",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${roomState.queue.size})",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }

                Button(
                    onClick = { showAddTrackDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceElevatedDark,
                        contentColor = ElectricCyan
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Track", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Queue List
            val sortedQueue = remember(roomState.queue) {
                roomState.getSortedQueue()
            }

            if (sortedQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark.copy(alpha = 0.5f))
                        .border(1.dp, SurfaceVariantDark, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Queue is empty", color = TextSecondary, fontWeight = FontWeight.Medium)
                        Text("Tap '+ Add Track' to append songs to the room", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sortedQueue) { index, item ->
                        QueueItemRow(
                            index = index + 1,
                            item = item,
                            onVote = { onVoteTrack(item.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Add Track Dialog
    if (showAddTrackDialog) {
        Dialog(onDismissRequest = { showAddTrackDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet)),
                    width = 1.dp
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Add to Party Queue",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "QUICK ADD (POPULAR DEMOS)",
                        style = MaterialTheme.typography.labelSmall.copy(color = ElectricCyan, letterSpacing = 1.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    CURATED_DEMO_TRACKS.forEach { demo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceVariantDark)
                                .clickable {
                                    onAddTrack(demo.videoId, demo.title, demo.artist)
                                    showAddTrackDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(demo.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(demo.artist, color = TextSecondary, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "OR PASTE YOUTUBE VIDEO ID / URL",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, letterSpacing = 1.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customVideoInput,
                        onValueChange = { customVideoInput = it },
                        placeholder = { Text("e.g. dQw4w9WgXcQ", color = TextMuted, fontSize = 13.sp) },
                        label = { Text("YouTube Video ID or Link", color = TextSecondary, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = SurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customTitleInput,
                        onValueChange = { customTitleInput = it },
                        placeholder = { Text("Track Title (Optional)", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = SurfaceVariantDark
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (customVideoInput.isNotBlank()) {
                                val cleanId = extractYouTubeId(customVideoInput.trim())
                                val title = if (customTitleInput.isNotBlank()) customTitleInput.trim() else "YouTube Track"
                                onAddTrack(cleanId, title, "YouTube Music")
                                showAddTrackDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = BackgroundDark
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Add to Queue", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ModeSelectorPills(
    currentMode: PlaybackMode,
    isHost: Boolean,
    onModeSelected: (PlaybackMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(4.dp)
    ) {
        val modeASelected = currentMode == PlaybackMode.MODE_A_YOUTUBE

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (modeASelected) ElectricCyan else Color.Transparent)
                .clickable(enabled = isHost) { onModeSelected(PlaybackMode.MODE_A_YOUTUBE) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Mode A: YouTube PLL",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (modeASelected) BackgroundDark else TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (!modeASelected) NeonViolet else Color.Transparent)
                .clickable(enabled = isHost) { onModeSelected(PlaybackMode.MODE_B_SYSTEM_AUDIO) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Mode B: System Broadcast",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (!modeASelected) TextPrimary else TextSecondary
            )
        }
    }
}

@Composable
fun NowPlayingBanner(
    currentTrack: QueueItem?,
    playbackState: TrackPlaybackState,
    isHost: Boolean,
    onTogglePlayPause: () -> Unit,
    onPlayNext: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet)),
            width = 1.5.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceElevatedDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentTrack?.title ?: "No Track Playing",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentTrack?.artist ?: "Add songs from the queue below",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextSecondary,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isHost && currentTrack != null) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan)
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = BackgroundDark
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onPlayNext,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevatedDark)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else if (currentTrack != null) {
                    // Sync lock status badge for clients
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AccentGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (playbackState.isPlaying) "SYNCED" else "PAUSED",
                            color = AccentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueItemRow(
    index: Int,
    item: QueueItem,
    onVote: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$index",
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.width(26.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.artist} • added by ${item.addedByName}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Upvote Button
            Button(
                onClick = onVote,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceElevatedDark,
                    contentColor = ElectricCyan
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.ThumbUp, contentDescription = "Vote", modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${item.upvotes}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun extractYouTubeId(urlOrId: String): String {
    return when {
        urlOrId.contains("v=") -> urlOrId.substringAfter("v=").substringBefore("&")
        urlOrId.contains("youtu.be/") -> urlOrId.substringAfter("youtu.be/").substringBefore("?")
        else -> urlOrId
    }
}
