package com.loopy.app.core.material

/**
 * 시스템 원자 Material.
 *
 * 더 이상 쪼갤 수 없는 동작들. 이것들을 조립해 Deep SLock 같은 기능이 만들어진다.
 * 조립은 사용자가 열어볼 수 있는 빌드로 두므로, 앱의 기능 자체가 학습 자료가 된다.
 */

// ── 화면 가리기 ──

data class DimParams(val on: Boolean) : Params {
    override fun toMap() = mapOf("on" to on)
}

/** 화면 위에 검은 레이어. 터치는 통과시켜 매크로가 계속 동작한다. */
object DimType : MaterialType {
    override val id = "screen.dim"
    override val kind = Kind.ACTION
    override val label = "화면 가리기"
    override val input = ParamInput.INLINE
    override fun parse(map: Map<String, Any?>) = DimParams(map["on"] as? Boolean ?: true)
}

// ── 밝기 ──

/** -1 이면 자동(시스템에 되돌림). 0~255. */
data class BrightnessParams(val level: Int) : Params {
    override fun toMap() = mapOf("level" to level)
}

object BrightnessType : MaterialType {
    override val id = "screen.brightness"
    override val kind = Kind.ACTION
    override val label = "밝기"
    override val input = ParamInput.INLINE
    override fun parse(map: Map<String, Any?>) =
        BrightnessParams((map["level"] as? Number)?.toInt() ?: 0)
}

// ── 변수 ──

/**
 * 변수 설정.
 *
 * 값에 {name} 을 쓰면 다른 변수를 참조한다. 산술은 지원하지 않는다. 복잡한 계산이
 * 필요해지는 순간은 스크립트 Material 이 맡을 자리다.
 */
data class VarSetParams(val name: String, val value: String, val global: Boolean) : Params {
    override fun toMap() = mapOf("name" to name, "value" to value, "global" to global)
}

object VarSetType : MaterialType {
    override val id = "var.set"
    override val kind = Kind.ACTION
    override val label = "변수 설정"
    override val input = ParamInput.SHEET
    override fun parse(map: Map<String, Any?>) = VarSetParams(
        name = map["name"] as? String ?: "",
        value = map["value"] as? String ?: "",
        global = map["global"] as? Boolean ?: false,
    )
}

// ── 알람 ──

/** 울리고 있는 알람을 끈다. 자동화의 흔한 요구라 원자로 둔다. */
object AlarmOffType : MaterialType {
    override val id = "alarm.off"
    override val kind = Kind.ACTION
    override val label = "알람 끄기"
    override fun parse(map: Map<String, Any?>) = NoParams
}

// ── 앱 ──

data class AppParams(val pkg: String) : Params {
    override fun toMap() = mapOf("pkg" to pkg)
}

object LaunchAppType : MaterialType {
    override val id = "app.launch"
    override val kind = Kind.ACTION
    override val label = "앱 실행"
    override val input = ParamInput.SHEET
    override fun parse(map: Map<String, Any?>) = AppParams(map["pkg"] as? String ?: "")
}

// ── 셸 ──

data class ShellParams(val cmd: String) : Params {
    override fun toMap() = mapOf("cmd" to cmd)
}

/**
 * 임의 셸 명령.
 *
 * Shizuku 가 ADB 권한을 주므로 접근성 서비스 기반 앱이 못 하는 일을 여기서 할 수 있다.
 * 전용 Material 이 아직 없는 기능의 통로이자, 고급 사용자의 탈출구다.
 */
object ShellType : MaterialType {
    override val id = "shell"
    override val kind = Kind.ACTION
    override val label = "셸 명령"
    override val input = ParamInput.CODE
    override fun parse(map: Map<String, Any?>) = ShellParams(map["cmd"] as? String ?: "")
}

fun registerSystemTypes() {
    MaterialRegistry.register(DimType)
    MaterialRegistry.register(BrightnessType)
    MaterialRegistry.register(VarSetType)
    MaterialRegistry.register(AlarmOffType)
    MaterialRegistry.register(LaunchAppType)
    MaterialRegistry.register(ShellType)
}
