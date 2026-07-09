package com.loopy.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.loopy.app.overlay.OverlayService

/**
 * 화면 녹화 권한(MediaProjection) 팝업용 투명 액티비티.
 * "허용" 즉시 결과를 OverlayService 로 넘겨 그 순간부터 매크로+영상을 시작한다.
 * (녹화 버튼 탭·허용 탭은 이 시점 전이라 매크로에 안 찍힘)
 */
class ProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), REQ) }
            .onFailure {
                forward(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_START_MACRO_ONLY })
                finish()
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val svc = Intent(this, OverlayService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            svc.action = OverlayService.ACTION_START_VIDEO
            svc.putExtra(OverlayService.EX_CODE, resultCode)
            svc.putExtra(OverlayService.EX_DATA, data)
        } else {
            svc.action = OverlayService.ACTION_START_MACRO_ONLY
        }
        forward(svc)
        finish()
    }

    private fun forward(svc: Intent) {
        runCatching { ContextCompat.startForegroundService(this, svc) }
    }

    companion object { private const val REQ = 7001 }
}
