package com.loopy.app.input

import com.loopy.app.shizuku.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 기기가 실제로 뿜은 raw 입력 이벤트 한 줄.
 * type/code/value 는 리눅스 input_event 의 원시 숫자값 그대로(추측/변환 없음).
 * value 는 0xffffffff(=트래킹ID 해제) 같은 값을 담아야 해서 Long 으로 둔다.
 */
data class RawEvent(
    val tMicros: Long,   // getevent 타임스탬프(마이크로초, 절대값)
    val type: Int,
    val code: Int,
    val value: Long,
)

/**
 * 터치스크린의 raw getevent 스트림을 그대로 캡처한다.
 * 파싱/정규화 없이 원본 숫자를 저장하므로, 이걸 sendevent 로 되쏘면
 * 기기 자신의 프로토콜이라 100% 인식된다(M1b-1 검증의 핵심).
 *
 * getevent -t (숫자 hex + 타임스탬프) 사용. M0 에서 -lt 가 동작했으므로 -t 도 지원됨.
 */
class Recorder {

    private var job: Job? = null
    val events: MutableList<RawEvent> = Collections.synchronizedList(mutableListOf())

    fun start(scope: CoroutineScope, dev: TouchDevice) {
        stop()
        events.clear()
        job = scope.launch(Dispatchers.IO) {
            val proc = try {
                Shell.newProcess(arrayOf("sh", "-c", "getevent -t ${dev.path}"))
            } catch (t: Throwable) {
                return@launch
            }
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    parse(line)?.let { events.add(it) }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { proc.destroy() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** `[   86185.123456] 0003 0035 000007a1` → RawEvent. 마지막 3토큰을 hex 로 읽는다. */
    private fun parse(line: String): RawEvent? {
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open < 0 || close < 0 || close <= open) return null
        val tMicros = try {
            (line.substring(open + 1, close).trim().toDouble() * 1_000_000).toLong()
        } catch (_: Throwable) {
            return null
        }
        val toks = line.substring(close + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 3) return null
        val n = toks.size
        return try {
            RawEvent(
                tMicros = tMicros,
                type = toks[n - 3].toInt(16),
                code = toks[n - 2].toInt(16),
                value = toks[n - 1].toLong(16),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
