package com.loopy.app.blocks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopy.app.ui.components.Icon

/**
 * 블록의 생김새.
 *
 * 모양이 문법을 강제한다. 모자 블록은 위가 둥글어 아무것도 붙일 수 없고, 캡 블록은 아래가
 * 평평해 뒤에 이어붙일 수 없다. 규칙을 문서로 설명하는 대신 손으로 만져 알게 하는 방식이다.
 *
 * 색은 기능을 말한다. 같은 색이 모여 있으면 같은 종류의 일을 한다는 뜻이고, 색이 바뀌는
 * 지점이 곧 흐름이 바뀌는 지점이다.
 */

/** 블록의 결합 규칙. */
enum class BlockShape {
    /** 위에 아무것도 못 붙는다. 트리거가 여기 속한다. */
    HAT,

    /** 위아래로 이어진다. 대부분의 동작. */
    STACK,

    /** 안에 블록을 품는다. 반복과 조건. */
    C_BLOCK,

    /** 아래로 여러 갈래가 뻗는다. 갈래는 캔버스에서 자유롭게 떠 있고 선으로만 이어진다. */
    FORK,

    /** 아래에 아무것도 못 붙는다. 실행을 끝내는 블록. */
    CAP,
}

/** 기능 분류. 팔레트의 탭이자 색의 근거. */
enum class BlockCategory(val label: String) {
    ACTION("동작"),
    CONTROL("제어"),
    SYSTEM("시스템"),
    DATA("변수"),
}

data class BlockSpec(
    val typeId: String,
    val label: String,
    val hint: String,
    val shape: BlockShape,
    val category: BlockCategory,
    val color: Color,
    val icon: Icon,
)

/** 스크래치의 색 체계를 따른다. 익숙한 사람에게는 배울 것이 없고, 처음인 사람에게도 규칙이 보인다. */
private val ActionBlue = Color(0xFF4C97FF)
private val ControlYellow = Color(0xFFFFAB19)
private val ForkGreen = Color(0xFF2ECC71)
private val DataPurple = Color(0xFF9966FF)
private val SystemGray = Color(0xFF6E7A8A)
private val StopRed = Color(0xFFE5533D)

val BlockSpecs: List<BlockSpec> = listOf(
    BlockSpec(
        "touch", "터치", "녹화된 궤적을 재생합니다",
        BlockShape.STACK, BlockCategory.ACTION, ActionBlue, Icon.RECORD,
    ),
    BlockSpec(
        "wait", "기다리기", "초 단위로 멈춥니다",
        BlockShape.STACK, BlockCategory.CONTROL, ControlYellow, Icon.PAUSE,
    ),
    BlockSpec(
        "loop", "반복하기", "안의 블록을 여러 번 실행합니다",
        BlockShape.C_BLOCK, BlockCategory.CONTROL, ControlYellow, Icon.REDO,
    ),
    BlockSpec(
        "if", "만약", "조건이 맞을 때만 안의 블록을 실행합니다",
        BlockShape.C_BLOCK, BlockCategory.CONTROL, ControlYellow, Icon.SPLIT,
    ),
    BlockSpec(
        "parallel", "동시에", "갈래들을 동시에 실행합니다",
        BlockShape.FORK, BlockCategory.CONTROL, ForkGreen, Icon.LIST,
    ),
    BlockSpec(
        "stop", "멈추기", "실행을 즉시 끝냅니다",
        BlockShape.CAP, BlockCategory.CONTROL, StopRed, Icon.STOP,
    ),
    BlockSpec(
        "screen.dim", "화면 가리기", "검은 막을 덮습니다. 터치는 통과합니다",
        BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.MOON,
    ),
    BlockSpec(
        "screen.brightness", "밝기", "화면 밝기를 바꿉니다",
        BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.SETTINGS,
    ),
    BlockSpec(
        "alarm.off", "알람 끄기", "울리는 알람을 멈춥니다",
        BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.CLOSE,
    ),
    BlockSpec(
        "app.launch", "앱 실행", "앱을 엽니다",
        BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.PLAY,
    ),
    BlockSpec(
        "shell", "셸 명령", "시스템 명령을 실행합니다",
        BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.LIST,
    ),
    BlockSpec(
        "var.set", "변수 정하기", "값을 저장합니다",
        BlockShape.STACK, BlockCategory.DATA, DataPurple, Icon.EDIT,
    ),
    BlockSpec(
        "build", "빌드", "다른 빌드를 실행합니다",
        BlockShape.STACK, BlockCategory.ACTION, ActionBlue, Icon.FOLDER,
    ),
)

fun specOf(typeId: String): BlockSpec =
    BlockSpecs.firstOrNull { it.typeId == typeId }
        ?: BlockSpec(
            typeId, typeId, "",
            BlockShape.STACK, BlockCategory.SYSTEM, SystemGray, Icon.MORE,
        )

/** 블록 치수. 노치 크기가 맞아야 위아래 블록이 물린다. */
object BlockSize {
    val height: Dp = 52.dp
    val minWidth: Dp = 160.dp
    val corner: Dp = 8.dp

    /** 위쪽 오목, 아래쪽 볼록. 이 요철이 결합 가능함을 보여준다. */
    val notchWidth: Dp = 26.dp
    val notchDepth: Dp = 6.dp
    val notchLeft: Dp = 16.dp

    /** C블록이 자식을 품는 홈의 왼쪽 벽 두께. */
    val cWall: Dp = 14.dp

    /** C블록 안이 비었을 때의 최소 높이. */
    val cEmpty: Dp = 28.dp

    val gap: Dp = 0.dp
}
