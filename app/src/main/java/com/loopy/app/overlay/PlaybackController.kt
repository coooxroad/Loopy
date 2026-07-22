package com.loopy.app.overlay

import com.loopy.app.core.exec.CancelSignal
import com.loopy.app.core.exec.Engine
import com.loopy.app.core.exec.ExecContext
import com.loopy.app.core.exec.ExecLog
import com.loopy.app.core.exec.VarScope
import com.loopy.app.core.io.Io
import com.loopy.app.core.material.Material
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 빌드 재생을 책임진다 — 실행 잡, 취소 신호, 전역 변수.
 *
 * 셔플·사이클·간격 같은 건 여기 없다. 전부 Material 블록이라 엔진에 트리를 넘기기만 하면 된다.
 * 그래서 새 블록이 늘어도 이 클래스는 그대로다. 화면(상태 문구·정지 버튼)은 콜백으로만 알린다.
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val io: Io,
    private val onStatus: (String) -> Unit,
    private val onRunningChanged: (Boolean) -> Unit,
) {
    private var playJob: Job? = null
    private var buildCancel: CancelSignal? = null

    /** 매크로 사이에서 살아남는 전역 변수. 서비스가 사는 동안 유지된다. */
    private val globalVars = HashMap<String, String>()

    val isPlaying: Boolean get() = playJob != null

    /** 트리를 실행한다. 이미 돌던 것은 멈춘다. */
    fun play(build: Material) {
        stop(null)
        onRunningChanged(true)
        val name = build.meta.name.ifEmpty { "빌드" }
        onStatus("▶ $name")

        val cancel = CancelSignal()
        buildCancel = cancel
        playJob = scope.launch {
            val ctx = ExecContext(
                io = io,
                scope = VarScope(globalVars),
                cancel = cancel,
                log = ExecLog(),
            )
            runCatching { Engine.run(build, ctx) }
            onStatus("완료 · $name")
            onRunningChanged(false)
            playJob = null
            buildCancel = null
        }
    }

    /** 재생 중지. msg 가 있으면 상태 문구로 알린다. */
    fun stop(msg: String?) {
        buildCancel?.cancel()
        playJob?.cancel()
        playJob = null
        onRunningChanged(false)
        if (msg != null) onStatus(msg)
    }
}
