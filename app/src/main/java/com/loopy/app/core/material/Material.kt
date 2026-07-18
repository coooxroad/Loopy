package com.loopy.app.core.material

/**
 * Loopy 의 유일한 공리.
 *
 * 터치 매크로도, 대기도, 조건도, 반복도, 트리거도, 그것들을 모아 만든 빌드까지도 전부 Material 이다.
 * 빌드가 Material 이므로 빌드 안에 빌드를 넣을 수 있고, 그래서 플레이리스트 같은 별도 개념이 없다.
 *
 * 새 기능을 추가할 때 이 구조는 바뀌지 않는다. 새 BlockDef 하나와 실행기를 등록할 뿐이다.
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
    /** 실행에 필요한 성격만. 값은 BlockDef 등록 시 TypeKinds 에 함께 채워진다. */
    val kind: Kind get() = TypeKinds.kindOf(typeId)
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
 * typeId → Kind.
 *
 * 실행 엔진은 "이 블록이 모자냐/제어냐"만 알면 된다. 모양·색·필드 등 나머지는 BlockDef(상위 층)가
 * 가지므로, 도메인은 이 최소 정보만 둔다. BlockDef 등록 시 함께 채워진다.
 * (구 MaterialType/MaterialRegistry 를 흡수한 자리 — 타입 정의는 이제 BlockDef 하나뿐이다.)
 */
object TypeKinds {
    private val map = HashMap<String, Kind>()
    fun register(id: String, kind: Kind) { map[id] = kind }
    fun kindOf(id: String): Kind = map[id] ?: Kind.ACTION
}
