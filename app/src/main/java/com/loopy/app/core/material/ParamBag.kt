package com.loopy.app.core.material

/**
 * 파라미터 값 저장소.
 *
 * 지금까지는 타입마다 `data class XParams` 를 만들고 `toMap()`/`parse()` 를 손으로 짰다. 블록이
 * 늘수록 그 보일러플레이트가 쌓였다. ParamBag 은 값을 그냥 Map 에 담고, 스키마([Field])가
 * 검증·편집·직렬화를 전담한다. 그래서 새 블록은 Field 만 선언하면 되고, 저장은 Map→JSON 로 공짜다.
 *
 * (기존 [Params] data class 들은 어댑터로 감싸 점진 이행한다 — Branch by Abstraction.)
 */
@JvmInline
value class ParamBag(val map: Map<String, Any?> = emptyMap()) {
    fun int(key: String, default: Int = 0): Int = (map[key] as? Number)?.toInt() ?: default
    fun long(key: String, default: Long = 0L): Long = (map[key] as? Number)?.toLong() ?: default
    fun bool(key: String, default: Boolean = false): Boolean = map[key] as? Boolean ?: default
    fun str(key: String, default: String = ""): String = map[key] as? String ?: default
    fun has(key: String): Boolean = map.containsKey(key)
    fun with(key: String, value: Any?): ParamBag = ParamBag(map + (key to value))

    companion object {
        val EMPTY = ParamBag()
    }
}
