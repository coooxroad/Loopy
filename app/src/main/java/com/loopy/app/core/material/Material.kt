package com.loopy.app.core.material

/**
 * Loopy 의 유일한 공리.
 *
 * 터치 매크로도, 대기도, 조건도, 반복도, 트리거도, 그리고 그것들을 모아 만든 빌드까지도
 * 전부 Material 이다. 빌드가 Material 이므로 빌드 안에 빌드를 넣을 수 있고,
 * 그래서 플레이리스트 같은 별도 개념이 필요 없다.
 *
 * 새 기능을 추가할 때 이 구조는 바뀌지 않는다. 새 [MaterialType] 과 그에 맞는
 * Params · 실행기 · 편집기를 등록할 뿐이다. 스크립트든 API 연동이든 마찬가지다.
 */
data class Material(
    val id: String,
    val typeId: String,
    val params: Params,
    val children: List<Material> = emptyList(),
    val meta: Meta = Meta(),
    /** 개별 블록만 잠시 꺼두기. 삭제하지 않고 실험할 수 있어야 한다. */
    val enabled: Boolean = true,
) {
    val type: MaterialType get() = MaterialRegistry.require(typeId)
}

data class Meta(
    val name: String = "",
    val note: String = "",
    val favorite: Boolean = false,
    val folder: String? = null,
    val createdAt: Long = 0L,
)

/** 타입별 설정값. 각 타입이 자기 Params 를 정의한다. */
interface Params {
    /** 저장을 위한 직렬화. 타입마다 자기 형식을 안다. */
    fun toMap(): Map<String, Any?>
}

object NoParams : Params {
    override fun toMap(): Map<String, Any?> = emptyMap()
}

/**
 * 블록의 성격.
 *
 * HAT 은 스크래치의 모자 블록처럼 위에 아무것도 붙일 수 없다. 트리거가 여기 속하며,
 * 그래서 "매크로 중간에 트리거가 있는" 상태가 구조적으로 불가능해진다.
 */
enum class Kind { HAT, ACTION, CONTROL }

/** 이 Material 을 어떻게 편집하는가. 타입마다 편집 UI 가 다르다는 사실을 구조에 못 박는다. */
enum class EditorKind {
    /** 시간축 — 촬영 매크로. 스트로크가 특정 시각에 배치된다. */
    TIMELINE,

    /** 순서축 — 빌드. 블록을 세로로 쌓고 가지친다. */
    BLOCKS,

    /** 블록 안에서 바로 값만 고침. 대기 시간 등. */
    INLINE,

    /** 바텀시트 폼. 항목이 여럿인 설정. */
    SHEET,

    /** 코드 에디터. 스크립트 Material 용. */
    CODE,
}

interface MaterialType {
    val id: String
    val kind: Kind
    val editor: EditorKind
    val label: String

    /** 저장된 맵에서 Params 복원. */
    fun parse(map: Map<String, Any?>): Params

    /** CONTROL 이면 children 을 가진다. */
    val hasChildren: Boolean get() = kind == Kind.CONTROL
}

/**
 * 타입 레지스트리.
 *
 * 런타임 등록이므로 새 타입을 추가해도 기존 코드를 건드리지 않는다.
 * 나중에 플러그인이 자기 타입을 여기에 밀어 넣는 것도 같은 방식이다.
 */
object MaterialRegistry {
    private val types = LinkedHashMap<String, MaterialType>()

    fun register(type: MaterialType) {
        types[type.id] = type
    }

    fun find(id: String): MaterialType? = types[id]

    fun require(id: String): MaterialType =
        types[id] ?: error("등록되지 않은 Material 타입: $id")

    fun all(): List<MaterialType> = types.values.toList()

    fun byKind(kind: Kind): List<MaterialType> = types.values.filter { it.kind == kind }
}
