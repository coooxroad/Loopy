package com.loopy.app

import android.app.Application
import com.loopy.app.core.exec.TouchExecutor
import com.loopy.app.core.exec.registerBuiltinExecutors
import com.loopy.app.core.exec.ExecutorRegistry
import com.loopy.app.core.material.registerBuiltins

/**
 * 앱 시작 지점.
 *
 * Material 타입과 실행기는 레지스트리에 등록되어야 존재한다. 등록을 한곳에 모아두면
 * 새 타입을 추가할 때 손댈 곳이 여기 한 줄뿐이고, 나중에 플러그인이 자기 타입을
 * 밀어 넣는 것도 같은 통로가 된다.
 */
class LoopyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerBuiltins()
        registerBuiltinExecutors()
        ExecutorRegistry.register(TouchExecutor(this))
    }
}
