package com.mcgeelee.photos.ml

import com.mcgeelee.photos.localization.RuntimeMessage
import com.mcgeelee.photos.localization.runtimeText
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Decodes audio files and video soundtracks using platform codecs, without loading a whole file. */
class AudioDecoder(context: Context) {
    private val appContext = context.applicationContext

    /** Returns the number of emitted windows, or zero when the media has no audio track. */
    suspend fun decode(
        uri: Uri,
        startMs: Long = 0,
        endMs: Long? = null,
        chunkDurationMs: Int = 10_000,
        overlapMs: Int = 1_000,
        onChunk: suspend (AudioChunk) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        require(startMs >= 0 && (endMs == null || endMs > startMs))
        require(chunkDurationMs in 1_000..30_000 && overlapMs in 0 until chunkDurationMs)
        val startUs = Math.multiplyExact(startMs, 1000L)
        val endUs = endMs?.let { Math.multiplyExact(it, 1000L) } ?: Long.MAX_VALUE
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(appContext, uri, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext 0
            extractor.selectTrack(audioTrack)
            // A fresh AAC decoder needs preceding packets to rebuild its overlap/priming state.
            // Seeking directly to the checkpoint can otherwise drop the beginning of that window
            // and shift every subsequent segment key. Decode a short pre-roll, then trim PCM at startUs.
            if (startUs > 0) extractor.seekTo((startUs - 1_000_000L).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            started = true

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val resampler = MonoResampler(startUs, endUs)
            val windows = AudioWindowAccumulator(chunkDurationMs, overlapMs, onChunk)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgressMs = SystemClock.elapsedRealtime()

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index))
                        val timeUs = extractor.sampleTime
                        // AAC priming packets can have negative presentation timestamps. Only an
                        // exhausted extractor (track index -1), or the requested end, signals EOF.
                        // Feed priming into the codec; MonoResampler trims decoded PCM before startUs.
                        if (extractor.sampleTrackIndex < 0 || timeUs >= endUs) {
                            decoder.queueInputBuffer(index, 0, 0, maxOf(timeUs, 0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val count = extractor.readSampleData(input, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, timeUs, 0)
                                extractor.advance()
                            }
                        }
                        lastProgressMs = SystemClock.elapsedRealtime()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(sampleRate > 0 && channels > 0) { runtimeText(RuntimeMessage.AUDIO_FORMAT).encode() }
                        lastProgressMs = SystemClock.elapsedRealtime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        check(SystemClock.elapsedRealtime() - lastProgressMs < 30_000) { runtimeText(RuntimeMessage.AUDIO_TIMEOUT).encode() }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytesPerSample = bytesPerSample(encoding)
                                val frames = output.remaining() / (channels * bytesPerSample)
                                for (frame in 0 until frames) {
                                    if (frame % 1024 == 0) currentCoroutineContext().ensureActive()
                                    var sum = 0f
                                    repeat(channels) { sum += readSample(output, encoding) }
                                    val timeUs = info.presentationTimeUs + frame * 1_000_000.0 / sampleRate
                                    if (timeUs >= endUs) {
                                        outputEnded = true
                                        break
                                    }
                                    resampler.accept((sum / channels).coerceIn(-1f, 1f), timeUs, windows::accept)
                                }
                            }
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                        lastProgressMs = SystemClock.elapsedRealtime()
                    }
                }
            }
            windows.flush()
            windows.count
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> error(runtimeText(RuntimeMessage.PCM_UNSUPPORTED, encoding).encode())
    }

    private fun readSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it else 0f }
        AudioFormat.ENCODING_PCM_32BIT -> (buffer.int / 2147483648.0).toFloat()
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val low = buffer.get().toInt() and 0xff
            val mid = buffer.get().toInt() and 0xff
            val high = buffer.get().toInt()
            ((high shl 16) or (mid shl 8) or low) / 8388608f
        }
        else -> error(runtimeText(RuntimeMessage.PCM_UNSUPPORTED, encoding).encode())
    }
}
