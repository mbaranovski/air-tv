package com.airtv.receiver.media

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/** Builds the decoder input formats used by [VideoPipeline]. */
object VideoFormats {

    const val MAX_FPS = 60

    /**
     * @param tuned adds the optional low-latency / realtime hints. They measurably reduce
     * mirroring lag, but they are advisory and some decoders refuse to configure with them,
     * so the pipeline retries with `tuned = false`.
     */
    fun create(
        codecConfig: ByteArray,
        width: Int,
        height: Int,
        isH265: Boolean,
        tuned: Boolean,
    ): MediaFormat {
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
            if (tuned) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                setInteger(MediaFormat.KEY_OPERATING_RATE, MAX_FPS)
            }
        }
    }
}
