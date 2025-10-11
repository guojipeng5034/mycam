package com.example.mycam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.lifecycle.LifecycleService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.mycam.MainActivity
import com.example.mycam.R
import com.example.mycam.server.MjpegHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import java.util.concurrent.Executor
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.core.CameraSelector.Builder as CameraSelectorBuilder
import androidx.camera.core.CameraInfo
import androidx.camera.camera2.interop.Camera2CameraInfo
import android.hardware.camera2.CameraCharacteristics
import com.example.mycam.util.NetworkInfo
 
import android.util.Log

class StreamingService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1001
        private const val TAG = "StreamingService"
    }

    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine error", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineErrorHandler)
    private var server: MjpegHttpServer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastFpsTimestampNs: Long = 0L
    private var framesCount: Int = 0
    private lateinit var mainExecutor: Executor
    private val analyzeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val jpegStream = ReusableByteArrayOutputStream(1_048_576)
    @Volatile private var nv21Buffer: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting server..."))
        mainExecutor = ContextCompat.getMainExecutor(this)
        scope.launch {
            server = MjpegHttpServer(port = 8080).also { it.start() }
            startCamera()
            updateNotification("Streaming at ${currentStreamUrl()}")
        }
        // Live react to control changes without restarting the service
        scope.launch {
            StreamControl.lensFacing.collect {
                bindAnalysis()
            }
        }
        scope.launch {
            StreamControl.resolution.collect {
                bindAnalysis()
            }
        }
        scope.launch {
            StreamControl.targetFps.collect {
                bindAnalysis()
            }
        }
        
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch { server?.stop() }
        cameraProvider?.unbindAll()
        scope.cancel()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            enumerateCameras(provider)
            bindAnalysis()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enumerateCameras(provider: ProcessCameraProvider) {
        val list = mutableListOf<StreamControl.CameraDesc>()
        provider.availableCameraInfos.forEach { info ->
            val c2 = Camera2CameraInfo.from(info)
            val camId = c2.cameraId
            val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val label = if (facing == CameraCharacteristics.LENS_FACING_BACK) "Back ($camId)" else "Front ($camId)"
            list.add(StreamControl.CameraDesc(camId, label))
        }
        StreamControl.setCameras(list)
        if (StreamControl.selectedCameraId.value == null && list.isNotEmpty()) {
            StreamControl.setSelectedCameraId(list.first().id)
        }
    }

    private fun bindAnalysis() {
        val provider = cameraProvider ?: return
        // Ensure binding on main executor
        (if (::mainExecutor.isInitialized) mainExecutor else ContextCompat.getMainExecutor(this)).execute {
            val selector = if (StreamControl.lensFacing.value == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val res = StreamControl.resolution.value
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(res.width, res.height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)
            val extender = Camera2Interop.Extender(analysisBuilder)
            val maxFps = queryMaxSupportedFps(selector)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(maxFps, maxFps))
            
            // Reset NV21 buffer to be recalculated per actual frame size
            nv21Buffer = null
            val analysisUseCase = analysisBuilder.build()
            analysisUseCase.setAnalyzer(analyzeExecutor) { image ->
                try {
                    val (buf, len) = yuv420ToJpegIntoBuffer(image)
                    if (buf != null && len > 0) server?.offerFrame(buf, len)
                    trackFps()
                } catch (t: Throwable) {
                    Log.e(TAG, "Analyzer error", t)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysisUseCase)
            analysis = analysisUseCase
        }
    }


    private fun yuv420ToJpegIntoBuffer(image: ImageProxy): Pair<ByteArray?, Int> {
        val nv21 = yuv420888ToNv21(image, horizontalMirror = false, reuse = nv21Buffer)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        jpegStream.reset()
        val crop = calculateCenterCrop16x9(image.width, image.height)
        val quality = StreamControl.jpegQuality.value
        val ok = yuvImage.compressToJpeg(crop, quality, jpegStream)
        if (!ok) return Pair(null, 0)
        return Pair(jpegStream.buffer(), jpegStream.size())
    }

    private class ReusableByteArrayOutputStream(initialCapacity: Int) : java.io.OutputStream() {
        private var buf: ByteArray = ByteArray(initialCapacity)
        private var count: Int = 0

        override fun write(b: Int) {
            ensureCapacity(1)
            buf[count] = b.toByte()
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            ensureCapacity(len)
            System.arraycopy(b, off, buf, count, len)
            count += len
        }

        fun reset() { count = 0 }
        fun size(): Int = count
        fun buffer(): ByteArray = buf

        private fun ensureCapacity(increment: Int) {
            val required = count + increment
            if (required <= buf.size) return
            var newCap = buf.size.coerceAtLeast(1)
            while (newCap < required) newCap = newCap * 2
            val newBuf = ByteArray(newCap)
            System.arraycopy(buf, 0, newBuf, 0, count)
            buf = newBuf
        }
    }

    private fun calculateCenterCrop16x9(width: Int, height: Int): android.graphics.Rect {
        // Ensure even dimensions for YUV subsampling
        fun even(x: Int): Int = if (x % 2 == 0) x else x - 1
        val targetRatio = 16.0 / 9.0
        val srcRatio = width.toDouble() / height.toDouble()
        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            // Too wide, limit width
            cropH = even(height)
            cropW = even((cropH * targetRatio).toInt())
        } else {
            // Too tall, limit height
            cropW = even(width)
            cropH = even((cropW / targetRatio).toInt())
        }
        val left = ((width - cropW) / 2).coerceAtLeast(0)
        val top = ((height - cropH) / 2).coerceAtLeast(0)
        return android.graphics.Rect(left, top, left + cropW, top + cropH)
    }

    private fun queryMaxSupportedFps(selector: CameraSelector): Int {
        return try {
            val provider = cameraProvider ?: return StreamControl.targetFps.value
            val desiredFacing = StreamControl.lensFacing.value
            var best: Int? = null
            for (info in provider.availableCameraInfos) {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing == desiredFacing) {
                    val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val max = ranges?.maxByOrNull { it.upper }?.upper
                    if (max != null && (best == null || max > best!!)) best = max
                }
            }
            best ?: StreamControl.targetFps.value
        } catch (_: Throwable) { StreamControl.targetFps.value }
    }


    private fun yuv420888ToNv21(image: ImageProxy, horizontalMirror: Boolean, reuse: ByteArray?): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val required = width * height + (width * height) / 2
        val nv21 = if (reuse != null && reuse.size == required) reuse else ByteArray(required)

        // Copy Y plane (with optional horizontal mirror)
        var outputPos = 0
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yBufferPosStart = yBuffer.position()
        for (row in 0 until height) {
            val rowStart = yBufferPosStart + row * yRowStride
            // mirror is handled on PC side now; use fast path
            if (yPixelStride == 1) {
                yBuffer.position(rowStart)
                yBuffer.get(nv21, outputPos, width)
                outputPos += width
            } else {
                var colBufferPos = rowStart
                for (col in 0 until width) {
                    nv21[outputPos++] = yBuffer.get(colBufferPos)
                    colBufferPos += yPixelStride
                }
            }
        }

        // Copy UV planes interleaved as VU (NV21), with optional horizontal mirror
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uBufferPosStart = uBuffer.position()
        val vBufferPosStart = vBuffer.position()

        for (row in 0 until height / 2) {
            var uRowPos = uBufferPosStart + row * uvRowStride
            var vRowPos = vBufferPosStart + row * uvRowStride
            if (uvPixelStride == 2) {
                for (col in 0 until width / 2) {
                    nv21[outputPos++] = vBuffer.get(vRowPos)
                    nv21[outputPos++] = uBuffer.get(uRowPos)
                    uRowPos += uvPixelStride
                    vRowPos += uvPixelStride
                }
            } else {
                for (col in 0 until width / 2) {
                    nv21[outputPos++] = vBuffer.get(vRowPos++)
                    nv21[outputPos++] = uBuffer.get(uRowPos++)
                }
            }
        }

        return nv21
    }

    private fun trackFps() {
        val now = System.nanoTime()
        if (lastFpsTimestampNs == 0L) {
            lastFpsTimestampNs = now
        }
        framesCount += 1
        val elapsed = now - lastFpsTimestampNs
        if (elapsed >= 1_000_000_000L) {
            val fps = framesCount
            framesCount = 0
            lastFpsTimestampNs = now
            StreamControl.setFps(fps)
            updateNotification("Streaming at ${currentStreamUrl()} • ${fps}fps")
        }
    }

    private fun currentStreamUrl(): String {
        val ip = NetworkInfo.getDeviceIp(this) ?: "0.0.0.0"
        return "http://$ip:8080/mjpeg"
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


