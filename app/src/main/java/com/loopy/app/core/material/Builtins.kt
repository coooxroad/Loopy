package com.loopy.app.core.material

/**
 * 기본 내장 타입들.
 *
 * 여기 있는 것들이 공리에서 유도되는 첫 정리들이다. 이 파일이 커지면 안 된다.
 * 새 타입은 자기 파일에서 정의하고 [registerBuiltins] 처럼 등록만 하면 된다.
 */

// ── 터치 ──

/**
 * 궤적 하나를 재생한다.
 *
 * 스트로크는 무거우므로 본체는 [com.loopy.app.core.record.StrokeStore] 에 두고 id 로만 참조한다.
 * Material 하나가 궤적 하나에 대응하므로, 편집기의 블록 하나가 곧 이 Material 하나다.
 *
 * 재생은 궤적이 끝날 때까지 블로킹한다. 그래야 다음 블록이 제 시각에 시작한다.
 */
data class TouchParams(val strokeId: String) : Params {
    override fun toMap() = mapOf("strokeId" to strokeId)
}

object TouchType : MaterialType {
    override val id = "touch"
    override val kind = Kind.ACTION
    override val label = "터치"
    override fun parse(map: Map<String, Any?>) = TouchParams(map["strokeId"] as? String ?: "")
}

// ── 대기 ──

data class WaitParams(val ms: Long) : Params {
    override fun toMap() = mapOf("ms" to ms)
}

object WaitType : MaterialType {
    override val id = "wait"
    override val kind = Kind.ACTION
    override val label = "대기"
    override val input = ParamInput.INLINE
    override fun parse(map: Map<String, Any?>) =
        WaitParams((map["ms"] as? Number)?.toLong() ?: 1000L)
}

// ── 반복 ──

data class LoopParams(val count: Int, val infinite: Boolean = false) : Params {
    override fun toMap() = mapOf("count" to count, "infinite" to infinite)
}

object LoopType : MaterialType {
    override val id = "loop"
    override val kind = Kind.CONTROL
    override val label = "반복"
    override val input = ParamInput.INLINE
    override val editor = EditorKind.BLOCKS
    override fun parse(map: Map<String, Any?>) = LoopParams(
        count = (map["count"] as? Number)?.toInt() ?: 1,
        infinite = map["infinite"] as? Boolean ?: false,
    )
}

// ── 조건 ──

/** 조건식은 문자열로 두고 평가기가 해석한다. 변수 치환({name})이 여기서 힘을 발휘한다. */
data class IfParams(val condition: String) : Params {
    override fun toMap() = mapOf("condition" to condition)
}

object IfType : MaterialType {
    override val id = "if"
    override val kind = Kind.CONTROL
    override val label = "만약"
    override val input = ParamInput.SHEET
    override val editor = EditorKind.BLOCKS
    override fun parse(map: Map<String, Any?>) = IfParams(map["condition"] as? String ?: "")
}

// ── 동시 실행 ──

/**
 * 순서축의 한계를 메우는 블록.
 * 빌드는 본래 순차 실행이라 "이동하면서 스킬 쓰기" 같은 동시 조작을 표현할 수 없다.
 */
object ParallelType : MaterialType {
    override val id = "parallel"
    override val kind = Kind.CONTROL
    override val label = "동시에"
    override val editor = EditorKind.BLOCKS
    override val parallel = true
    override fun parse(map: Map<String, Any?>) = NoParams
}

// ── 빌드 ──

/**
 * 자식들을 순서대로 실행한다. 빌드도 Material 이라 빌드 안에 빌드를 넣을 수 있고,
 * 그래서 플레이리스트 같은 별도 개념이 필요 없다.
 *
 * recordingId 는 녹화로 만들어진 빌드만 가진다. 영상과 회전 이력이 거기 매달려 있고,
 * 편집기가 그것을 읽어 타임라인을 그린다. 사용자가 손으로 조립한 빌드에는 없다.
 */
data class BuildParams(val recordingId: String? = null) : Params {
    override fun toMap() = mapOf("recordingId" to recordingId)
}

object BuildType : MaterialType {
    override val id = "build"
    override val kind = Kind.CONTROL
    override val label = "빌드"
    override val editor = EditorKind.BLOCKS
    override fun parse(map: Map<String, Any?>) =
        BuildParams((map["recordingId"] as? String)?.takeIf { it.isNotEmpty() })
}

// ── 종료 ──

object StopType : MaterialType {
    override val id = "stop"
    override val kind = Kind.ACTION
    override val label = "종료"
    override fun parse(map: Map<String, Any?>) = NoParams
}

fun registerBuiltins() {
    MaterialRegistry.register(TouchType)
    MaterialRegistry.register(WaitType)
    MaterialRegistry.register(LoopType)
    MaterialRegistry.register(IfType)
    MaterialRegistry.register(ParallelType)
    MaterialRegistry.register(BuildType)
    MaterialRegistry.register(StopType)
}
