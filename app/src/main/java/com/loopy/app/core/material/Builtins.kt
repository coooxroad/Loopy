package com.loopy.app.core.material

/**
 * 기본 내장 타입들. 이제 파라미터는 [ParamBag] 로 통일되어 타입별 XParams·parse 가 사라졌다.
 * 타입은 실행 성격(kind/editor/parallel)만 선언한다. 값 스키마는 BlockDef(Field)가 가진다.
 */

object TouchType : MaterialType {
    override val id = "touch"; override val kind = Kind.ACTION; override val label = "터치"
}

object WaitType : MaterialType {
    override val id = "wait"; override val kind = Kind.ACTION; override val label = "대기"
    override val input = ParamInput.INLINE
}

object LoopType : MaterialType {
    override val id = "loop"; override val kind = Kind.CONTROL; override val label = "반복"
    override val input = ParamInput.INLINE
    override val editor = EditorKind.BLOCKS
}

object IfType : MaterialType {
    override val id = "if"; override val kind = Kind.CONTROL; override val label = "만약"
    override val input = ParamInput.SHEET
    override val editor = EditorKind.BLOCKS
}

object ParallelType : MaterialType {
    override val id = "parallel"; override val kind = Kind.CONTROL; override val label = "동시에"
    override val editor = EditorKind.BLOCKS
    override val parallel = true
}

object BuildType : MaterialType {
    override val id = "build"; override val kind = Kind.CONTROL; override val label = "빌드"
    override val editor = EditorKind.BLOCKS
}

object StopType : MaterialType {
    override val id = "stop"; override val kind = Kind.ACTION; override val label = "종료"
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
