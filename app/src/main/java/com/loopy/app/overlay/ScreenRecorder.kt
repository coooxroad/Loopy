package com.loopy.app.overlay

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 화면 녹화. **세션(MediaProjection)**과 **녹화(VirtualDisplay+MediaRecorder)**를 분리.
 * 세션은 한 번 켜두면 유지되고(권한 1회), 녹화는 여러 번 시작/정지하며 세션을 재사용한다.
 * 저장 위치는 공용 Movies/Loopy (갤러리에서 보임, MediaStore).
 */
class ScreenRecorder(private val context: Context) {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var vDisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null

    var active = false          // 지금 녹화 중인가
        private set
    var startUptime = 0L        // 녹화 start() 직후 uptime (싱크 기준)
        private set
    var currentUri: String? = null
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { runCatching { endSession() } }
    }

    // ── 세션 ──
    fun hasSession() = projection != null

    fun setSession(proj: MediaProjection) {
        endSession() // 기존 것 정리
        projection = proj
        runCatching { proj.registerCallback(callback, null) }
    }

    fun endSession() {
        runCatching { if (active) stopRecording() }
        runCatching { projection?.unregisterCallback(callback) }
        runCatching { projection?.stop() }
        projection = null
    }

    // ── 녹화 ──
    fun startRecording(): Boolean {
        val proj = projection ?: return false
        if (active) return true
        return runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            var w = metrics.widthPixels
            var h = metrics.heightPixels
            val dpi = metrics.densityDpi
            val scale = if (maxOf(w, h) > 1280) 1280f / maxOf(w, h) else 1f
            w = ((w * scale).toInt() / 2) * 2
            h = ((h * scale).toInt() / 2) * 2

            val uri = createOutput() ?: return false
            pendingUri = uri
            currentUri = uri.toString()
            val fd = context.contentResolver.openFileDescriptor(uri, "w") ?: return false
            pfd = fd

            val rec = MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(fd.fileDescriptor)
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
            startUptime = SystemClock.uptimeMillis()
            recorder = rec
            active = true
            true
        }.getOrElse { runCatching { cleanupRecording(false) }; false }
    }

    /** 녹화 정지(세션은 유지). 저장된 콘텐츠 Uri 문자열 반환. */
    fun stopRecording(): String? {
        if (!active) return null
        var ok = true
        runCatching { recorder?.stop() }.onFailure { ok = false }
        val uri = currentUri
        cleanupRecording(ok)
        active = false
        return if (ok) uri else null
    }

    private fun createOutput(): Uri? {
        val name = "loopy_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Loopy")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun cleanupRecording(finalizeOk: Boolean) {
        runCatching { vDisplay?.release() }; vDisplay = null
        runCatching { recorder?.reset(); recorder?.release() }; recorder = null
        runCatching { pfd?.close() }; pfd = null
        val uri = pendingUri
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (finalizeOk) {
                runCatching {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, v, null, null)
                }
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
        pendingUri = null
    }
}
