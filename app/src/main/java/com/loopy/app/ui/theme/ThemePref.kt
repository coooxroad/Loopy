package com.loopy.app.ui.theme

import android.content.Context

/**
 * 테마 설정.
 *
 * 시스템을 따라가는 것이 기본이지만, 사용자가 직접 고를 수 있어야 한다. 이 앱은 게임 위에서
 * 오래 켜두는 도구라 어두운 화면을 선호하는 사람이 있고, 시스템 설정을 바꾸는 것보다
 * 앱 안에서 고르는 편이 빠르다.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemePref {

    private const val FILE = "loopy_prefs"
    private const val KEY = "theme_mode"

    fun get(ctx: Context): ThemeMode {
        val name = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(name!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode.name)
            .apply()
    }
}
