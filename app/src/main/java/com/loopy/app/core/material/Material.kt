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
    /**
     * 홈(slot)에 꽂힌 값·조건 Material. 키는 [com.loopy.app.blocks.SlotDef.key].
     *
     * children(세로 몸통)과 다르다: children 은 순서대로 실행되는 흐름이고, slots 는 "이 자리에
     * 들어갈 값 하나"다. 예) if 의 "condition" 홈에 비교 블록이 꽂힌다. 비어 있으면 기존처럼
     * params 의 글자 조건으로 평가한다(하위호환).
     */
    val slots: Map<String, Material> = emptyMap(),
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
/**
 * 시간축에서 이 블록이 무엇인가.
 *
 * 타임라인이 블록 이름을 나열해 알아보던 것을 대신한다. 기본이 [NONE] 이므로 새 블록은
 * "시간축이 표현할 수 없는 것"으로 안전하게 취급된다 — 모르는 채로 열었다가 저장할 때
 * 표현 못 한 블록이 사라지는 사고를 막는다.
 */
enum class TimelineRole {
    /** 시간축에 표현할 수 없음(조건·반복 등). 이게 섞이면 그 빌드는 시간축으로 열지 않는다. */
    NONE,

    /** 시간만 흐르게 한다. 길이는 [TimelineKeys.DURATION_MS] 파라미터. */
    DELAY,

    /** 녹화된 궤적 하나를 놓는다. 궤적은 [TimelineKeys.STROKE_ID] 파라미터. */
    STROKE,

    /** 자식을 차례로. */
    SEQUENCE,

    /** 자식이 같은 시각에 함께 출발. 가장 늦게 끝나는 갈래가 다음 시작을 정한다. */
    CONCURRENT,
}

/**
 * 캔버스의 **덩어리**(블록 줄기 하나를 담는 그릇).
 *
 * 지금은 "빌드 실행" 블록과 같은 typeId 를 쓴다 — 담는 그릇이라는 성격이 같아서다. 다만
 * 팔레트 블록이자 구조 컨테이너인 이중 사용이라 언젠가 갈라야 한다. 그때 고칠 곳이
 * 한 군데뿐이도록 이름을 붙여 여기 모아 둔다.
 */
object Clump {
    const val TYPE_ID = "build"
    fun isClump(m: Material): Boolean = m.typeId == TYPE_ID
}

/** 시간축이 읽는 파라미터 키 약속. 역할을 선언한 블록은 이 키를 쓴다. */
object TimelineKeys {
    const val DURATION_MS = "ms"
    const val STROKE_ID = "strokeId"
}

/**
 * 도메인이 아는 블록의 성격.
 *
 * 도메인은 모양·색을 몰라야 하므로, 정의(BlockDef)에서 실행·시간축에 필요한 것만 넘겨받는다.
 */
object TypeKinds {
    private val kinds = HashMap<String, Kind>()
    private val roles = HashMap<String, TimelineRole>()

    fun register(id: String, kind: Kind, timeline: TimelineRole = TimelineRole.NONE) {
        kinds[id] = kind
        roles[id] = timeline
    }

    fun kindOf(id: String): Kind = kinds[id] ?: Kind.ACTION

    /** 등록되지 않은(또는 선언하지 않은) 블록은 시간축이 모르는 것으로 본다. */
    fun timelineOf(id: String): TimelineRole = roles[id] ?: TimelineRole.NONE
}
