package com.example.mycam

import android.content.ClipData
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mycam.model.Resolution
import com.example.mycam.service.StreamControl
import com.example.mycam.service.StreamingService
import com.example.mycam.ui.theme.MyCamTheme
import com.example.mycam.util.StreamPreviewHolder
import com.example.mycam.viewmodel.StreamViewModel
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            MyCamTheme {
                MainScreen()
            }
        }
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    var streaming by remember { mutableStateOf(false) }
    val rtspUrl by StreamControl.rtspStreamUrl.collectAsStateWithLifecycle(initialValue = "")
    val fps by StreamControl.fps.collectAsStateWithLifecycle(initialValue = 0)
    val resolution by StreamControl.resolution.collectAsStateWithLifecycle()
    val bitrateMbps by StreamControl.videoBitrateMbps.collectAsStateWithLifecycle()
    val lensFacing by StreamControl.lensFacing.collectAsStateWithLifecycle()

    var zoomRatio by remember { mutableStateOf(1f) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val serviceCameraControl by StreamControl.cameraControl.collectAsStateWithLifecycle()

    val displayUrl = when {
        rtspUrl.isNotEmpty() -> rtspUrl
        streaming -> "rtsp://<IP>:${StreamingService.RTSP_PORT}/live (连接中...)"
        else -> "rtsp://<IP>:${StreamingService.RTSP_PORT}/live (启动后显示)"
    }

    // 相机列表
    LaunchedEffect(Unit) {
        androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).addListener({
            try {
                val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                val list = provider.availableCameraInfos.map { info ->
                    val c2 = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                    val camId = c2.cameraId
                    val facing = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    val label = if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) "Back ($camId)" else "Front ($camId)"
                    StreamControl.CameraDesc(camId, label)
                }
                StreamControl.setCameras(list)
                if (StreamControl.selectedCameraId.value == null && list.isNotEmpty()) {
                    StreamControl.setSelectedCameraId(list.first().id)
                }
            } catch (_: Throwable) {}
        }, ContextCompat.getMainExecutor(context))
    }

    // 推流启动：延迟以确保 OpenGlView 已布局，再启动服务
    LaunchedEffect(streaming) {
        if (streaming) {
            delay(250)
            StreamViewModel.startStreaming(context)
        }
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
            // 预览区域：推流时用 OpenGlView（RootEncoder 渲染），非推流时用 CameraX PreviewView
            PreviewCard(
                streaming = streaming,
                lensFacing = lensFacing,
                lifecycleOwner = lifecycleOwner,
                zoomRatio = zoomRatio,
                onZoomChange = { zoomRatio = it },
                cameraControl = cameraControl,
                serviceCameraControl = serviceCameraControl,
                onCameraControlUpdate = { cameraControl = it },
                fps = fps
            )

            // RTSP URL 卡片
            RtpUrlCard(
                displayUrl = displayUrl,
                onCopy = {
                    if (displayUrl.isNotBlank() && !displayUrl.contains("<IP>")) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("rtsp", displayUrl)))
                            snackbarHostState.showSnackbar("RTSP URL 已复制")
                        }
                    }
                }
            )

            // 设置卡片
            SettingsCard(
                resolution = resolution,
                bitrateMbps = bitrateMbps,
                lensFacing = lensFacing,
                streaming = streaming,
                onResolutionChange = {
                    StreamControl.setResolution(it)
                },
                onBitrateChange = {
                    StreamControl.setVideoBitrateMbps(it)
                },
                onLensFacingChange = {
                    StreamControl.setLensFacing(it)
                }
            )

            Spacer(Modifier.weight(1f))

            // 启动/停止按钮
            StartStopButton(
                streaming = streaming,
                onStart = { streaming = true },
                onStop = {
                    streaming = false
                    StreamViewModel.stopStreaming(context)
                }
            )
        }
    }
}

@Composable
private fun PreviewCard(
    streaming: Boolean,
    lensFacing: Int,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit,
    cameraControl: androidx.camera.core.CameraControl?,
    serviceCameraControl: androidx.camera.core.CameraControl?,
    onCameraControlUpdate: (androidx.camera.core.CameraControl?) -> Unit,
    fps: Int
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (streaming) {
                val openGlView = remember {
                    OpenGlView(context).apply {
                        setAspectRatioMode(AspectRatioMode.Adjust)
                        setCameraFlip(lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT, false)
                    }
                }
                LaunchedEffect(lensFacing) {
                    openGlView.setCameraFlip(lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT, false)
                }
                DisposableEffect(openGlView) {
                    StreamPreviewHolder.setPreviewView(openGlView)
                    onDispose { StreamPreviewHolder.clear() }
                }
                AndroidView(
                    factory = { openGlView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                val previewView = remember {
                    androidx.camera.view.PreviewView(context).apply {
                        implementationMode = androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE
                    }
                }
                LaunchedEffect(lensFacing) {
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = androidx.camera.core.Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val selector = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                            } else {
                                androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
                            }
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                preview
                            )
                            onCameraControlUpdate(camera.cameraControl)
                            camera.cameraControl.setZoomRatio(1f)
                            onZoomChange(1f)
                        } catch (_: Throwable) {}
                    }, ContextCompat.getMainExecutor(context))
                }
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            // FPS 角标
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (streaming) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$fps FPS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Zoom 滑块
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Zoom",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${String.format("%.1f", zoomRatio)}x",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        val newZoom = (zoomRatio - 0.5f).coerceAtLeast(1f)
                        onZoomChange(newZoom)
                        val control = serviceCameraControl ?: cameraControl
                        control?.setZoomRatio(newZoom)
                        StreamControl.requestZoom(newZoom)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out", modifier = Modifier.size(20.dp))
                }
                Slider(
                    value = zoomRatio,
                    onValueChange = {
                        onZoomChange(it)
                        val control = serviceCameraControl ?: cameraControl
                        control?.setZoomRatio(it)
                        StreamControl.requestZoom(it)
                    },
                    valueRange = 1f..30f,
                    modifier = Modifier.weight(1f),
                    steps = 0
                )
                IconButton(
                    onClick = {
                        val newZoom = (zoomRatio + 0.5f).coerceAtMost(30f)
                        onZoomChange(newZoom)
                        val control = serviceCameraControl ?: cameraControl
                        control?.setZoomRatio(newZoom)
                        StreamControl.requestZoom(newZoom)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun RtpUrlCard(displayUrl: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "RTSP URL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    displayUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    resolution: Resolution,
    bitrateMbps: Int,
    lensFacing: Int,
    streaming: Boolean,
    onResolutionChange: (Resolution) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onLensFacingChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 码率 (Mbps)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "码率",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$bitrateMbps Mbps",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Slider(
                    value = bitrateMbps.toFloat(),
                    onValueChange = { onBitrateChange(it.toInt().coerceIn(1, 12)) },
                    valueRange = 1f..12f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 分辨率 & 摄像头
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val next = when (resolution) {
                            Resolution.VGA -> Resolution.HD_720P
                            Resolution.HD_720P -> Resolution.FULL_HD
                            Resolution.FULL_HD -> Resolution.VGA
                        }
                        onResolutionChange(next)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.HighQuality, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(resolution.displayName)
                }
                OutlinedButton(
                    onClick = {
                        val next = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                        } else {
                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                        }
                        onLensFacingChange(next)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) "后置" else "前置")
                }
            }
        }
    }
}

@Composable
private fun StartStopButton(
    streaming: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val buttonText = if (streaming) "停止推流" else "开始推流"
    Button(
        onClick = if (streaming) onStop else onStart,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (streaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (streaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buttonText,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
