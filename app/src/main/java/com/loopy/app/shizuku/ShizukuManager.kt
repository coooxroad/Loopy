package com.loopy.app.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,   // 바인더 자체가 없음 (Shizuku 앱 미설치/미실행)
    NEEDS_PERMISSION,
    READY,
}

object ShizukuManager {

    private const val PERMISSION_CODE = 1001

    fun state(): ShizukuState {
        if (!Shizuku.pingBinder()) return ShizukuState.NOT_INSTALLED
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else {
            ShizukuState.NEEDS_PERMISSION
        }
    }

    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_CODE) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_CODE)
    }
}
