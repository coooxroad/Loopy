#!/data/data/com.termux/files/usr/bin/bash
# Phase 0-1: 2/2
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi
cat > "app/src/main/java/com/loopy/app/core/exec/Engine.kt" << 'LOOPY_EOF'
package com.loopy.app.core.exec

import com.loopy.app.core.io.Io
import com.loopy.app.core.material.Material
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실행 중 흐름 제어.
 *
 * 실행기는 자기 일을 끝낸 뒤 다음에 무엇을 할지 반환한다. 제어 블록(반복·조건)이
 * 이 값을 보고 흐름을 바꾼다. 점프 대신 이 방식을 쓰는 이유는, 중첩 구조를
 * 그대로 따라가야 스크래치식 가지치기가 코드와 UI 양쪽에서 같은 모양이 되기 때문이다.
 */
sealed interface Flow {
    /** 다음 블록으로. */
    object Next : Flow

    /** 가장 가까운 반복을 빠져나감. */
    object Break : Flow

    /** 가장 가까운 반복의 다음 회차로. */
    object Continue : Flow

    /** 실행 전체 종료. 킬 스위치. */
    object Stop : Flow
}

/** 어디서든 실행을 멈출 수 있어야 한다. 킬 스위치와 사용자 취소가 같은 신호를 쓴다. */
class CancelSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean get() = cancelled.get()

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("실행 취소됨")
    }
}

/**
 * 변수 스코프.
 *
 * 지역 변수는 빌드 1회 실행 동안만 살아 있고, 전역 변수는 앱 전체에서 공유된다.
 * 반복 카운터처럼 실행마다 초기화되어야 하는 값과, 매크로 사이에 상태를 넘겨야 하는
 * 값의 수명이 다르기 때문이다.
 */
class VarScope(private val global: MutableMap<String, String>) {
    private val local = HashMap<String, String>()

    operator fun get(name: String): String? = local[name] ?: global[name]

    fun setLocal(name: String, value: String) {
        local[name] = value
    }

    fun setGlobal(name: String, value: String) {
        global[name] = value
    }

    /** 문자열 안의 {name} 을 값으로 치환. 파라미터 어디서든 변수를 쓸 수 있게 한다. */
    fun expand(text: String): String =
        Regex("\\{(\\w+)}").replace(text) { m -> this[m.groupValues[1]] ?: m.value }

    fun child(): VarScope = VarScope(global).also { it.local.putAll(local) }
}

/** 실행 이력. 디버깅과 "왜 안 돌았지"에 답하기 위해 남긴다. */
class ExecLog {
    private val entries = ArrayList<Entry>()

    data class Entry(val at: Long, val materialId: String, val message: String)

    fun add(materialId: String, message: String) {
        entries.add(Entry(System.currentTimeMillis(), materialId, message))
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() = entries.clear()
}

/** 실행기에 넘어가는 모든 맥락. 새 Material 이 필요로 할 것들이 전부 여기 모인다. */
class ExecContext(
    val io: Io,
    val scope: VarScope,
    val cancel: CancelSignal,
    val log: ExecLog,
)

/** 타입별 실행 규칙. 새 Material 은 이것만 구현해 등록하면 된다. */
interface Executor {
    val typeId: String
    suspend fun run(material: Material, ctx: ExecContext): Flow
}

object ExecutorRegistry {
    private val executors = HashMap<String, Executor>()

    fun register(executor: Executor) {
        executors[executor.typeId] = executor
    }

    fun find(typeId: String): Executor? = executors[typeId]
}

/**
 * 엔진.
 *
 * 자식 목록을 순서대로 실행하고, 각 실행기가 돌려준 Flow 에 따라 흐름을 정한다.
 * 제어 블록은 runChildren 을 재귀 호출해 자기 body 를 돌린다.
 */
object Engine {

    suspend fun run(root: Material, ctx: ExecContext): Flow {
        ctx.cancel.throwIfCancelled()
        if (!root.enabled) return Flow.Next

        val executor = ExecutorRegistry.find(root.typeId)
        if (executor == null) {
            ctx.log.add(root.id, "실행기 없음: ${root.typeId}")
            return Flow.Next
        }
        return executor.run(root, ctx)
    }

    /** 자식들을 순서대로. 제어 블록이 자기 body 를 돌릴 때 쓴다. */
    suspend fun runChildren(children: List<Material>, ctx: ExecContext): Flow {
        for (child in children) {
            when (val flow = run(child, ctx)) {
                Flow.Next -> continue
                else -> return flow
            }
        }
        return Flow.Next
    }

    /**
     * 자식들을 동시에.
     *
     * 순서축(빌드)만으로는 "왼손으로 이동하며 오른손으로 스킬" 같은 동시 조작을 표현할 수 없다.
     * PARALLEL 제어 블록이 이것을 쓴다. 변수 스코프는 갈래마다 분리해 서로 덮어쓰지 않게 한다.
     */
    suspend fun runParallel(children: List<Material>, ctx: ExecContext): Flow = coroutineScope {
        val results = children.map { child ->
            async {
                val branch = ExecContext(ctx.io, ctx.scope.child(), ctx.cancel, ctx.log)
                run(child, branch)
            }
        }.awaitAll()

        // 한 갈래라도 전체 종료를 요구하면 그것이 우선한다.
        if (results.any { it is Flow.Stop }) Flow.Stop else Flow.Next
    }
}
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/core/exec/Builtins.kt" << 'LOOPY_EOF'
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
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/ui/theme/Neu.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 뉴모피즘 단일 구현.
 *
 * 광원은 왼쪽 위. 요소는 배경과 같은 재질이 솟거나(raised) 파인(recessed) 것처럼 보여야 하므로,
 * 그림자·글로우는 **요소 색이 아니라 표면(surface) 색**에서 파생된다.
 * 컬러 요소(fill)는 그 위에 자기 색 발광과 안쪽 그라데이션이 얹힌다.
 */
fun Modifier.neu(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this.drawBehind {
    val off = offset.toPx()
    val bl = blur.toPx()
    val cr = corner.toPx()
    val body = fill ?: surface
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

    if (raised) {
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val shadow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, off, off, surface.shade(0.78f).toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, shadow)

            val glow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(rect, cr, cr, glow)

            if (fill != null) {
                val tint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = body.toArgb()
                    setShadowLayer(bl * 1.05f, 0f, off * 0.4f, fill.copy(alpha = 0.45f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, tint)
            }
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(body.tint(0.20f), body, body.shade(0.91f)),
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.26f),
                0.32f to Color.Transparent,
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
    } else {
        // 파임: 표면색으로 채운 뒤 안쪽에만 그림자를 그린다(clip 필수).
        drawRoundRect(body, cornerRadius = CornerRadius(cr, cr))
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val saved = fw.save()
            fw.clipPath(
                android.graphics.Path().apply {
                    addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
                },
            )
            val outer = android.graphics.RectF(
                -off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f,
            )
            val inner = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, off, off, surface.shade(0.72f).toArgb())
            }
            fw.drawRoundRect(outer, cr, cr, inner)

            val reflect = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(outer, cr, cr, reflect)
            fw.restoreToCount(saved)
        }
    }
}

/**
 * 가로로 꽉 찬 바. 좌우 여백이 없어 대각선 그림자를 줄 자리가 없으므로 수직 방향만 쓴다.
 * 위/아래 층 구분이 목적이라 뉴모피즘 카드와는 의도가 다르다.
 */
fun Modifier.neuBar(surface: Color = NeuBase): Modifier = this.drawBehind {
    val off = 3.dp.toPx()
    val bl = 7.dp.toPx()
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val shadow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl, 0f, off, surface.shade(0.90f).toArgb())
        }
        fw.drawRect(rect, shadow)
        val glow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl * 0.7f, 0f, -off * 0.7f, 0x66FFFFFF)
        }
        fw.drawRect(rect, glow)
    }
}

/** 뉴모피즘 + 클립 + 배경을 한 번에. 대부분의 호출부는 이걸 쓰면 된다. */
fun Modifier.neuSurface(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this
    .neu(surface, fill, corner, offset, blur, raised)
    .clip(RoundedCornerShape(corner))
    .background(if (raised) (fill ?: surface) else Color.Transparent)

fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

fun Color.tint(f: Float) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)
LOOPY_EOF
echo "2/2 완료."
git add -A
git commit -m "Phase 0-1: 확장 기반 — Material 공리(재귀/레지스트리/HAT), 실행엔진(Flow/변수/킬스위치/PARALLEL), Io 단일통로, 좌표매핑 통합, 뉴모피즘 단일화"
git push
echo "푸시 완료"

