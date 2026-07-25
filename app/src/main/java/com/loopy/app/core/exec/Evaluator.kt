package com.loopy.app.core.exec

import com.loopy.app.core.material.Material

/**
 * 값을 "내는" Material 의 실행기.
 *
 * 동작을 "하는" [Executor] 와 짝이다. 리포터(둥근 값)·불리언(육각 조건) 블록이 여기 등록된다.
 * 예) 비교 블록은 자기 두 홈(slots)의 값을 [Evaluator.text] 로 읽어 비교 결과를 낸다.
 *
 * eval 이 suspend 인 이유: 앞으로 화면 캡처·API 응답 같은 리포터가 오면 값을 내기까지 대기가
 * 필요하다. 지금은 대기가 없어도 미래를 막지 않으려고 suspend 로 둔다.
 */
interface ValueExecutor {
    val typeId: String
    suspend fun eval(material: Material, ctx: ExecContext): String
}

/** typeId → ValueExecutor. [ExecutorRegistry] 와 같은 열린 등록 방식. */
object EvaluatorRegistry {
    private val evaluators = HashMap<String, ValueExecutor>()
    fun register(evaluator: ValueExecutor) { evaluators[evaluator.typeId] = evaluator }
    fun find(typeId: String): ValueExecutor? = evaluators[typeId]
}

/**
 * 홈에 꽂힌 Material 을 값·참거짓으로 읽는다.
 *
 * 값은 문자열 하나로 다룬다(ParamBag·Conditions 와 같은 결). 숫자가 필요하면 읽는 쪽에서
 * 숫자로 해석한다 — 별도 값 타입을 두지 않아 단순함을 지킨다. 필요해지면 나중에 올린다.
 */
object Evaluator {
    /** 등록된 평가기가 없으면 빈 문자열(값 없음)로 안전하게 물러난다. */
    suspend fun text(m: Material, ctx: ExecContext): String =
        EvaluatorRegistry.find(m.typeId)?.eval(m, ctx).orEmpty()

    /** 참거짓 해석. 비교 블록 등은 "true"/"false" 를 내므로 그대로 읽는다. */
    suspend fun bool(m: Material, ctx: ExecContext): Boolean {
        val v = text(m, ctx).trim()
        return v.equals("true", ignoreCase = true) || v == "1"
    }
}
