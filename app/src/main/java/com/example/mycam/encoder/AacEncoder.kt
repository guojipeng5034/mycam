package com.example.mycam.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlinx.coroutines.SupervisorJob
import kotlin.math.min

class AacEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 64000,
    private val onEncodedAac: (ByteArray, Long) -> Unit
) {
    private var codec: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        // Hint a reasonable max input size to avoid tiny buffers on some devices
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        recorder?.startRecording()
        scope.launch { loop(minBuf) }
    }

    fun stop() {
        scope.cancel()
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    private fun loop(bufSize: Int) {
        val c = codec ?: return
        val rec = recorder ?: return
        val pcm = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            try {
                val read = rec.read(pcm, 0, pcm.size)
                if (read > 0) {
                    var offset = 0
                    while (offset < read) {
                        val inIndex = c.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                        val tmp = c.getInputBuffer(inIndex)
                        if (tmp == null) {
                            c.queueInputBuffer(inIndex, 0, 0, System.nanoTime() / 1000, 0)
                            // give chance to drain outputs and retry next loop
                            break
                        }
                        val inBuf: ByteBuffer = tmp
                            inBuf.clear()
                            val cap = inBuf.capacity()
                            val toCopy = min(read - offset, cap)
                            inBuf.put(pcm, offset, toCopy)
                            val ptsUs = System.nanoTime() / 1000
                            c.queueInputBuffer(inIndex, 0, toCopy, ptsUs, 0)
                            offset += toCopy
                        } else {
                            // No input buffer available now; try draining outputs to free buffers
                            break
                        }
                    }
                }
                var outIndex = c.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0) {
                    val out = c.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val aac = ByteArray(info.size)
                        out.get(aac)
                        onEncodedAac(aac, info.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    outIndex = c.dequeueOutputBuffer(info, 0)
                }
            } catch (_: Throwable) {
                // swallow to avoid crashing the app; encoder will continue
            }
        }
    }
}


