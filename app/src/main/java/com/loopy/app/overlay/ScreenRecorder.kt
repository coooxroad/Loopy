package com.loopy.app.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

/**
 * MediaProjection 으로 화면을 mp4 로 녹화한다.
 * 매크로 녹화와 동시에 시작/정지하며, 실패해도 매크로 녹화엔 지장 없게 조용히 degrade.
 */
class ScreenRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var vDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    var active = false
        private set
    private var outputPath: String? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { runCatching { stop() } }
    }

    fun start(proj: MediaProjection, outFile: File): Boolean {
        return runCatching {
            projection = proj
            proj.registerCallback(callback, null) // API34: VirtualDisplay 전 필수

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val dpi = metrics.densityDpi
            // 인코더 부하/한계 대비 최대변 1280 으로 축소(짝수 보장)
            val scale = if (maxOf(w, h) > 1280) 1280f / maxOf(w, h) else 1f
            w = ((w * scale).toInt() / 2) * 2
            h = ((h * scale).toInt() / 2) * 2

            val rec = MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(outFile.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(6_000_000)
            rec.prepare()

            vDisplay = proj.createVirtualDisplay(
                "LoopyRec", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null,
            )
            rec.start()
            recorder = rec
            outputPath = outFile.absolutePath
            active = true
            true
        }.getOrElse {
            runCatching { cleanup() }
            active = false
            false
        }
    }

    /** 정지하고 저장된 파일 경로 반환(없으면 null). */
    fun stop(): String? {
        if (!active) return null
        val path = outputPath
        var ok = true
        runCatching { recorder?.stop() }.onFailure { ok = false }
        cleanup()
        active = false
        return if (ok) path else null
    }

    private fun cleanup() {
        runCatching { vDisplay?.release() }; vDisplay = null
        runCatching { recorder?.reset(); recorder?.release() }; recorder = null
        runCatching { projection?.unregisterCallback(callback) }
        runCatching { projection?.stop() }; projection = null
    }
}
