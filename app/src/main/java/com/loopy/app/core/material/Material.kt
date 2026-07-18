package com.loopy.app.core.material

/**
 * Loopy 의 유일한 공리.
 *
 * 터치 매크로도, 대기도, 조건도, 반복도, 트리거도, 그것들을 모아 만든 빌드까지도 전부 Material 이다.
 * 빌드가 Material 이므로 빌드 안에 빌드를 넣을 수 있고, 그래서 플레이리스트 같은 별도 개념이 없다.
 *
 * 새 기능을 추가할 때 이 구조는 바뀌지 않는다. 새 [MaterialType] 과 그에 맞는
 * Params · 실행기 · 편집 UI 를 등록할 뿐이다. 스크립트든 API 연동이든 마찬가지다.
 */
data class Material(
    val id: String,
    val typeId: String,
    val params: ParamBag,
    val children: List<Material> = emptyList(),
    val meta: Meta = Meta(),
    /** 삭제하지 않고 잠시 꺼두기. 실험하며 만드는 도구에는 반드시 필요하다. */
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
    /**
     * 캔버스 위치.
     *
     * 블록 조립 화면은 무한 평면이다. 어디에 놓였는지는 실행과 무관하지만, 사용자가 배치한
     * 그대로 다시 열려야 한다. 화면이 기억을 못 하면 매번 처음부터 정리하게 된다.
     */
    val x: Float = 0f,
    val y: Float = 0f,
)

/** 타입별 설정값. */
/**
 * 블록의 성격.
 *
 * HAT 은 스크래치의 모자 블록처럼 위에 아무것도 붙일 수 없다. 트리거가 여기 속하며,
 * 덕분에 "매크로 중간에 트리거가 있는" 상태가 구조적으로 불가능해진다.
 *
 * CONTROL 은 children 을 가지고 흐름을 바꾼다. 조건·반복·동시실행·빌드가 여기 속한다.
 */
enum class Kind { HAT, ACTION, CONTROL, REPORTER, BOOLEAN }

/**
 * children 을 어떤 축으로 배치·편집하는가. 두 가지뿐이다.
 *
 * 순서축만으로는 동시 실행을 표현할 수 없다. 그래서 시간축(촬영 매크로)이 따로 있고,
 * 순서축에서 동시성이 필요하면 PARALLEL 제어 블록을 쓴다.
 */
enum class EditorKind {
    /** 시간축. 각 자식이 시작 시각을 가지며, 겹치면 동시에 실행된다. 촬영 매크로. */
    TIMELINE,

    /** 순서축. 자식을 세로로 쌓고 가지친다. 빌드. */
    BLOCKS,
}

/** 파라미터를 어떻게 입력받는가. 컨테이너 편집기와는 다른 층위다. */
enum class ParamInput { NONE, INLINE, SHEET, CODE }

interface MaterialType {
    val id: String
    val kind: Kind
    val label: String

    /** 파라미터 입력 방식. */
    val input: ParamInput get() = ParamInput.NONE

    /** children 을 가진다면 어떤 축으로 편집하는가. CONTROL 이 아니면 null. */
    val editor: EditorKind? get() = null

    /** 자식들을 순서대로가 아니라 동시에 실행한다. PARALLEL 이 이것을 켠다. */
    val parallel: Boolean get() = false


    val hasChildren: Boolean get() = kind == Kind.CONTROL
}

/**
 * 타입 레지스트리.
 *
 * 런타임 등록이라 새 타입을 추가해도 기존 코드를 건드리지 않는다.
 * 플러그인이 자기 타입을 밀어 넣는 것도 같은 방식이 된다.
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
