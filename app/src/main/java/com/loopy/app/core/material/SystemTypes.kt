package com.loopy.app.core.material

/** 시스템 원자 Material. 파라미터는 ParamBag, 스키마는 BlockDef(Field). */

object DimType : MaterialType {
    override val id = "screen.dim"; override val kind = Kind.ACTION; override val label = "화면 가리기"
    override val input = ParamInput.INLINE
}

object BrightnessType : MaterialType {
    override val id = "screen.brightness"; override val kind = Kind.ACTION; override val label = "밝기"
    override val input = ParamInput.INLINE
}

object VarSetType : MaterialType {
    override val id = "var.set"; override val kind = Kind.ACTION; override val label = "변수 설정"
    override val input = ParamInput.SHEET
}

object AlarmOffType : MaterialType {
    override val id = "alarm.off"; override val kind = Kind.ACTION; override val label = "알람 끄기"
}

object LaunchAppType : MaterialType {
    override val id = "app.launch"; override val kind = Kind.ACTION; override val label = "앱 실행"
    override val input = ParamInput.SHEET
}

object ShellType : MaterialType {
    override val id = "shell"; override val kind = Kind.ACTION; override val label = "셸 명령"
    override val input = ParamInput.CODE
}

object ManualTriggerType : MaterialType {
    override val id = "trigger.manual"; override val kind = Kind.HAT; override val label = "실행하면"
}

fun registerSystemTypes() {
    MaterialRegistry.register(ManualTriggerType)
    MaterialRegistry.register(DimType)
    MaterialRegistry.register(BrightnessType)
    MaterialRegistry.register(VarSetType)
    MaterialRegistry.register(AlarmOffType)
    MaterialRegistry.register(LaunchAppType)
    MaterialRegistry.register(ShellType)
}
