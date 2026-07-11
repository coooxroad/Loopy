package com.loopy.app.core.exec

import com.loopy.app.core.io.Io
import com.loopy.app.core.material.Material
import kotlinx.coroutines.CancellationException
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
 * 제어 블록은 이 runChildren 을 재귀 호출해 자기 자식들을 돌린다.
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
}
