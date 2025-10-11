package com.example.mycam.service

import androidx.camera.core.CameraSelector
import com.example.mycam.model.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object StreamControl {
    data class CameraDesc(val id: String, val label: String)

    private val _resolution = MutableStateFlow(Resolution.VGA)
    val resolution: StateFlow<Resolution> = _resolution.asStateFlow()

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_FRONT)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _jpegQuality = MutableStateFlow(60) // 0..100, lower = faster
    val jpegQuality: StateFlow<Int> = _jpegQuality.asStateFlow()

    private val _targetFps = MutableStateFlow(30)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _cameras = MutableStateFlow<List<CameraDesc>>(emptyList())
    val cameras: StateFlow<List<CameraDesc>> = _cameras.asStateFlow()
    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCameraId: StateFlow<String?> = _selectedCameraId.asStateFlow()

    // Mirror moved to PC client side; keep constant false here
    fun setResolution(value: Resolution) { _resolution.value = value }
    fun setLensFacing(value: Int) { _lensFacing.value = value }
    fun setFps(value: Int) { _fps.value = value }
    fun setJpegQuality(value: Int) { _jpegQuality.value = value.coerceIn(1, 100) }
    fun setTargetFps(value: Int) { _targetFps.value = value.coerceIn(5, 120) }
    fun setCameras(value: List<CameraDesc>) { _cameras.value = value }
    fun setSelectedCameraId(value: String?) { _selectedCameraId.value = value }
}


