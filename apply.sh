#!/data/data/com.termux/files/usr/bin/bash
# Phase 0-1: 확장 가능한 기반 (Material 공리 / 실행엔진 / Io 통로 / 좌표통합 / 뉴모피즘통합)
set -e
if [ ! -f settings.gradle.kts ]; then echo "!! Loopy 폴더에서 실행"; exit 1; fi
mkdir -p app/src/main/java/com/loopy/app/core/geom app/src/main/java/com/loopy/app/core/io app/src/main/java/com/loopy/app/core/material app/src/main/java/com/loopy/app/core/exec
cat > "app/src/main/java/com/loopy/app/core/geom/Coords.kt" << 'LOOPY_EOF'
package com.loopy.app.core.geom

import androidx.compose.ui.geometry.Offset

/**
 * 좌표 변환의 단일 진실 공급원.
 *
 * 터치 샘플의 nx/ny 는 **터치 패널 기준 정규화 좌표**다. 기기를 돌려도 값이 변하지 않는다.
 * 반대로 화면·영상은 회전에 따라 배치가 달라지므로, 그릴 때마다 그 시점의 회전으로 변환해야 한다.
 * 변환 로직이 여러 곳에 흩어지면 회전 규칙을 바꿀 때마다 전부 고쳐야 하므로 여기 하나로 모은다.
 */
object Coords {

    /** 패널 좌표 → 회전된 화면 좌표(0~1). 재생 측 좌표 변환과 반드시 같은 규칙이어야 한다. */
    fun rotate(nx: Float, ny: Float, rotDeg: Int): Pair<Float, Float> = when (rotDeg) {
        90 -> ny to (1f - nx)
        180 -> (1f - nx) to (1f - ny)
        270 -> (1f - ny) to nx
        else -> nx to ny
    }

    /** 화면 델타 → 패널 델타. rotate 의 역변환. */
    fun unrotateDelta(dx: Float, dy: Float, rotDeg: Int): Pair<Float, Float> = when (rotDeg) {
        90 -> (-dy) to dx
        180 -> (-dx) to (-dy)
        270 -> dy to (-dx)
        else -> dx to dy
    }

    fun isLandscape(rotDeg: Int) = rotDeg == 90 || rotDeg == 270

    /** 뷰 안에 종횡비 aspect 인 내용을 letterbox 로 맞춘 사각형. */
    fun fitRect(viewW: Float, viewH: Float, aspect: Float): Rect {
        val viewAspect = viewW / viewH
        return if (aspect > viewAspect) {
            val h = viewW / aspect
            Rect(0f, (viewH - h) / 2f, viewW, h)
        } else {
            val w = viewH * aspect
            Rect((viewW - w) / 2f, 0f, w, viewH)
        }
    }

    /**
     * 스트로크를 그릴 실제 영역.
     *
     * 화면 녹화 영상은 프레임 해상도가 고정(보통 세로)이라, 녹화 중 기기를 돌리면 가로 화면이
     * 그 세로 프레임 **안에** 축소되어 배치된다. 따라서 회전 구간의 스트로크는 프레임 전체가
     * 아니라 그 축소된 영역에 매핑해야 한다.
     *
     * @param videoRect 영상 프레임이 뷰에 그려진 사각형
     * @param screenAspect 기기 세로 화면의 종횡비(w/h)
     */
    fun contentRect(videoRect: Rect, rotDeg: Int, screenAspect: Float): Rect {
        if (!isLandscape(rotDeg)) return videoRect
        val landAspect = if (screenAspect > 0f) 1f / screenAspect else 16f / 9f
        val inner = fitRect(videoRect.w, videoRect.h, landAspect)
        return Rect(videoRect.x + inner.x, videoRect.y + inner.y, inner.w, inner.h)
    }

    /** 패널 좌표 → 뷰 픽셀. 회전·레터박스·회전구간 축소를 모두 반영한다. */
    fun toView(nx: Float, ny: Float, rotDeg: Int, videoRect: Rect, screenAspect: Float): Offset {
        val (rx, ry) = rotate(nx, ny, rotDeg)
        val area = contentRect(videoRect, rotDeg, screenAspect)
        return Offset(area.x + rx * area.w, area.y + ry * area.h)
    }

    /** 뷰 픽셀 델타 → 패널 정규화 델타. 드래그로 스트로크를 옮길 때 사용. */
    fun deltaToPanel(
        dx: Float, dy: Float, rotDeg: Int, videoRect: Rect, screenAspect: Float,
    ): Pair<Float, Float> {
        val area = contentRect(videoRect, rotDeg, screenAspect)
        return unrotateDelta(dx / area.w, dy / area.h, rotDeg)
    }
}

data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
}
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/core/io/Io.kt" << 'LOOPY_EOF'
package com.loopy.app.core.io

import com.loopy.app.macro.Stroke

/**
 * 시스템에 실제로 손을 대는 모든 경로의 단일 통로.
 *
 * Shizuku(ADB 권한)가 있으면 접근성 서비스로는 불가능한 일들이 가능해진다.
 * 터치 주입, 설정 변경, 화면 캡처, 앱 제어가 전부 여기를 거치므로,
 * 이후 추가될 Material(이미지 인식, 스크립트, API 연동)도 같은 통로만 알면 된다.
 *
 * 구현을 인터페이스 뒤에 두는 이유:
 *  - 테스트 시 가짜 구현으로 대체 가능
 *  - Shizuku 외 백엔드(루트, 접근성)를 나중에 붙일 여지
 */
interface Io {

    val available: Boolean

    // ── 입력 ──

    /** 스트로크들을 실제 터치로 주입. startMs 가 겹치면 멀티터치로 동시 재생된다. */
    suspend fun playStrokes(strokes: List<Stroke>, rotationAt: (Long) -> Int)

    /** 진행 중인 주입을 중단. 킬 스위치가 어디서든 동작하려면 필요하다. */
    fun stopPlayback()

    /** 터치 캡처 시작. 콜백은 패널 정규화 좌표(회전 불변)를 넘긴다. */
    fun startCapture(onPoint: (slot: Int, nx: Float, ny: Float, down: Boolean) -> Unit)

    fun stopCapture()

    // ── 화면 ──

    /** 현재 화면 회전(0/90/180/270). */
    fun rotation(): Int

    /** 화면 픽셀 크기. */
    fun screenSize(): Pair<Int, Int>

    /**
     * 현재 화면 캡처. 이미지 인식 Material 이 여기에 의존한다.
     * 아직 구현하지 않았지만, 자리를 비워둬야 나중에 호출부가 흔들리지 않는다.
     */
    suspend fun captureScreen(): ScreenShot?

    // ── 시스템 ──

    /** `settings put` 계열. 밝기·볼륨 등 Material 이 사용한다. */
    suspend fun putSetting(namespace: String, key: String, value: String): Boolean

    suspend fun getSetting(namespace: String, key: String): String?

    /** 임의 셸 명령. 마지막 수단이자, 아직 전용 API 가 없는 기능의 임시 통로. */
    suspend fun shell(cmd: String): String?

    /** 앱 실행 / 종료. 트리거·액션 양쪽에서 쓰인다. */
    suspend fun launchApp(pkg: String): Boolean

    suspend fun forceStop(pkg: String): Boolean

    /** 현재 최상위 앱 패키지. 트리거(앱 실행 감지)와 조건에서 쓰인다. */
    suspend fun foregroundApp(): String?
}

/** 캡처된 화면. 이미지 인식이 이 위에서 템플릿 매칭을 수행한다. */
class ScreenShot(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val rotation: Int,
)
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/core/material/Material.kt" << 'LOOPY_EOF'
package com.loopy.app.core.material

/**
 * Loopy 의 유일한 공리.
 *
 * 터치 매크로도, 대기도, 조건도, 반복도, 트리거도, 그리고 그것들을 모아 만든 빌드까지도
 * 전부 Material 이다. 빌드가 Material 이므로 빌드 안에 빌드를 넣을 수 있고,
 * 그래서 플레이리스트 같은 별도 개념이 필요 없다.
 *
 * 새 기능을 추가할 때 이 구조는 바뀌지 않는다. 새 [MaterialType] 과 그에 맞는
 * Params · 실행기 · 편집기를 등록할 뿐이다. 스크립트든 API 연동이든 마찬가지다.
 */
data class Material(
    val id: String,
    val typeId: String,
    val params: Params,
    val children: List<Material> = emptyList(),
    val meta: Meta = Meta(),
    /** 개별 블록만 잠시 꺼두기. 삭제하지 않고 실험할 수 있어야 한다. */
    val enabled: Boolean = true,
) {
    val type: MaterialType get() = MaterialRegistry.require(typeId)
}

data class Meta(
    val name: String = "",
    val note: String = "",
    val favorite: Boolean = false,
    val folder: String? = null,
    val createdAt: Long = 0L,
)

/** 타입별 설정값. 각 타입이 자기 Params 를 정의한다. */
interface Params {
    /** 저장을 위한 직렬화. 타입마다 자기 형식을 안다. */
    fun toMap(): Map<String, Any?>
}

object NoParams : Params {
    override fun toMap(): Map<String, Any?> = emptyMap()
}

/**
 * 블록의 성격.
 *
 * HAT 은 스크래치의 모자 블록처럼 위에 아무것도 붙일 수 없다. 트리거가 여기 속하며,
 * 그래서 "매크로 중간에 트리거가 있는" 상태가 구조적으로 불가능해진다.
 */
enum class Kind { HAT, ACTION, CONTROL }

/** 이 Material 을 어떻게 편집하는가. 타입마다 편집 UI 가 다르다는 사실을 구조에 못 박는다. */
enum class EditorKind {
    /** 시간축 — 촬영 매크로. 스트로크가 특정 시각에 배치된다. */
    TIMELINE,

    /** 순서축 — 빌드. 블록을 세로로 쌓고 가지친다. */
    BLOCKS,

    /** 블록 안에서 바로 값만 고침. 대기 시간 등. */
    INLINE,

    /** 바텀시트 폼. 항목이 여럿인 설정. */
    SHEET,

    /** 코드 에디터. 스크립트 Material 용. */
    CODE,
}

interface MaterialType {
    val id: String
    val kind: Kind
    val editor: EditorKind
    val label: String

    /** 저장된 맵에서 Params 복원. */
    fun parse(map: Map<String, Any?>): Params

    /** CONTROL 이면 children 을 가진다. */
    val hasChildren: Boolean get() = kind == Kind.CONTROL
}

/**
 * 타입 레지스트리.
 *
 * 런타임 등록이므로 새 타입을 추가해도 기존 코드를 건드리지 않는다.
 * 나중에 플러그인이 자기 타입을 여기에 밀어 넣는 것도 같은 방식이다.
 */
object MaterialRegistry {
    private val types = LinkedHashMap<String, MaterialType>()

    fun register(type: MaterialType) {
        types[type.id] = type
    }

    fun find(id: String): MaterialType? = types[id]

    fun require(id: String): MaterialType =
        types[id] ?: error("등록되지 않은 Material 타입: $id")

    fun all(): List<MaterialType> = types.values.toList()

    fun byKind(kind: Kind): List<MaterialType> = types.values.filter { it.kind == kind }
}
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/core/exec/Engine.kt" << 'LOOPY_EOF'
package com.loopy.app.core.exec

import com.loopy.app.core.io.Io
import com.loopy.app.core.material.Material
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실행 중 흐름 제어.
 *
 * 실행기는 자기 일을 끝낸 뒤 다음에 무엇을 할지 반환한다. 제어 블록(반복·조건)이
 * 이 값을 보고 흐름을 바꾼다. 점프 대신 이 방식을 쓰는 이유는, 중첩 구조를
 * 그대로 따라가야 스크래치식 가지치기가 코드와 UI 양쪽에서 같은 모양이 되기 때문이다.
 */
sealed interface Flow {
    /** 다음 블록으로. */
    object Next : Flow

    /** 가장 가까운 반복을 빠져나감. */
    object Break : Flow

    /** 가장 가까운 반복의 다음 회차로. */
    object Continue : Flow

    /** 실행 전체 종료. 킬 스위치. */
    object Stop : Flow
}

/** 어디서든 실행을 멈출 수 있어야 한다. 킬 스위치와 사용자 취소가 같은 신호를 쓴다. */
class CancelSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    val isCancelled: Boolean get() = cancelled.get()

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("실행 취소됨")
    }
}

/**
 * 변수 스코프.
 *
 * 지역 변수는 빌드 1회 실행 동안만 살아 있고, 전역 변수는 앱 전체에서 공유된다.
 * 반복 카운터처럼 실행마다 초기화되어야 하는 값과, 매크로 사이에 상태를 넘겨야 하는
 * 값의 수명이 다르기 때문이다.
 */
class VarScope(private val global: MutableMap<String, String>) {
    private val local = HashMap<String, String>()

    operator fun get(name: String): String? = local[name] ?: global[name]

    fun setLocal(name: String, value: String) {
        local[name] = value
    }

    fun setGlobal(name: String, value: String) {
        global[name] = value
    }

    /** 문자열 안의 {name} 을 값으로 치환. 파라미터 어디서든 변수를 쓸 수 있게 한다. */
    fun expand(text: String): String =
        Regex("\\{(\\w+)}").replace(text) { m -> this[m.groupValues[1]] ?: m.value }

    fun child(): VarScope = VarScope(global).also { it.local.putAll(local) }
}

/** 실행 이력. 디버깅과 "왜 안 돌았지"에 답하기 위해 남긴다. */
class ExecLog {
    private val entries = ArrayList<Entry>()

    data class Entry(val at: Long, val materialId: String, val message: String)

    fun add(materialId: String, message: String) {
        entries.add(Entry(System.currentTimeMillis(), materialId, message))
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() = entries.clear()
}

/** 실행기에 넘어가는 모든 맥락. 새 Material 이 필요로 할 것들이 전부 여기 모인다. */
class ExecContext(
    val io: Io,
    val scope: VarScope,
    val cancel: CancelSignal,
    val log: ExecLog,
)

/** 타입별 실행 규칙. 새 Material 은 이것만 구현해 등록하면 된다. */
interface Executor {
    val typeId: String
    suspend fun run(material: Material, ctx: ExecContext): Flow
}

object ExecutorRegistry {
    private val executors = HashMap<String, Executor>()

    fun register(executor: Executor) {
        executors[executor.typeId] = executor
    }

    fun find(typeId: String): Executor? = executors[typeId]
}

/**
 * 엔진.
 *
 * 자식 목록을 순서대로 실행하고, 각 실행기가 돌려준 Flow 에 따라 흐름을 정한다.
 * 제어 블록은 이 runChildren 을 재귀 호출해 자기 자식들을 돌린다.
 */
object Engine {

    suspend fun run(root: Material, ctx: ExecContext): Flow {
        ctx.cancel.throwIfCancelled()
        if (!root.enabled) return Flow.Next

        val executor = ExecutorRegistry.find(root.typeId)
        if (executor == null) {
            ctx.log.add(root.id, "실행기 없음: ${root.typeId}")
            return Flow.Next
        }
        return executor.run(root, ctx)
    }

    /** 자식들을 순서대로. 제어 블록이 자기 body 를 돌릴 때 쓴다. */
    suspend fun runChildren(children: List<Material>, ctx: ExecContext): Flow {
        for (child in children) {
            when (val flow = run(child, ctx)) {
                Flow.Next -> continue
                else -> return flow
            }
        }
        return Flow.Next
    }
}
LOOPY_EOF
cat > "app/src/main/java/com/loopy/app/ui/theme/Neu.kt" << 'LOOPY_EOF'
package com.loopy.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 뉴모피즘 단일 구현.
 *
 * 광원은 왼쪽 위. 요소는 배경과 같은 재질이 솟거나(raised) 파인(recessed) 것처럼 보여야 하므로,
 * 그림자·글로우는 **요소 색이 아니라 표면(surface) 색**에서 파생된다.
 * 컬러 요소(fill)는 그 위에 자기 색 발광과 안쪽 그라데이션이 얹힌다.
 */
fun Modifier.neu(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this.drawBehind {
    val off = offset.toPx()
    val bl = blur.toPx()
    val cr = corner.toPx()
    val body = fill ?: surface
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)

    if (raised) {
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val shadow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, off, off, surface.shade(0.78f).toArgb())
            }
            fw.drawRoundRect(rect, cr, cr, shadow)

            val glow = android.graphics.Paint().apply {
                isAntiAlias = true
                color = body.toArgb()
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(rect, cr, cr, glow)

            if (fill != null) {
                val tint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = body.toArgb()
                    setShadowLayer(bl * 1.05f, 0f, off * 0.4f, fill.copy(alpha = 0.45f).toArgb())
                }
                fw.drawRoundRect(rect, cr, cr, tint)
            }
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(body.tint(0.20f), body, body.shade(0.91f)),
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.26f),
                0.32f to Color.Transparent,
            ),
            cornerRadius = CornerRadius(cr, cr),
        )
    } else {
        // 파임: 표면색으로 채운 뒤 안쪽에만 그림자를 그린다(clip 필수).
        drawRoundRect(body, cornerRadius = CornerRadius(cr, cr))
        drawIntoCanvas { canvas ->
            val fw = canvas.nativeCanvas
            val saved = fw.save()
            fw.clipPath(
                android.graphics.Path().apply {
                    addRoundRect(rect, cr, cr, android.graphics.Path.Direction.CW)
                },
            )
            val outer = android.graphics.RectF(
                -off * 2f, -off * 2f, size.width + off * 2f, size.height + off * 2f,
            )
            val inner = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, off, off, surface.shade(0.72f).toArgb())
            }
            fw.drawRoundRect(outer, cr, cr, inner)

            val reflect = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = off * 2.2f
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(bl, -off, -off, android.graphics.Color.WHITE)
            }
            fw.drawRoundRect(outer, cr, cr, reflect)
            fw.restoreToCount(saved)
        }
    }
}

/**
 * 가로로 꽉 찬 바. 좌우 여백이 없어 대각선 그림자를 줄 자리가 없으므로 수직 방향만 쓴다.
 * 위/아래 층 구분이 목적이라 뉴모피즘 카드와는 의도가 다르다.
 */
fun Modifier.neuBar(surface: Color = NeuBase): Modifier = this.drawBehind {
    val off = 3.dp.toPx()
    val bl = 7.dp.toPx()
    val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
    drawIntoCanvas { canvas ->
        val fw = canvas.nativeCanvas
        val shadow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl, 0f, off, surface.shade(0.90f).toArgb())
        }
        fw.drawRect(rect, shadow)
        val glow = android.graphics.Paint().apply {
            isAntiAlias = true
            color = surface.toArgb()
            setShadowLayer(bl * 0.7f, 0f, -off * 0.7f, 0x66FFFFFF)
        }
        fw.drawRect(rect, glow)
    }
}

/** 뉴모피즘 + 클립 + 배경을 한 번에. 대부분의 호출부는 이걸 쓰면 된다. */
fun Modifier.neuSurface(
    surface: Color = NeuBase,
    fill: Color? = null,
    corner: Dp = 12.dp,
    offset: Dp = 2.5.dp,
    blur: Dp = 5.dp,
    raised: Boolean = true,
): Modifier = this
    .neu(surface, fill, corner, offset, blur, raised)
    .clip(RoundedCornerShape(corner))
    .background(if (raised) (fill ?: surface) else Color.Transparent)

fun Color.shade(f: Float) = Color(red * f, green * f, blue * f, alpha)

fun Color.tint(f: Float) =
    Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)
LOOPY_EOF
echo "기반 파일 5개 생성 완료."
git add -A
git commit -m "Phase 0-1: 확장 기반 — Material 공리, 실행 엔진 계약, Io 통로, 좌표 매핑 통합, 뉴모피즘 단일화"
git push
echo "푸시 완료"

