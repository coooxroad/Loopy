package com.loopy.app.data

import com.loopy.app.core.material.BrightnessParams
import com.loopy.app.core.material.DimParams
import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.VarSetParams

/**
 * 앱이 들고 시작하는 Material 들.
 *
 * 여기 있는 것들은 Kotlin 으로 특별 취급되지 않는다. 전부 사용자가 만들 수 있는 것과 똑같은
 * 블록 조립이다. 그래서 사용자가 Deep SLock 을 열어보면 "토글은 변수와 조건으로 만든다"는 것을
 * 코드가 아니라 자기가 쓰는 도구의 언어로 배우게 된다. 기능이 곧 예제다.
 */
object Presets {

    const val DEEP_SLOCK = "preset.deep_slock"

    fun all(): List<Material> = listOf(deepSLock(), brightnessDown())

    /**
     * Deep SLock.
     *
     * 화면을 검게 덮고 밝기를 최소로 내린다. 터치는 통과하므로 매크로는 계속 돈다.
     * 다시 실행하면 원래대로. 이 토글은 전역 변수 하나와 조건 블록으로 만들어진다.
     */
    private fun deepSLock(): Material = Material(
        id = DEEP_SLOCK,
        typeId = "build",
        params = NoParams,
        meta = Meta(
            name = "Deep SLock",
            note = "화면을 덮고 밝기를 낮춘다. 터치는 통과해서 매크로는 계속 동작한다.",
        ),
        children = listOf(
            // 두 조건이 같은 변수를 보므로, 먼저 현재 상태를 지역 변수에 담아둔다.
            // 그러지 않으면 첫 조건이 값을 바꾼 뒤 두 번째 조건이 그것을 보고 바로 되돌린다.
            Material(
                id = "$DEEP_SLOCK.snap",
                typeId = "var.set",
                params = VarSetParams("was", "{slock}", false),
                meta = Meta(name = "현재 상태 기억"),
            ),
            Material(
                id = "$DEEP_SLOCK.if_off",
                typeId = "if",
                params = IfParams("{was} != 1"),
                meta = Meta(name = "꺼져 있으면 켠다"),
                children = listOf(
                    Material("$DEEP_SLOCK.dim_on", "screen.dim", DimParams(true)),
                    Material("$DEEP_SLOCK.dark", "screen.brightness", BrightnessParams(0)),
                    Material("$DEEP_SLOCK.set_on", "var.set", VarSetParams("slock", "1", true)),
                ),
            ),
            Material(
                id = "$DEEP_SLOCK.if_on",
                typeId = "if",
                params = IfParams("{was} == 1"),
                meta = Meta(name = "켜져 있으면 끈다"),
                children = listOf(
                    Material("$DEEP_SLOCK.dim_off", "screen.dim", DimParams(false)),
                    Material("$DEEP_SLOCK.auto", "screen.brightness", BrightnessParams(-1)),
                    Material("$DEEP_SLOCK.set_off", "var.set", VarSetParams("slock", "0", true)),
                ),
            ),
        ),
    )

    /** 밝기만 내린다. Deep SLock 이 과할 때. */
    private fun brightnessDown(): Material = Material(
        id = "preset.dim_only",
        typeId = "screen.brightness",
        params = BrightnessParams(10),
        meta = Meta(name = "밝기 낮추기"),
    )
}
