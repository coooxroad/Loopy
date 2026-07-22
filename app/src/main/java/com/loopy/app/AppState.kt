package com.loopy.app

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.ParamBag
import com.loopy.app.core.record.Timeline
import com.loopy.app.data.MaterialStore
import com.loopy.app.shizuku.ShizukuManager
import com.loopy.app.shizuku.ShizukuState

/**
 * 앱 화면의 상태 홀더 — 탭 이동, 편집기 열고 닫기, 권한 안내, 빌드 목록, 이름 변경.
 *
 * RootScreen 이 상태 11개와 그 규칙을 함께 들고 있어 화면이 두꺼웠다. 여기로 모으면 화면은
 * "무엇을 보여줄지"만 남고, "무엇이 일어나는지"는 이름 붙은 동작으로 읽힌다(단방향 흐름).
 *
 * Context 는 보관하지 않는다(누수 방지). 필요한 순간에만 인자로 받는다.
 */
class AppState(shizuku: ShizukuState, canOverlay: Boolean, builds: List<Material>) {

    var tab by mutableStateOf(Tab.DASHBOARD)
        private set
    var shizuku by mutableStateOf(shizuku)
        private set
    var canOverlay by mutableStateOf(canOverlay)
        private set
    var overlayMsg by mutableStateOf("오버레이를 켜고 게임으로 전환한 뒤, 컨트롤 바에서 녹화/재생.")
        private set
    var builds by mutableStateOf(builds)
        private set

    var renaming by mutableStateOf<Material?>(null)
        private set
    var nameField by mutableStateOf("")
        private set

    var showShizukuDialog by mutableStateOf(shizuku != ShizukuState.READY)
        private set
    var showOverlayDialog by mutableStateOf(false)
        private set

    /** 열려 있는 편집기. 둘 다 null 이면 일반 화면. */
    var editingBuild by mutableStateOf<Material?>(null)
        private set
    var blocksBuild by mutableStateOf<Material?>(null)
        private set

    // ── 탭 · 권한 ──

    fun selectTab(t: Tab) { tab = t }

    fun refresh(ctx: Context) { builds = loadBuilds(ctx) }

    /** 화면으로 돌아왔을 때 권한과 목록을 다시 읽는다. */
    fun recheckAll(ctx: Context) {
        shizuku = ShizukuManager.state()
        canOverlay = Settings.canDrawOverlays(ctx)
        refresh(ctx)
    }

    fun updateShizuku(s: ShizukuState) { shizuku = s }
    fun recheckShizuku() { shizuku = ShizukuManager.state() }
    fun recheckOverlay(ctx: Context) { canOverlay = Settings.canDrawOverlays(ctx) }
    fun updateOverlayMsg(msg: String) { overlayMsg = msg }

    fun askOverlayPermission() { showOverlayDialog = true }
    fun dismissDialogs() { showShizukuDialog = false; showOverlayDialog = false }
    /** 안내 팝업에서 "설정으로 이동". */
    fun goToSettings() { dismissDialogs(); tab = Tab.SETTINGS }

    // ── 빌드 목록 ──

    fun startRename(m: Material) { renaming = m; nameField = m.meta.name }
    fun editName(text: String) { nameField = text }
    fun cancelRename() { renaming = null }

    fun commitRename(ctx: Context) {
        val target = renaming
        if (target != null && nameField.isNotBlank()) {
            MaterialStore.upsert(ctx, target.copy(meta = target.meta.copy(name = nameField.trim())))
        }
        renaming = null
        refresh(ctx)
    }

    fun delete(ctx: Context, m: Material) {
        MaterialStore.delete(ctx, m.id)
        refresh(ctx)
    }

    fun newBuild(ctx: Context) {
        val fresh = Material(
            id = MaterialStore.newId(),
            typeId = "build",
            params = ParamBag.EMPTY,
            meta = Meta(name = "새 빌드", createdAt = System.currentTimeMillis()),
        )
        MaterialStore.upsert(ctx, fresh)
        blocksBuild = fresh
    }

    // ── 편집기 ──

    /** 편집 버튼: 시간축으로 열 수 있으면 타임라인, 아니면 블록 편집기. */
    fun edit(build: Material) {
        if (Timeline.canOpenAsTimeline(build)) editingBuild = build else blocksBuild = build
    }

    fun openBlocks(build: Material) { blocksBuild = build }

    /** 블록 편집기에서 터치 블록을 눌렀을 때 → 궤적(타임라인) 편집으로. */
    fun openTouchTimeline() {
        val b = blocksBuild ?: return
        if (Timeline.canOpenAsTimeline(b)) { editingBuild = b; blocksBuild = null }
    }

    fun closeEditors(ctx: Context) {
        editingBuild = null
        blocksBuild = null
        refresh(ctx)
    }

    companion object {
        fun loadBuilds(ctx: Context): List<Material> =
            MaterialStore.load(ctx).filter { it.typeId == "build" }
    }
}
