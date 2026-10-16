package com.moise.laniakea.tools

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a section out of any audio file the platform can decode and writes it in one of three formats:
 *  - WAV  (lossless PCM, works everywhere)
 *  - M4A  (AAC-LC, best ringtone compatibility)
 *  - OGG  (Opus, Android 10+)
 */
object AudioCutter {

    enum class Format(val extension: String, val mime: String) {
        WAV("wav", "audio/x-wav"),
        M4A("m4a", "audio/mp4"),
        OGG("ogg", "audio/ogg"),
    }

    data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    const val MAX_CLIP_MS = 5 * 60_000L

    fun isSupported(format: Format) = format != Format.OGG || Build.VERSION.SDK_INT >= 29

    /** Decode [startMs, endMs] to 16-bit PCM. */
    fun decode(context: Context, uri: Uri, startMs: Long, endMs: Long): Pcm {
        require(endMs > startMs) { "Empty selection" }
        require(endMs - startMs <= MAX_CLIP_MS) { "Selection too long (max 5 minutes)" }

        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val fmt = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val startUs = startMs * 1000
        val endUs = endMs * 1000

        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0 || extractor.sampleTime > endUs) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && info.presentationTimeUs in startUs until endUs) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(bb.remaining()); bb.get(shorts)
        return Pcm(shorts, rate, ch)
    }

    /** Linear fade in/out over the clip edges, in place. */
    fun applyFades(pcm: Pcm, fadeInMs: Int, fadeOutMs: Int): Pcm {
        val frames = pcm.samples.size / pcm.channels
        val fi = (fadeInMs / 1000f * pcm.sampleRate).toInt().coerceAtMost(frames)
        val fo = (fadeOutMs / 1000f * pcm.sampleRate).toInt().coerceAtMost(frames)
        for (f in 0 until frames) {
            var g = 1f
            if (f < fi) g = minOf(g, f.toFloat() / fi)
            if (f >= frames - fo) g = minOf(g, (frames - f).toFloat() / fo)
            if (g < 1f) for (c in 0 until pcm.channels) {
                val idx = f * pcm.channels + c
                pcm.samples[idx] = (pcm.samples[idx] * g).toInt().toShort()
            }
        }
        return pcm
    }

    fun encode(pcm: Pcm, format: Format, target: File) {
        when (format) {
            Format.WAV -> writeWav(pcm, target)
            Format.M4A -> encodeWithCodec(pcm, "audio/mp4a-latm", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, target, 128_000)
            Format.OGG -> {
                check(Build.VERSION.SDK_INT >= 29) { "OGG/Opus export needs Android 10+" }
                val resampled = if (pcm.sampleRate == 48_000) pcm else resample(pcm, 48_000)
                encodeWithCodec(resampled, "audio/opus", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, target, 96_000)
            }
        }
    }

    private fun writeWav(pcm: Pcm, target: File) {
        val dataLen = pcm.samples.size * 2
        RandomAccessFile(target, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(pcm.channels.toShort())
            h.putInt(pcm.sampleRate).putInt(pcm.sampleRate * pcm.channels * 2)
            h.putShort((pcm.channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray()).putInt(dataLen)
            f.write(h.array())
            val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            body.asShortBuffer().put(pcm.samples)
            f.write(body.array())
        }
    }

    private fun resample(pcm: Pcm, newRate: Int): Pcm {
        val inFrames = pcm.samples.size / pcm.channels
        val outFrames = (inFrames.toLong() * newRate / pcm.sampleRate).toInt()
        val out = ShortArray(outFrames * pcm.channels)
        for (f in 0 until outFrames) {
            val src = f.toDouble() * pcm.sampleRate / newRate
            val i0 = src.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val t = (src - i0).toFloat()
            for (c in 0 until pcm.channels) {
                val a = pcm.samples[i0 * pcm.channels + c]; val b = pcm.samples[i1 * pcm.channels + c]
                out[f * pcm.channels + c] = (a + (b - a) * t).toInt().toShort()
            }
        }
        return Pcm(out, newRate, pcm.channels)
    }

    private fun encodeWithCodec(pcm: Pcm, mime: String, muxerFormat: Int, target: File, bitrate: Int) {
        val ch = pcm.channels.coerceIn(1, 2)
        val src = if (pcm.channels == ch) pcm else downmix(pcm)
        val fmt = MediaFormat.createAudioFormat(mime, src.sampleRate, ch).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (mime == "audio/mp4a-latm") setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        if (target.exists()) target.delete()
        val muxer = MediaMuxer(target.absolutePath, muxerFormat)
        var muxTrack = -1
        var muxStarted = false

        val bytes = ByteBuffer.allocate(src.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(src.samples)
        val data = bytes.array()
        var offset = 0
        var ptsUs = 0L
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = minOf(buf.capacity(), data.size - offset)
                    if (n <= 0) {
                        codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buf.clear(); buf.put(data, offset, n)
                        codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                        offset += n
                        ptsUs += (n / 2 / ch).toLong() * 1_000_000L / src.sampleRate
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(muxTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release()
        if (muxStarted) muxer.stop()
        muxer.release()
    }

    private fun downmix(pcm: Pcm): Pcm {
        val frames = pcm.samples.size / pcm.channels
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = pcm.samples[f * pcm.channels]
            out[f * 2 + 1] = pcm.samples[f * pcm.channels + 1]
        }
        return Pcm(out, pcm.sampleRate, 2)
    }
}
