package com.airtv.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the Annex-B H.264/H.265 access units produced by the AirPlay mirror stream
 * straight onto a [Surface].
 *
 * Frames are submitted from the native mirror thread and rendered as soon as the decoder
 * produces them: mirroring is a live stream, so the lowest latency beats smooth pacing.
 */
class VideoPipeline {

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var isH265 = false
    private var codecConfig: ByteArray? = null
    private var needKeyFrame = true
    private var sourceWidth = DEFAULT_WIDTH
    private var sourceHeight = DEFAULT_HEIGHT
    private var scratch = ByteArray(256 * 1024)

    private var drainThread: Thread? = null

    @Volatile
    private var draining = false

    @Volatile
    var framesRendered = 0L
        private set

    @Volatile
    var framesDropped = 0L
        private set

    /** Attaches (or detaches, with null) the output surface. */
    fun setSurface(newSurface: Surface?) {
        synchronized(lock) {
            if (surface === newSurface) return
            releaseCodecLocked()
            surface = newSurface
            startCodecIfPossibleLocked()
        }
    }

    fun setCodec(h265: Boolean) {
        synchronized(lock) {
            if (isH265 == h265 && codec != null) return
            isH265 = h265
            codecConfig = null
            releaseCodecLocked()
        }
    }

    fun setSourceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            if (width == sourceWidth && height == sourceHeight) return
            sourceWidth = width
            sourceHeight = height
            // The decoder adapts from the in-band parameter sets; only a fresh session
            // needs the new defaults, so nothing to restart here.
        }
    }

    /** Submits one access unit. The buffer contents are copied before returning. */
    fun submit(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (length <= 0) return
        synchronized(lock) {
            if (scratch.size < length) scratch = ByteArray(length * 2)
            data.position(0)
            data.get(scratch, 0, length)

            if (NalUnits.isCodecConfig(scratch, length, isH265)) {
                val config = scratch.copyOf(length)
                if (!config.contentEquals(codecConfig)) {
                    Log.i(TAG, "codec config (${NalUnits.describe(config, length, isH265)})")
                    codecConfig = config
                    releaseCodecLocked()
                }
                startCodecIfPossibleLocked()
                return
            }

            val activeCodec = codec ?: run {
                framesDropped++
                return
            }
            if (needKeyFrame) {
                if (!NalUnits.isKeyFrame(scratch, length, isH265)) {
                    framesDropped++
                    return
                }
                needKeyFrame = false
            }
            try {
                val index = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) {
                    framesDropped++
                    return
                }
                val input = activeCodec.getInputBuffer(index) ?: return
                input.clear()
                input.put(scratch, 0, length)
                activeCodec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "decoder rejected input, restarting", e)
                releaseCodecLocked()
                startCodecIfPossibleLocked()
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            try {
                codec?.flush()
                codec?.start()
                needKeyFrame = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "flush failed, restarting decoder", e)
                releaseCodecLocked()
                startCodecIfPossibleLocked()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            releaseCodecLocked()
            codecConfig = null
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { codec != null }

    private fun startCodecIfPossibleLocked() {
        if (codec != null) return
        val outputSurface = surface ?: return
        val config = codecConfig ?: return
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        try {
            val format = MediaFormat.createVideoFormat(mime, sourceWidth, sourceHeight).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                    setInteger(MediaFormat.KEY_OPERATING_RATE, MAX_FPS)
                }
            }
            val created = MediaCodec.createDecoderByType(mime)
            created.configure(format, outputSurface, null, 0)
            created.start()
            codec = created
            needKeyFrame = true
            startDrainLocked(created)
            Log.i(TAG, "decoder started: $mime ${sourceWidth}x$sourceHeight")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start decoder for $mime", e)
            codec = null
        }
    }

    private fun startDrainLocked(activeCodec: MediaCodec) {
        draining = true
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (draining) {
                try {
                    val index = activeCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                    when {
                        index >= 0 -> {
                            activeCodec.releaseOutputBuffer(index, true)
                            framesRendered++
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            Log.i(TAG, "output format: ${activeCodec.outputFormat}")
                    }
                } catch (e: IllegalStateException) {
                    // codec released underneath us
                    break
                }
            }
        }, "airtv-video-drain").apply { start() }
    }

    private fun releaseCodecLocked() {
        draining = false
        drainThread?.let {
            if (it !== Thread.currentThread()) it.join(DRAIN_JOIN_TIMEOUT_MS)
        }
        drainThread = null
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        needKeyFrame = true
    }

    private companion object {
        const val TAG = "VideoPipeline"
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val MAX_FPS = 60
        const val INPUT_TIMEOUT_US = 100_000L
        const val OUTPUT_TIMEOUT_US = 20_000L
        const val DRAIN_JOIN_TIMEOUT_MS = 500L
    }
}
