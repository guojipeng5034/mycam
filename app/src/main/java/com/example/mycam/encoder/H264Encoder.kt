package com.example.mycam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val preferredEncoderName: String? = null,
    private val onEncodedNal: (ByteArray, Long, Boolean) -> Unit,
    private val onCodecConfig: (ByteArray?, ByteArray?) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var useByteBufferInput: Boolean = false

    fun getInputSurface(): Surface? = inputSurface

    fun enableByteBufferInput() {
        useByteBufferInput = true
    }

    fun start() {
        val encW = if (width % 2 == 0) width else width - 1
        val encH = if (height % 2 == 0) height else height - 1
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH)
        if (useByteBufferInput) {
            // Prefer NV12 (YUV420SP) for ByteBuffer path
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        } else {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        // Prefer baseline for compatibility
        try { format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) } catch (_: Throwable) {}
        try { format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3) } catch (_: Throwable) {}

        val encoderName = preferredEncoderName ?: if (useByteBufferInput) chooseAvcEncoderForByteBuffer() else chooseAvcEncoder()
        val c = if (encoderName != null) MediaCodec.createByCodecName(encoderName)
                else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (!useByteBufferInput) {
            inputSurface = c.createInputSurface()
        }
        c.start()
        codec = c
        // Force an IDR at start
        try {
            val params = android.os.Bundle()
            params.putInt("request-sync", 0)
            c.setParameters(params)
        } catch (_: Throwable) {}
        scope.launch { drain() }
    }

    fun requestKeyFrame() {
        val c = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt("request-sync", 0)
            c.setParameters(params)
        } catch (_: Throwable) {}
    }

    private fun chooseAvcEncoder(): String? {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }.sortedBy { info -> if (info.name.startsWith("c2.")) 0 else 1 }
        for (info in candidates) {
            val caps = try { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: Exception) { null } ?: continue
            if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                return info.name
            }
        }
        return null
    }

    private fun chooseAvcEncoderForByteBuffer(): String? {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }.sortedBy { info -> if (info.name.startsWith("c2.")) 0 else 1 }
        for (info in candidates) {
            val caps = try { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (_: Exception) { null } ?: continue
            if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ||
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ||
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                return info.name
            }
        }
        return null
    }

    fun stop() {
        scope.cancel()
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        codec = null
            inputSurface = null
    }

    private fun drain() {
        val c = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")
                    val csd1 = fmt.getByteBuffer("csd-1")
                    val sps = csd0?.let { bb -> bb.rewind(); ByteArray(bb.remaining()).also { bb.get(it) } }
                    val pps = csd1?.let { bb -> bb.rewind(); ByteArray(bb.remaining()).also { bb.get(it) } }
                    onCodecConfig(sps, pps)
                }
                outIndex >= 0 -> {
                    val tmpBuffer = c.getOutputBuffer(outIndex)
                    if (tmpBuffer == null) {
                        c.releaseOutputBuffer(outIndex, false)
                        continue
                    }
                    val outBuffer = tmpBuffer
                    if (bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        val frame = ByteArray(bufferInfo.size)
                        outBuffer.get(frame)
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        try {
                            // Try length-prefixed AVC parsing
                            var pos = 0
                            var parsedAny = false
                            while (pos + 4 <= frame.size) {
                                val length = ((frame[pos].toInt() and 0xFF) shl 24) or ((frame[pos + 1].toInt() and 0xFF) shl 16) or ((frame[pos + 2].toInt() and 0xFF) shl 8) or (frame[pos + 3].toInt() and 0xFF)
                                if (length <= 0) break
                                val start = pos + 4
                                val end = start + length
                                if (end > frame.size) break
                                val nal = frame.copyOfRange(start, end)
                                onEncodedNal(nal, bufferInfo.presentationTimeUs, isKey)
                                parsedAny = true
                                pos = end
                            }
                            if (!parsedAny) {
                                // Fallback to AnnexB start-code parsing
                                val ranges = extractAnnexBRanges(frame)
                                for (r in ranges) {
                                    val startIdx = r.first
                                    val endExclusive = r.last + 1
                                    if (startIdx < endExclusive) {
                                        val nal = frame.copyOfRange(startIdx, endExclusive)
                                        onEncodedNal(nal, bufferInfo.presentationTimeUs, isKey)
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            // ignore malformed buffer
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun extractAnnexBRanges(buf: ByteArray): List<IntRange> {
        val indices = ArrayList<Int>()
        var i = 0
        while (i + 3 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1 ||
                (i + 4 < buf.size && buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1)) {
                indices.add(i)
                i += 3
            }
            i++
        }
        if (indices.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        for (j in 0 until indices.size) {
            val start = if (buf.getOrNull(indices[j] + 2)?.toInt() == 1) indices[j] + 3 else indices[j] + 4
            val end = if (j + 1 < indices.size) indices[j + 1] else buf.size
            if (start < end) ranges.add(start until end)
        }
        return ranges
    }

    // Feed NV21 as NV12 (Y + UV) into encoder for ByteBuffer mode
    fun queueYuvNv21(nv21: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        if (!useByteBufferInput) return
        val inIndex = c.dequeueInputBuffer(10_000)
        if (inIndex < 0) return
        val inBuf = c.getInputBuffer(inIndex) ?: run {
            c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            return
        }
        val ySize = width * height
        val uvSize = ySize / 2
        if (nv21.size < ySize + uvSize) {
            c.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            return
        }
        inBuf.clear()
        // Y plane
        inBuf.put(nv21, 0, ySize)
        // Convert VU to UV inline
        var i = 0
        while (i < uvSize) {
            val v = nv21[ySize + i]
            val u = nv21[ySize + i + 1]
            inBuf.put(u)
            inBuf.put(v)
            i += 2
        }
        inBuf.flip()
        c.queueInputBuffer(inIndex, 0, ySize + uvSize, ptsUs, 0)
    }
}


