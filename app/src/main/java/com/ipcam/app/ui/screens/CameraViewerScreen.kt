package com.ipcam.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ipcam.app.ui.viewmodel.AuthViewModel
import com.ipcam.app.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraViewerScreen(
    cameraId: String,
    authViewModel: AuthViewModel,
    cameraViewModel: CameraViewModel,
    onBack: () -> Unit
) {
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val uiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll debug console to bottom on new log entries
    LaunchedEffect(uiState.debugLogs.size) {
        if (uiState.debugLogs.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(uiState.debugLogs.size - 1) }
        }
    }

    // Connect when the screen opens — mirrors the React useEffect([user, id])
    LaunchedEffect(cameraId) {
        authState.user?.let { user ->
            cameraViewModel.connect(user.id, cameraId)
        }
    }

    // Disconnect when the screen leaves the composition — mirrors the cleanup return in useEffect
    DisposableEffect(Unit) {
        onDispose { cameraViewModel.disconnect() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "Camera ID: $cameraId",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Status: ${uiState.error ?: uiState.status}",
                color = if (uiState.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Video player ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceViewRenderer(context).also { renderer ->
                            cameraViewModel.initVideoRenderer(renderer)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!uiState.isPlaying) {
                    Text(
                        text = "Waiting for video stream...",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Debug console ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Debug Console",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { cameraViewModel.clearLogs() }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF111827))
                    .padding(8.dp)
            ) {
                if (uiState.debugLogs.isEmpty()) {
                    Text(
                        text = "No logs yet...",
                        color = Color(0xFF6B7280),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(uiState.debugLogs) { line ->
                            Text(
                                text = line,
                                color = when {
                                    line.contains("error", ignoreCase = true) ||
                                            line.contains("failed", ignoreCase = true) ||
                                            line.contains("ERROR") -> Color(0xFFF87171)
                                    line.contains("success", ignoreCase = true) ||
                                            line.contains("connected", ignoreCase = true) -> Color(0xFF4ADE80)
                                    else -> Color(0xFF86EFAC)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
