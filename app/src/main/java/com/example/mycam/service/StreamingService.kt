package com.example.mycam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaCodecInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.mycam.MainActivity
import com.example.mycam.R
import com.example.mycam.model.Resolution
import com.example.mycam.util.NetworkInfo
import com.example.mycam.util.StreamPreviewHolder
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.FpsListener
import com.pedro.rtspserver.RtspServerCamera2
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * MyCam 2.0 推流服务
 *
 * 使用 RootEncoder + RTSP-Server 实现零拷贝硬件编码：
 * 相机 → Surface → MediaCodec → H.264 → RTSP Server
 *
 * 支持 OpenGlView 本地预览（通过 StreamPreviewHolder 获取）。
 *
 * 低延迟优化策略：
 * - Baseline Profile：强制 H.264 Baseline，关闭 B 帧，减少解码依赖
 * - 短关键帧间隔：iFrameInterval=1 秒，首帧更快
 * - 采集与编码分辨率完全一致，避免缩放开销
 * - 注：BITRATE_MODE_CBR / KEY_LATENCY / KEY_PRIORITY 需 RootEncoder 支持自定义 MediaFormat，当前库未暴露
 */
class StreamingService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1001
        private const val TAG = "StreamingService"
        const val RTSP_PORT = 8554
        private const val RTSP_PATH = "live"
        /**
         * I 帧间隔（秒）。1 = 每秒一个 I 帧，缩短接收端首帧等待。
         * 可尝试 0（部分设备支持，极短间隔/全内帧），但可能显著增加码率。
         */
        private const val I_FRAME_INTERVAL = 1
    }

    private var rtspCamera: RtspServerCamera2? = null
    private var isStreaming = false
    private var lensFacingJob: Job? = null

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionSuccess() {
            Log.i(TAG, "RTSP server started successfully")
            runOnMain { updateStreamUrlAndNotification() }
        }

        override fun onConnectionFailed(reason: String) {
            Log.e(TAG, "RTSP connection failed: $reason")
            runOnMain {
                StreamControl.setFps(0)
                updateNotification("Error: $reason")
            }
        }

        override fun onConnectionStarted(url: String) {
            Log.d(TAG, "Connection started: $url")
        }

        override fun onNewBitrate(bitrate: Long) {
            // 可选：用于码率适配
        }

        override fun onDisconnect() {
            Log.i(TAG, "RTSP disconnected")
        }

        override fun onAuthError() {
            Log.e(TAG, "RTSP auth error")
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "RTSP auth success")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting RTSP server..."))
        startRtspStream()
    }

    override fun onDestroy() {
        StreamControl.setZoomHandler(null)
        stopRtspStream()
        StreamPreviewHolder.clear()
        super.onDestroy()
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    /**
     * 按分辨率选择 H.264 等级，确保编码器能支持对应分辨率/帧率。
     * 采集与编码分辨率完全一致（width/height 均来自 StreamControl），避免额外缩放开销。
     */
    private fun avcLevelForResolution(resolution: Resolution): Int = when (resolution) {
        Resolution.VGA -> MediaCodecInfo.CodecProfileLevel.AVCLevel31      // 720p 以内
        Resolution.HD_720P -> MediaCodecInfo.CodecProfileLevel.AVCLevel31   // 720p
        Resolution.FULL_HD -> MediaCodecInfo.CodecProfileLevel.AVCLevel4  // 1080p
    }

    /**
     * 低延迟视频编码配置：Baseline Profile（无 B 帧）、短 I 帧间隔、分辨率对齐。
     */
    private fun prepareVideoLowLatency(
        camera: RtspServerCamera2,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        rotation: Int,
        resolution: Resolution
    ): Boolean {
        val profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline  // 强制关闭 B 帧
        val level = avcLevelForResolution(resolution)
        return camera.prepareVideo(width, height, fps, bitrateBps, I_FRAME_INTERVAL, rotation, profile, level)
    }

    private fun startRtspStream() {
        try {
            val resolution = StreamControl.resolution.value
            val width = resolution.width
            val height = resolution.height
            val fps = StreamControl.targetFps.value.coerceIn(15, 60)
            val bitrateBps = StreamControl.videoBitrateMbps.value.coerceIn(1, 12) * 1_000_000
            val facing = if (StreamControl.lensFacing.value == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                CameraHelper.Facing.BACK
            } else {
                CameraHelper.Facing.FRONT
            }
            // 前置 180° 正确；后置需翻转 0°
            val rotation = if (facing == CameraHelper.Facing.BACK) 0 else 180

            // 采集与编码分辨率完全一致，避免 ImageAnalysis/GPU 缩放带来的额外毫秒级开销
            val openGlView = StreamPreviewHolder.getPreviewView()

            rtspCamera = if (openGlView != null) {
                RtspServerCamera2(openGlView, connectChecker, RTSP_PORT).apply {
                    setFpsListener { fps -> runOnMain { StreamControl.setFps(fps) } }
                    setVideoCodec(VideoCodec.H264)
                    if (!prepareVideoLowLatency(this, width, height, fps, bitrateBps, rotation, resolution)) {
                        Log.e(TAG, "prepareVideo failed")
                        runOnMain { updateNotification("Video prepare failed") }
                        return
                    }
                    if (!prepareAudio(128 * 1024, 32000, true, false, false)) {
                        Log.w(TAG, "prepareAudio failed, continuing video-only")
                    } else {
                        disableAudio() // 默认关闭音频输出（画面需 prepareAudio 才能正常）
                    }
                    startPreview(facing, width, height, rotation)
                    startStream()
                }
            } else {
                RtspServerCamera2(this, connectChecker, RTSP_PORT).apply {
                    setFpsListener { fps -> runOnMain { StreamControl.setFps(fps) } }
                    setVideoCodec(VideoCodec.H264)
                    if (!prepareVideoLowLatency(this, width, height, fps, bitrateBps, rotation, resolution)) {
                        Log.e(TAG, "prepareVideo failed")
                        runOnMain { updateNotification("Video prepare failed") }
                        return
                    }
                    if (!prepareAudio(128 * 1024, 32000, true, false, false)) {
                        Log.w(TAG, "prepareAudio failed, continuing video-only")
                    } else {
                        disableAudio() // 默认关闭音频输出
                    }
                    startPreview(facing, width, height, rotation)
                    startStream()
                }
            }
            runOnMain { updateStreamUrlAndNotification() }
            val cam = rtspCamera!!
            StreamControl.setZoomHandler { level -> runOnMain { try { cam.setZoom(level) } catch (_: Throwable) {} } }
            isStreaming = true
            lensFacingJob = lifecycleScope.launch {
                StreamControl.lensFacing.drop(1).collect { newLensFacing ->
                    if (isStreaming) {
                        runOnMain {
                            try {
                                val openGlView = StreamPreviewHolder.getPreviewView()
                                // 先设置目标摄像头的 rotation 和 flip，再切换（避免帧错乱）
                                // 切换时需与启动时相反：后置 180°、前置 0°（RootEncoder switchCamera 内部行为不同）
                                val rotation = if (newLensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) 180 else 0
                                val isFront = newLensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                                openGlView?.setStreamRotation(rotation)
                                openGlView?.setCameraFlip(isFront, false)
                                cam.switchCamera()
                                Log.d(TAG, "Camera switched, rotation=$rotation, isFront=$isFront")
                            } catch (e: Exception) {
                                Log.e(TAG, "switchCamera failed", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP stream", e)
            runOnMain {
                updateNotification("Start failed: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun stopRtspStream() {
        try {
            rtspCamera?.let { cam ->
                try {
                    if (cam.isStreaming) cam.stopStream()
                    cam.stopPreview()
                } catch (_: Throwable) {}
            }
            rtspCamera = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream", e)
        }
        lensFacingJob?.cancel()
        lensFacingJob = null
        isStreaming = false
        StreamControl.setRtspStreamUrl("")
        StreamControl.setFps(0)
    }

    private fun updateStreamUrlAndNotification() {
        val ip = NetworkInfo.getDeviceIp(this) ?: "0.0.0.0"
        val url = "rtsp://$ip:$RTSP_PORT/$RTSP_PATH"
        StreamControl.setRtspStreamUrl(url)
        updateNotification("Streaming at $url")
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
