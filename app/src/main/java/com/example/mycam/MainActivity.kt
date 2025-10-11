package com.example.mycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraCharacteristics
import androidx.core.content.ContextCompat
import com.example.mycam.service.StreamingService
import com.example.mycam.ui.theme.MyCamTheme
import com.example.mycam.util.NetworkInfo
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        setContent {
            MyCamTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var streaming by remember { mutableStateOf(false) }
    val ip = NetworkInfo.getDeviceIp(context) ?: "0.0.0.0"
    val url = "http://$ip:8080/mjpeg"
    val fps by com.example.mycam.service.StreamControl.fps.collectAsState(initial = 0)
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    var resolution by remember { mutableStateOf(com.example.mycam.model.Resolution.VGA) }
    var quality by remember { mutableStateOf(60f) }

    LaunchedEffect(Unit) {
        try {
            val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val list = provider.availableCameraInfos.map { info ->
                        val c2 = Camera2CameraInfo.from(info)
                        val camId = c2.cameraId
                        val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                        val label = if (facing == CameraCharacteristics.LENS_FACING_BACK) "Back ($camId)" else "Front ($camId)"
                        com.example.mycam.service.StreamControl.CameraDesc(camId, label)
                    }
                    com.example.mycam.service.StreamControl.setCameras(list)
                    if (com.example.mycam.service.StreamControl.selectedCameraId.value == null && list.isNotEmpty()) {
                        com.example.mycam.service.StreamControl.setSelectedCameraId(list.first().id)
                    }
                } catch (_: Throwable) {}
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Throwable) {}
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MyCam", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (streaming) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            if (streaming) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = null,
                            tint = if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (streaming) "Streaming" else "Stopped",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "$fps FPS",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // URL Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stream URL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(url))
                        scope.launch { snackbarHostState.showSnackbar("URL copied!") }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                }
            }

            // Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    // Quality Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Quality", style = MaterialTheme.typography.bodyMedium)
                            Text("${quality.toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Slider(
                            value = quality,
                            onValueChange = {
                                quality = it
                                com.example.mycam.service.StreamControl.setJpegQuality(quality.toInt())
                            },
                            valueRange = 40f..95f
                        )
                    }

                    Divider()

                    // Camera & Resolution
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                                com.example.mycam.service.StreamControl.setLensFacing(lensFacing)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Cameraswitch, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (lensFacing == CameraSelector.LENS_FACING_BACK) "Back" else "Front")
                        }
                        OutlinedButton(
                            onClick = {
                                resolution = when (resolution) {
                                    com.example.mycam.model.Resolution.VGA -> com.example.mycam.model.Resolution.HD_720P
                                    com.example.mycam.model.Resolution.HD_720P -> com.example.mycam.model.Resolution.FULL_HD
                                    com.example.mycam.model.Resolution.FULL_HD -> com.example.mycam.model.Resolution.VGA
                                }
                                com.example.mycam.service.StreamControl.setResolution(resolution)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.HighQuality, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(resolution.displayName)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Start/Stop Button
            Button(
                onClick = {
                    if (!streaming) {
                        ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
                    } else {
                        context.stopService(Intent(context, StreamingService::class.java))
                    }
                    streaming = !streaming
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (streaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (streaming) "Stop Streaming" else "Start Streaming",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
