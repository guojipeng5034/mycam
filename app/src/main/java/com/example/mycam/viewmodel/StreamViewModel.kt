package com.example.mycam.viewmodel

import android.content.Context
import android.content.Intent
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.mycam.model.Resolution
import com.example.mycam.service.StreamControl
import com.example.mycam.service.StreamingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 层：封装推流服务交互与 StreamControl 状态。
 * 供 Compose UI 使用，符合单向数据流。
 */
object StreamViewModel {

    val rtspStreamUrl: StateFlow<String> = StreamControl.rtspStreamUrl
    val fps: StateFlow<Int> = StreamControl.fps
    val resolution: StateFlow<Resolution> = StreamControl.resolution
    val videoBitrateMbps: StateFlow<Int> = StreamControl.videoBitrateMbps
    val lensFacing: StateFlow<Int> = StreamControl.lensFacing

    fun setResolution(value: Resolution) = StreamControl.setResolution(value)
    fun setLensFacing(value: Int) = StreamControl.setLensFacing(value)
    fun setVideoBitrateMbps(value: Int) = StreamControl.setVideoBitrateMbps(value)

    /**
     * 启动推流：解绑 CameraX 后启动 StreamingService。
     * 需在主线程调用。
     */
    fun startStreaming(context: Context) {
        ProcessCameraProvider.getInstance(context).addListener({
            try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (_: Throwable) {}
            ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
        }, ContextCompat.getMainExecutor(context))
    }

    /** 停止推流 */
    fun stopStreaming(context: Context) {
        context.stopService(Intent(context, StreamingService::class.java))
    }

    fun isBackCamera(): Boolean = StreamControl.lensFacing.value == CameraSelector.LENS_FACING_BACK
}
