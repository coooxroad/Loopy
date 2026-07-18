package com.loopy.app.blocks

import androidx.compose.ui.graphics.Color
import com.loopy.app.core.material.Field
import com.loopy.app.core.material.Kind
import com.loopy.app.core.material.ParamBag
import com.loopy.app.core.material.defaults
import com.loopy.app.ui.components.Icon

/**
 * 블록 한 종류의 완전한 정의 — 이 앱의 심장.
 *
 * 지금까지 블록 하나가 5곳(MaterialType · BlockSpec · 팔레트 목록 · defaultParams · 파라미터 시트)에
 * 흩어져 있었다. 그래서 기능을 더할수록 부채가 쌓였다. BlockDef 는 그 다섯을 하나로 모은다.
 * 새 블록 = 정의 하나. 팔레트·색·기본값·편집 UI 는 이 정의에서 자동으로 나온다.
 *
 * 정의는 "무엇을"만 안다. "어떻게(플랫폼)"는 실행기(ExecutorRegistry)가 따로 담당한다 — 밝기를
 * *정의*하는 것과 밝기를 *바꾸는* 것은 다른 층이다. 이 분리가 견고함의 뿌리다.
 *
 * (층 청소: 이 파일은 지금 blocks 패키지에서 시각 타입과 함께 산다. 순수 domain 으로 옮기는 건
 *  예정된 정리 대상 — 지금은 기존 코드 옆에 세워 두고 점진 전환한다. Branch by Abstraction.)
 */
interface BlockDef {
    val id: String
    val kind: Kind
    val category: BlockCategory
    val shape: BlockShape
    val icon: Icon

    /** 라벨. 파라미터 자리는 {key} 로 적는다. 예: "밝기 {level}%" */
    val label: String
    val hint: String get() = ""

    /** CONTROL 컨테이너를 어떤 축으로 편집하는가. 컨테이너가 아니면 null. */
    val container: EditorAxis? get() = null

    /** 자식을 순서가 아니라 동시에 실행하는가. '동시에' 블록이 켠다. */
    val parallel: Boolean get() = false

    /** 리터럴 파라미터 스키마 → 편집 UI 자동 생성 + 기본값 + 직렬화. */
    val fields: List<Field> get() = emptyList()

    /** 다른 블록이 꽂히는 값/조건 슬롯. 구현은 2편(조건·리포터). */
    val slots: List<SlotDef> get() = emptyList()

    /** 새 블록을 만들 때 채울 기본 파라미터. 하드코딩 defaultParams 를 대체. */
    fun defaultParams(): ParamBag = fields.defaults()

    /** 색은 기능을 말한다. 기본은 카테고리에서 유도하고, 예외만 정의가 덮어쓴다. */
    val color: Color get() = colorOf(category)
}

/** 컨테이너 편집 축. (기존 EditorKind 와 같은 의미 — 이행 후 하나로 합친다.) */
enum class EditorAxis { BLOCKS, TIMELINE }

/** 값/조건 슬롯 정의. accepts 가 무엇이 꽂힐 수 있는지 강제한다(모양=문법). */
data class SlotDef(val key: String, val accepts: SlotKind, val label: String = "")

/** 카테고리 → 색. 스크래치 색 체계. 같은 색 = 같은 종류의 일. */
fun colorOf(category: BlockCategory): Color = when (category) {
    BlockCategory.EVENT -> Color(0xFFFFBF00)
    BlockCategory.ACTION -> Color(0xFF4C97FF)
    BlockCategory.CONTROL -> Color(0xFFFFAB19)
    BlockCategory.SYSTEM -> Color(0xFF6E7A8A)
    BlockCategory.DATA -> Color(0xFF9966FF)
}

/** BlockDef 의 평범한 구현체. 대부분의 블록은 이걸로 선언한다. */
data class Block(
    override val id: String,
    override val kind: Kind,
    override val category: BlockCategory,
    override val shape: BlockShape,
    override val icon: Icon,
    override val label: String,
    override val hint: String = "",
    override val container: EditorAxis? = null,
    override val parallel: Boolean = false,
    override val fields: List<Field> = emptyList(),
    override val slots: List<SlotDef> = emptyList(),
    /** 카테고리 규칙에서 벗어나는 색(동시에=초록, 멈추기=빨강)만 지정. */
    val colorOverride: Color? = null,
) : BlockDef {
    override val color: Color get() = colorOverride ?: colorOf(category)
}

/**
 * 블록 정의 레지스트리 — 런타임 등록.
 * 팔레트·색·편집기는 전부 여기를 읽어 자동으로 채워진다(손으로 나열하던 목록이 사라진다).
 */
object BlockRegistry {
    private val defs = LinkedHashMap<String, BlockDef>()

    fun register(def: BlockDef) { defs[def.id] = def }
    fun register(list: List<BlockDef>) { list.forEach(::register) }

    fun find(id: String): BlockDef? = defs[id]
    fun all(): List<BlockDef> = defs.values.toList()
    fun byCategory(category: BlockCategory): List<BlockDef> = defs.values.filter { it.category == category }
}

private val ForkGreen = Color(0xFF2ECC71)
private val StopRed = Color(0xFFE5533D)

/**
 * 현재 앱의 블록들 — 기존 BlockSpecs 를 정의로 옮긴 것. (아직 소비처는 없음: 팔레트/시트 전환은 다음 조각.)
 */
val LoopyBlocks: List<BlockDef> = listOf(
    Block("trigger.manual", Kind.HAT, BlockCategory.EVENT, BlockShape.HAT, Icon.PLAY,
        "실행하면", "재생 버튼을 누를 때 시작합니다"),

    Block("touch", Kind.ACTION, BlockCategory.ACTION, BlockShape.STACK, Icon.RECORD,
        "터치", "녹화된 궤적을 재생합니다"),
    Block("build", Kind.CONTROL, BlockCategory.ACTION, BlockShape.STACK, Icon.FOLDER,
        "빌드 실행", "다른 빌드를 실행합니다", container = EditorAxis.BLOCKS),

    Block("wait", Kind.ACTION, BlockCategory.CONTROL, BlockShape.STACK, Icon.PAUSE,
        "기다리기", "정해진 시간만큼 멈춥니다",
        fields = listOf(Field.IntSlider("ms", "대기(ms)", 0, 5000, default = 1000, unit = "ms"))),
    Block("loop", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.C_BLOCK, Icon.REDO,
        "반복하기", "안의 블록을 여러 번 실행합니다", container = EditorAxis.BLOCKS,
        fields = listOf(Field.IntSlider("count", "횟수", 1, 999, default = 10))),
    Block("if", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.C_BLOCK, Icon.SPLIT,
        "만약", "조건이 맞을 때만 안의 블록을 실행합니다", container = EditorAxis.BLOCKS,
        slots = listOf(SlotDef("cond", SlotKind.BOOLEAN, "조건"))),
    Block("parallel", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.STACK, Icon.LIST,
        "동시에", "연결된 갈래들을 함께 실행합니다", parallel = true, colorOverride = ForkGreen),
    Block("stop", Kind.ACTION, BlockCategory.CONTROL, BlockShape.CAP, Icon.STOP,
        "멈추기", "실행을 즉시 끝냅니다", colorOverride = StopRed),

    Block("screen.dim", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.MOON,
        "화면 가리기", "검은 막을 덮습니다. 터치는 통과합니다"),
    Block("screen.brightness", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.SETTINGS,
        "밝기", "화면 밝기를 바꿉니다",
        fields = listOf(Field.IntSlider("level", "밝기", 0, 100, default = 50, unit = "%"))),
    Block("alarm.off", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.CLOSE,
        "알람 끄기", "울리는 알람을 멈춥니다"),
    Block("app.launch", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.PLAY,
        "앱 실행", "앱을 엽니다",
        fields = listOf(Field.AppPick("pkg", "앱"))),
    Block("shell", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.LIST,
        "셸 명령", "시스템 명령을 실행합니다",
        fields = listOf(Field.TextField("cmd", "명령", hint = "예: input keyevent 26"))),

    Block("var.set", Kind.ACTION, BlockCategory.DATA, BlockShape.STACK, Icon.EDIT,
        "변수 정하기", "값을 저장합니다",
        fields = listOf(
            Field.TextField("name", "변수", hint = "이름"),
            Field.TextField("value", "값"),
        )),
)

/**
 * 리트머스 — 네가 든 4개가 "정의 하나씩"으로 표현되는지 증명한다. (아직 등록 안 함: 실행기가
 * 없으므로 팔레트에 띄우지 않는다. 틀이 이들을 담을 수 있음을 보이는 용도.)
 *   1) 밝기: config field 만 (위 screen.brightness 가 이미 통과).
 *   2) 회전: 선택지.  3) 버튼 누르기: 앱/요소 피커.  4) 자이로: 값 리포터(REPORTER kind + 둥근 모양).
 */
val LitmusBlocks: List<BlockDef> = listOf(
    Block("screen.rotate", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.REDO,
        "화면 {dir}", "화면 방향을 바꿉니다",
        fields = listOf(Field.Choice("dir", "방향", listOf(
            Field.Opt("portrait", "세로"),
            Field.Opt("landscape", "가로"),
            Field.Opt("auto", "자동"),
        ), default = "auto"))),
    Block("a11y.tapElement", Kind.ACTION, BlockCategory.ACTION, BlockShape.STACK, Icon.RECORD,
        "{element} 누르기", "다른 앱의 버튼을 접근성으로 누릅니다",
        fields = listOf(
            Field.AppPick("app", "앱"),
            Field.ElementPick("element", "요소"),
        )),
    Block("sensor.gyro", Kind.REPORTER, BlockCategory.DATA, BlockShape.REPORTER, Icon.MORE,
        "자이로 {axis}", "자이로스코프 센서값(값 블록)",
        fields = listOf(Field.Choice("axis", "축", listOf(
            Field.Opt("x", "X"), Field.Opt("y", "Y"), Field.Opt("z", "Z"),
        ), default = "x"))),
)

/** 앱 시작 시 호출 — LoopyApp 에서 등록 통로에 합류. 현재 14개 실블록만 등록. */
fun registerBlockDefs() {
    BlockRegistry.register(LoopyBlocks)
}
