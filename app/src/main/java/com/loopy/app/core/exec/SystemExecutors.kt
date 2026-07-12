package com.loopy.app.core.exec

import com.loopy.app.core.material.AppParams
import com.loopy.app.core.material.BrightnessParams
import com.loopy.app.core.material.DimParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.ShellParams
import com.loopy.app.core.material.VarSetParams

/**
 * 시스템 원자들의 실행 규칙.
 *
 * 각각은 하나의 일만 한다. 토글 같은 상태 있는 동작은 여기서 구현하지 않는다.
 * 변수와 조건 블록으로 표현할 수 있고, 그렇게 두면 사용자가 그 조립을 열어보고 배울 수 있다.
 */

object DimExecutor : Executor {
    override val typeId = "screen.dim"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        ctx.io.setDim((material.params as DimParams).on)
        return Flow.Next
    }
}

object BrightnessExecutor : Executor {
    override val typeId = "screen.brightness"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val level = (material.params as BrightnessParams).level
        if (level < 0) {
            ctx.io.putSetting("system", "screen_brightness_mode", "1")
        } else {
            ctx.io.putSetting("system", "screen_brightness_mode", "0")
            ctx.io.putSetting("system", "screen_brightness", level.coerceIn(0, 255).toString())
        }
        return Flow.Next
    }
}

object VarSetExecutor : Executor {
    override val typeId = "var.set"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val p = material.params as VarSetParams
        if (p.name.isBlank()) return Flow.Next
        val value = ctx.scope.expand(p.value)
        if (p.global) ctx.scope.setGlobal(p.name, value) else ctx.scope.setLocal(p.name, value)
        return Flow.Next
    }
}

object AlarmOffExecutor : Executor {
    override val typeId = "alarm.off"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        ctx.io.dismissAlarm()
        return Flow.Next
    }
}

object LaunchAppExecutor : Executor {
    override val typeId = "app.launch"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val pkg = ctx.scope.expand((material.params as AppParams).pkg)
        if (pkg.isNotBlank()) ctx.io.launchApp(pkg)
        return Flow.Next
    }
}

object ShellExecutor : Executor {
    override val typeId = "shell"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val cmd = ctx.scope.expand((material.params as ShellParams).cmd)
        if (cmd.isNotBlank()) {
            val out = ctx.io.shell(cmd)
            ctx.log.add(material.id, "shell: ${out?.take(80) ?: "실패"}")
        }
        return Flow.Next
    }
}

fun registerSystemExecutors() {
    ExecutorRegistry.register(DimExecutor)
    ExecutorRegistry.register(BrightnessExecutor)
    ExecutorRegistry.register(VarSetExecutor)
    ExecutorRegistry.register(AlarmOffExecutor)
    ExecutorRegistry.register(LaunchAppExecutor)
    ExecutorRegistry.register(ShellExecutor)
}
