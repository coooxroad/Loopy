package com.loopy.app.shizuku

import rikka.shizuku.Shizuku

/**
 * Shizuku 셸 실행을 한 곳으로 모은 진입점.
 *
 * Shizuku.newProcess 는 이 API 버전에서 private 이라 리플렉션으로 호출한다.
 * (rikka.shizuku.* 는 안드로이드 프레임워크 클래스가 아니라 hidden-API 제약이 없고,
 *  debug 빌드라 난독화도 없어 안전하다. 반환형 ShizukuRemoteProcess 는
 *  java.lang.Process 를 상속하므로 Process 로 받아 쓴다.)
 */
object Shell {

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** raw 프로세스 생성. 스트리밍(getevent)처럼 살아있는 프로세스가 필요할 때 사용. */
    fun newProcess(cmd: Array<String>): Process =
        newProcessMethod.invoke(null, cmd, null, null) as Process

    private fun sh(cmd: String): Process = newProcess(arrayOf("sh", "-c", cmd))

    /** 명령을 실행하고 stdout 을 통째로 반환(동기). 실패 시 null. */
    fun run(cmd: String): String? = try {
        val p = sh(cmd)
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (t: Throwable) {
        null
    }

    /** 출력이 필요 없는 명령을 실행하고 끝날 때까지 대기(동기). */
    fun exec(cmd: String) {
        try {
            sh(cmd).waitFor()
        } catch (_: Throwable) {
        }
    }
}
