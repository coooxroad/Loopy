package com.loopy.app.core.exec

import com.loopy.app.core.material.IfParams
import com.loopy.app.core.material.LoopParams
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.WaitParams
import kotlinx.coroutines.delay

/**
 * 내장 타입들의 실행 규칙.
 *
 * 각 실행기는 자기 일만 하고 다음 흐름을 [Flow] 로 알린다. 제어 블록이 자식을 돌릴 때는
 * [Engine.runChildren] 을 재귀 호출하므로, 중첩 구조가 그대로 실행 구조가 된다.
 */

object WaitExecutor : Executor {
    override val typeId = "wait"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val ms = (material.params as WaitParams).ms
        // 통째로 재우면 취소에 늦게 반응한다. 잘게 쪼개 킬 스위치가 즉시 먹히게 한다.
        var left = ms
        while (left > 0) {
            ctx.cancel.throwIfCancelled()
            val step = minOf(left, 50L)
            delay(step)
            left -= step
        }
        return Flow.Next
    }
}

object LoopExecutor : Executor {
    override val typeId = "loop"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val p = material.params as LoopParams
        var i = 0
        while (p.infinite || i < p.count) {
            ctx.cancel.throwIfCancelled()
            ctx.scope.setLocal("_i", i.toString())
            when (Engine.runChildren(material.children, ctx)) {
                Flow.Break -> return Flow.Next
                Flow.Stop -> return Flow.Stop
                else -> Unit // Next, Continue 는 모두 다음 회차로
            }
            i++
        }
        return Flow.Next
    }
}

object IfExecutor : Executor {
    override val typeId = "if"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        val cond = (material.params as IfParams).condition
        return if (Conditions.eval(cond, ctx)) {
            Engine.runChildren(material.children, ctx)
        } else {
            Flow.Next
        }
    }
}

object ParallelExecutor : Executor {
    override val typeId = "parallel"
    override suspend fun run(material: Material, ctx: ExecContext): Flow =
        Engine.runParallel(material.children, ctx)
}

object BuildExecutor : Executor {
    override val typeId = "build"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        // 트리거(HAT)는 실행 대상이 아니라 발동 조건이므로 건너뛴다.
        val body = material.children.filter { it.type.kind != com.loopy.app.core.material.Kind.HAT }
        return when (Engine.runChildren(body, ctx)) {
            Flow.Stop -> Flow.Stop
            else -> Flow.Next
        }
    }
}

object StopExecutor : Executor {
    override val typeId = "stop"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        ctx.io.stopPlayback()
        return Flow.Stop
    }
}

fun registerBuiltinExecutors() {
    ExecutorRegistry.register(WaitExecutor)
    ExecutorRegistry.register(LoopExecutor)
    ExecutorRegistry.register(IfExecutor)
    ExecutorRegistry.register(ParallelExecutor)
    ExecutorRegistry.register(BuildExecutor)
    ExecutorRegistry.register(StopExecutor)
}

/**
 * 조건식 평가.
 *
 * 지금은 변수 비교만 지원한다. 이미지 인식·앱 상태 같은 조건이 추가되면 여기에 얹는다.
 * 문법을 단순하게 유지하는 이유는, 복잡한 식이 필요해지는 순간 스크립트 Material 로
 * 넘기는 편이 낫기 때문이다.
 */
object Conditions {
    private val binary = Regex("""\s*(.+?)\s*(==|!=|>=|<=|>|<)\s*(.+?)\s*""")

    fun eval(expr: String, ctx: ExecContext): Boolean {
        val e = ctx.scope.expand(expr).trim()
        if (e.isEmpty()) return true
        if (e.equals("true", true)) return true
        if (e.equals("false", true)) return false

        val m = binary.matchEntire(e) ?: return false
        val (l, op, r) = m.destructured
        val ln = l.toDoubleOrNull()
        val rn = r.toDoubleOrNull()

        return if (ln != null && rn != null) {
            when (op) {
                "==" -> ln == rn
                "!=" -> ln != rn
                ">" -> ln > rn
                "<" -> ln < rn
                ">=" -> ln >= rn
                "<=" -> ln <= rn
                else -> false
            }
        } else {
            when (op) {
                "==" -> l == r
                "!=" -> l != r
                else -> false
            }
        }
    }
}
