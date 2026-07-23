package com.loopy.app.core.material

/**
 * 파라미터 스키마 한 칸 = 편집 위젯 하나 + 직렬화 규칙 하나.
 *
 * 블록에 Field 를 더하면 편집 UI 가 저절로 생기고(범용 시트가 이 목록을 훑어 그린다), 기본값과
 * 저장이 따라온다. "밝기 슬라이더", "회전 방향 선택" 같은 걸 블록마다 손으로 짜던 걸 없앤다.
 *
 * 이것은 "리터럴 값"을 위한 것이다. 다른 블록을 꽂는 "값/조건 슬롯"은 [SlotDef] 로 따로 다룬다(2편).
 */
sealed interface Field {
    val key: String
    val label: String
    val defaultValue: Any?

    /**
     * 블록 안 칩에 보일 글자.
     *
     * 저장 단위와 보여줄 단위가 다를 수 있으므로(예: 밀리초로 저장하고 초로 보여주기) 변환을
     * 값의 주인인 필드가 맡는다. 화면 쪽에 타입별 분기를 두면 블록이 늘 때마다 거기도 고쳐야 한다.
     */
    fun display(params: ParamBag): String = params.str(key)

    /** 정수 슬라이더. 예: 밝기 0~100. */
    data class IntSlider(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
        val default: Int,
        val unit: String = "",
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /** 켜기/끄기. */
    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /**
     * 시간. 사람은 초로 생각하고, 녹화·엔진은 밀리초로 다룬다.
     * 그래서 저장은 밀리초 그대로 두고 편집·표시만 초로 바꾼다(키는 하나, 의미도 하나).
     */
    data class Seconds(
        override val key: String,
        override val label: String,
        val defaultMs: Long = 1000L,
    ) : Field {
        override val defaultValue: Any get() = defaultMs
        override fun display(params: ParamBag): String = fromMs(params.long(key, defaultMs))

        companion object {
            /** 1000 -> "1", 300 -> "0.3", 10789 -> "10.789" */
            fun fromMs(ms: Long): String {
                val v = java.math.BigDecimal.valueOf(ms).movePointLeft(3).stripTrailingZeros()
                return if (v.signum() == 0) "0" else v.toPlainString()
            }

            /** "1.5" -> 1500. 못 읽으면 null(입력 도중일 수 있으므로 값을 버리지 않는다). */
            fun toMs(text: String): Long? =
                text.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
        }
    }

    /** 자유 텍스트. */
    data class TextField(
        override val key: String,
        override val label: String,
        val default: String = "",
        val hint: String = "",
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /** 선택지 중 하나. 예: 회전 방향(세로/가로/자동), 자이로 축(X/Y/Z). */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Opt>,
        val default: String,
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /** 앱 선택 피커. 값은 패키지명. 피커 UI 는 플랫폼 쪽에서(나중). */
    data class AppPick(
        override val key: String,
        override val label: String,
        val default: String = "",
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /** 접근성 요소(버튼) 피커. 값은 요소 식별자. 피커 UI 는 플랫폼 쪽에서(나중). */
    data class ElementPick(
        override val key: String,
        override val label: String,
        val default: String = "",
    ) : Field {
        override val defaultValue: Any get() = default
    }

    /** 선택지 한 개. */
    data class Opt(val value: String, val label: String)
}

/** 스키마로부터 기본값 묶음을 만든다. 하드코딩된 defaultParams 를 대체. */
fun List<Field>.defaults(): ParamBag =
    ParamBag(associate { it.key to it.defaultValue })
