package com.loopy.app.core.exec

import com.loopy.app.core.material.Material
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
        val ms = material.params.long("ms", 1000L)
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
        val count = material.params.int("count", 1)
        val infinite = material.params.bool("infinite", false)
        var i = 0
        while (infinite || i < count) {
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
        val cond = material.params.str("condition")
        return if (Conditions.eval(cond, ctx)) {
            Engine.runChildren(material.children, ctx)
        } else {
            Flow.Next
        }
    }
}

object ParallelExecutor : Executor {
    override val typeId = "parallel"
    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        // 갈래가 전부 "단순 터치"(터치 하나, 또는 lead 대기 + 터치)면 한 번의 멀티터치로 묶는다.
        // 손가락을 갈래마다 따로 주입하면 서로 취소되어 한 손가락만 찍히기 때문이다.
        val strokes = material.children.map { simpleStroke(it) }
        if (strokes.isNotEmpty() && strokes.all { it != null }) {
            ctx.io.playStrokesById(strokes.filterNotNull())
            return Flow.Next
        }
        // 복잡한 갈래(루프·조건 등)는 진짜 동시 실행으로.
        return Engine.runParallel(material.children, ctx)
    }

    /** 갈래에서 (시작 지연 ms, strokeId) 를 뽑는다. 단순 터치가 아니면 null. */
    private fun simpleStroke(branch: Material): Pair<Long, String>? {
        if (branch.typeId == "touch") {
            return 0L to branch.params.str("strokeId")
        }
        if (branch.typeId == "build" && branch.children.size == 2) {
            val a = branch.children[0]
            val b = branch.children[1]
            if (a.typeId == "wait" && b.typeId == "touch") {
                return a.params.long("ms") to b.params.str("strokeId")
            }
        }
        return null
    }
}

object BuildExecutor : Executor {
    override val typeId = "build"

    /** 순환 참조 방어. A→B→A 로 무한히 부르지 못하게 불러오기 깊이를 제한한다. */
    private const val MAX_LOAD_DEPTH = 50

    override suspend fun run(material: Material, ctx: ExecContext): Flow {
        // 대상 빌드가 지정돼 있으면(불러오기) 저장소에서 찾아 실행한다. 없으면 자기 몸을 실행한다.
        // 이 분기 덕분에 "빌드 실행" 블록이 다른 저장된 빌드를 부를 수 있다(빌드 불러오기).
        val targetId = material.params.str("buildId")
        if (targetId.isNotEmpty()) {
            if (ctx.loadDepth >= MAX_LOAD_DEPTH) {
                ctx.log.add(material.id, "빌드 불러오기 깊이 초과: $targetId")
                return Flow.Next
            }
            val target = ctx.materials.find(targetId)
            if (target == null) {
                ctx.log.add(material.id, "빌드 없음: $targetId")
                return Flow.Next
            }
            return Engine.run(target, ctx.copy(loadDepth = ctx.loadDepth + 1))
        }

        val kids = material.children
        // 캔버스: 자식이 전부 덩어리(build)면, 모자로 시작하는 덩어리만 실행한다.
        // 모자 없는 덩어리(조각)는 저장만 되고 돌지 않는다. (스크래치와 같은 최적화)
        val isCanvas = kids.isNotEmpty() && kids.all { it.typeId == "build" }
        if (isCanvas) {
            for (clump in kids) {
                if (clump.children.firstOrNull()?.kind != com.loopy.app.core.material.Kind.HAT) continue
                val body = clump.children.filter { it.kind != com.loopy.app.core.material.Kind.HAT }
                if (Engine.runChildren(body, ctx) == Flow.Stop) return Flow.Stop
            }
            return Flow.Next
        }
        // 시퀀스(레거시/중첩/갈래): 트리거(HAT)는 발동 조건이므로 건너뛰고 순서대로 실행.
        val body = kids.filter { it.kind != com.loopy.app.core.material.Kind.HAT }
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
