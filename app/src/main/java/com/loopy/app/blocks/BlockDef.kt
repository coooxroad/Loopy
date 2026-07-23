package com.loopy.app.blocks

import androidx.compose.ui.graphics.Color
import com.loopy.app.core.material.Field
import com.loopy.app.core.material.Kind
import com.loopy.app.core.material.ParamBag
import com.loopy.app.core.material.TypeKinds
import com.loopy.app.core.material.defaults
import com.loopy.app.ui.components.Icon

/**
 * 블록 한 종류의 완전한 정의 — 이 앱의 심장.
 *
 * 블록 하나에 필요한 모든 것(팔레트 노출 · 모양 · 색 · 아이콘 · 파라미터 스키마 · 기본값 · 블록 안
 * 문장)이 여기 한 곳에 있다. 정의가 흩어지면 기능을 더할수록 부채가 쌓이므로, 갈래를 만들지 않는다.
 *
 * 정의는 "무엇을"만 안다. "어떻게(플랫폼)"는 실행기(core/exec)가 맡는다 — 밝기를 *정의*하는 일과
 * 밝기를 *바꾸는* 일은 다른 층이다. 이 분리 덕분에 도메인은 안드로이드를 모르고, 테스트에서 가짜
 * 실행기로 갈아끼울 수 있다.
 *
 * 확장점: 새 블록은 [LoopyBlocks] 에 정의 한 줄 + 실행기 하나. 그 외 파일은 건드리지 않는다.
 */
interface BlockDef {
    val id: String
    val kind: Kind
    val category: BlockCategory
    val shape: BlockShape
    val icon: Icon

    /** 팔레트에 보이는 짧은 이름. */
    val label: String

    /** 팔레트의 한 줄 설명. */
    val hint: String get() = ""

    /**
     * 캔버스의 블록 안에 쓰이는 문장. `{키}` 자리에 파라미터 값이 칩으로 들어간다.
     * 예: `"만약 {condition} 이라면"`, `"{count}번 반복하기"`.
     * 값이 없는 블록은 이름이 곧 문장이므로 기본값이 [label] 이다.
     */
    val template: String get() = label

    /** CONTROL 컨테이너를 어떤 축으로 편집하는가. 컨테이너가 아니면 null. */
    val container: EditorAxis? get() = null

    /** 자식을 순서가 아니라 동시에 실행하는가. '동시에' 블록이 켠다. */
    val parallel: Boolean get() = false

    /** 리터럴 파라미터 스키마. 편집 시트의 위젯과 기본값이 여기서 자동으로 나온다. */
    val fields: List<Field> get() = emptyList()

    /** 다른 블록이 꽂히는 값/조건 홈. 홈 모양이 무엇을 넣을 수 있는지 강제한다. */
    val slots: List<SlotDef> get() = emptyList()

    /** 새 블록을 만들 때 채울 기본 파라미터. 스키마가 곧 기본값이다. */
    fun defaultParams(): ParamBag = fields.defaults()

    /** 색은 기능을 말한다. 기본은 카테고리에서 나오고, 예외만 정의가 덮어쓴다. */
    val color: Color get() = colorOf(category)
}

/** 컨테이너가 자식을 배치·편집하는 축. */
enum class EditorAxis { BLOCKS, TIMELINE }

/** 값/조건 홈. accepts 가 무엇이 꽂힐 수 있는지 정한다(모양 = 문법). */
data class SlotDef(val key: String, val accepts: SlotKind, val label: String = "")

/** 카테고리 → 색. 스크래치의 색 체계를 따른다. 같은 색 = 같은 종류의 일. */
fun colorOf(category: BlockCategory): Color = when (category) {
    BlockCategory.EVENT -> Color(0xFFFFBF00)
    BlockCategory.ACTION -> Color(0xFF4C97FF)
    BlockCategory.CONTROL -> Color(0xFFFFAB19)
    BlockCategory.SYSTEM -> Color(0xFF6E7A8A)
    BlockCategory.DATA -> Color(0xFF9966FF)
}

/** [BlockDef] 의 평범한 구현체. 대부분의 블록은 이걸로 선언한다. */
data class Block(
    override val id: String,
    override val kind: Kind,
    override val category: BlockCategory,
    override val shape: BlockShape,
    override val icon: Icon,
    override val label: String,
    override val hint: String = "",
    val sentence: String? = null,
    override val container: EditorAxis? = null,
    override val parallel: Boolean = false,
    override val fields: List<Field> = emptyList(),
    override val slots: List<SlotDef> = emptyList(),
    /** 카테고리 규칙에서 벗어나는 색(동시에=초록, 멈추기=빨강)만 지정. */
    val colorOverride: Color? = null,
) : BlockDef {
    override val template: String get() = sentence ?: label
    override val color: Color get() = colorOverride ?: colorOf(category)
}

/**
 * 블록 정의 레지스트리 — 런타임 등록.
 *
 * 팔레트·색·모양·편집 시트가 전부 여기를 읽으므로, 등록만 하면 앱 전체가 새 블록을 안다.
 * 기존 코드를 건드리지 않고 확장되는 게 핵심이다.
 */
object BlockRegistry {
    private val defs = LinkedHashMap<String, BlockDef>()

    fun register(def: BlockDef) { defs[def.id] = def }
    fun register(list: List<BlockDef>) { list.forEach(::register) }

    fun find(id: String): BlockDef? = defs[id]
    fun all(): List<BlockDef> = defs.values.toList()
    fun byCategory(category: BlockCategory): List<BlockDef> = defs.values.filter { it.category == category }
}

/**
 * 정의를 반드시 하나 돌려준다.
 *
 * 저장된 트리에 모르는 typeId 가 있어도(옛 데이터·삭제된 블록) 화면이 무너지면 안 되므로,
 * 회색 기본 블록으로 대신 그린다. 지워진 게 눈에 보이는 편이 조용히 사라지는 것보다 낫다.
 */
fun defOf(typeId: String): BlockDef =
    BlockRegistry.find(typeId) ?: Block(
        id = typeId,
        kind = Kind.ACTION,
        category = BlockCategory.SYSTEM,
        shape = BlockShape.STACK,
        icon = Icon.MORE,
        label = typeId,
    )

private val ForkGreen = Color(0xFF2ECC71)
private val StopRed = Color(0xFFE5533D)

/**
 * 이 앱의 블록들.
 *
 * 확장점: 여기에 한 줄 더하면 팔레트·색·기본값·편집 시트가 따라온다. 실행기(core/exec)만 짝지어 주면 끝.
 */
val LoopyBlocks: List<BlockDef> = listOf(
    // 이벤트 — 스크립트는 여기서 시작한다. 시작점이 없으면 무엇이 먼저 도는지 알 수 없다.
    Block("trigger.manual", Kind.HAT, BlockCategory.EVENT, BlockShape.HAT, Icon.PLAY,
        "실행하면", "재생 버튼을 누를 때 시작합니다"),

    Block("touch", Kind.ACTION, BlockCategory.ACTION, BlockShape.STACK, Icon.RECORD,
        "터치", "녹화된 궤적을 재생합니다"),
    Block("build", Kind.CONTROL, BlockCategory.ACTION, BlockShape.STACK, Icon.FOLDER,
        "빌드 실행", "다른 빌드를 실행합니다", container = EditorAxis.BLOCKS),

    Block("wait", Kind.ACTION, BlockCategory.CONTROL, BlockShape.STACK, Icon.PAUSE,
        "기다리기", "정해진 시간만큼 멈춥니다", sentence = "{ms}초 기다리기",
        fields = listOf(Field.Seconds("ms", "시간(초)"))),
    Block("loop", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.C_BLOCK, Icon.REDO,
        "반복하기", "안의 블록을 여러 번 실행합니다", sentence = "{count}번 반복하기",
        container = EditorAxis.BLOCKS,
        fields = listOf(Field.TextField("count", "횟수", default = "10", hint = "몇 번"))),
    Block("if", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.C_BLOCK, Icon.SPLIT,
        "만약", "조건이 맞을 때만 안의 블록을 실행합니다", sentence = "만약 {condition} 이라면",
        container = EditorAxis.BLOCKS,
        // 조건은 원래 육각 블록을 꽂을 자리다. 그 편집이 생기기 전까지는 글로 적어 넣는다.
        fields = listOf(Field.TextField("condition", "조건", hint = "예: {점수} > 10")),
        slots = listOf(SlotDef("condition", SlotKind.BOOLEAN, "조건"))),
    Block("parallel", Kind.CONTROL, BlockCategory.CONTROL, BlockShape.STACK, Icon.LIST,
        "동시에", "연결된 갈래들을 함께 실행합니다", parallel = true, colorOverride = ForkGreen),
    Block("stop", Kind.ACTION, BlockCategory.CONTROL, BlockShape.CAP, Icon.STOP,
        "멈추기", "실행을 즉시 끝냅니다", colorOverride = StopRed),

    Block("screen.dim", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.MOON,
        "화면 가리기", "검은 막을 덮습니다. 터치는 통과합니다"),
    Block("screen.brightness", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.SETTINGS,
        "밝기", "화면 밝기를 바꿉니다", sentence = "밝기를 {level}로",
        fields = listOf(Field.IntSlider("level", "밝기", 0, 100, default = 50, unit = "%"))),
    Block("alarm.off", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.CLOSE,
        "알람 끄기", "울리는 알람을 멈춥니다"),
    Block("app.launch", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.PLAY,
        "앱 실행", "앱을 엽니다", sentence = "{pkg} 실행",
        fields = listOf(Field.AppPick("pkg", "앱"))),
    Block("shell", Kind.ACTION, BlockCategory.SYSTEM, BlockShape.STACK, Icon.LIST,
        "셸 명령", "시스템 명령을 실행합니다", sentence = "셸 {cmd}",
        fields = listOf(Field.TextField("cmd", "명령", hint = "예: input keyevent 26"))),

    Block("var.set", Kind.ACTION, BlockCategory.DATA, BlockShape.STACK, Icon.EDIT,
        "변수 정하기", "값을 저장합니다", sentence = "변수를 {name}로 정하기",
        fields = listOf(
            Field.TextField("name", "변수", hint = "이름"),
            Field.TextField("value", "값"),
        )),
)

/**
 * 앱 시작 시 한 번. 정의를 레지스트리에 넣고, 도메인에는 실행에 필요한 kind 만 넘긴다.
 * (도메인은 모양·색을 몰라야 한다 — 층이 섞이면 순수 규칙을 테스트할 수 없다.)
 */
fun registerBlockDefs() {
    BlockRegistry.register(LoopyBlocks)
    LoopyBlocks.forEach { TypeKinds.register(it.id, it.kind) }
}
