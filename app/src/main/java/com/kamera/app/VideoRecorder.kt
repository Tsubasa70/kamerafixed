package com.kamera.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor

/**
 * Sesli video kaydi. Goruntu, GlPipeline'in renk profiliyle isledigi kareleri
 * MediaRecorder'in giris yuzeyinden alir (VIDEO_SOURCE_SURFACE); ses mikrofondan gelir.
 */
class VideoRecorder(
    private val ctx: Context,
    private val width: Int,
    private val height: Int,
    private val fd: FileDescriptor,
    private val withAudio: Boolean
) {
    private var rec: MediaRecorder? = null

    fun prepare(): Surface {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(fd)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(width, height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(if (width * height >= 1920 * 1080) 24_000_000 else 12_000_000)
        if (withAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(2)
        }
        r.prepare()
        rec = r
        return r.surface
    }

    fun start() {
        rec?.start()
    }

    /** true: dosya gecerli; false: kayit cok kisa/bozuk. */
    fun stop(): Boolean {
        val r = rec ?: return false
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            Log.w("KameraApp", "MediaRecorder.stop: $e")
            ok = false
        }
        try {
            r.reset()
            r.release()
        } catch (_: Exception) {
        }
        rec = null
        return ok
    }
}
