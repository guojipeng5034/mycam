package com.example.mycam.service

import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import com.example.mycam.model.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object StreamControl {
    data class CameraDesc(val id: String, val label: String)

    private val _resolution = MutableStateFlow(Resolution.FULL_HD)
    val resolution: StateFlow<Resolution> = _resolution.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_FRONT)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _targetFps = MutableStateFlow(30)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    /** 目标码率 (Mbps)，1–12，默认 5 */
    private val _videoBitrateMbps = MutableStateFlow(5)
    val videoBitrateMbps: StateFlow<Int> = _videoBitrateMbps.asStateFlow()

    private val _cameras = MutableStateFlow<List<CameraDesc>>(emptyList())
    val cameras: StateFlow<List<CameraDesc>> = _cameras.asStateFlow()
    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    private val _cameraControl = MutableStateFlow<CameraControl?>(null)
    val cameraControl: StateFlow<CameraControl?> = _cameraControl.asStateFlow()

    /** 推流时由 StreamingService 注册，用于设置 RtspServerCamera2 的 zoom */
    @Volatile private var zoomHandler: ((Float) -> Unit)? = null
    fun setZoomHandler(handler: ((Float) -> Unit)?) { zoomHandler = handler }
    fun requestZoom(level: Float) { zoomHandler?.invoke(level) }

    private val _rtspStreamUrl = MutableStateFlow("")
    val rtspStreamUrl: StateFlow<String> = _rtspStreamUrl.asStateFlow()

    fun setResolution(value: Resolution) { _resolution.value = value }
    fun setLensFacing(value: Int) { _lensFacing.value = value }
    fun setFps(value: Int) { _fps.value = value }
    fun setTargetFps(value: Int) { _targetFps.value = value.coerceIn(5, 120) }
    fun setVideoBitrateMbps(value: Int) { _videoBitrateMbps.value = value.coerceIn(1, 12) }
    fun setCameras(value: List<CameraDesc>) { _cameras.value = value }
    fun setSelectedCameraId(value: String?) { _selectedCameraId.value = value }
    fun setCameraControl(value: CameraControl?) { _cameraControl.value = value }
    fun setRtspStreamUrl(value: String) { _rtspStreamUrl.value = value }
}


