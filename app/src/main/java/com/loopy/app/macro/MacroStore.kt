package com.loopy.app.macro

import android.content.Context
import com.loopy.app.input.GestureRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 매크로를 앱 내부 저장소에 JSON 파일로 저장/관리한다. (filesDir/macros/{id}.json)
 * 직렬화는 안드로이드 기본 내장 org.json 사용 — 의존성 추가 없음.
 */
object MacroStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "macros").apply { mkdirs() }

    /** "Jun 14 AM 2:00" 형식 자동 이름. */
    fun autoName(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MMM d a h:mm", Locale.ENGLISH).format(Date(time))

    /** actions 로 새 매크로를 만들어 저장하고 반환. */
    fun saveNew(ctx: Context, actions: List<GestureRecorder.Action>): Macro {
        val now = System.currentTimeMillis()
        val macro = Macro(UUID.randomUUID().toString(), autoName(now), now, actions)
        write(ctx, macro)
        return macro
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val m = read(ctx, id) ?: return
        write(ctx, m.copy(name = newName))
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    /** 최신순 목록. 손상된 파일은 건너뜀. */
    fun list(ctx: Context): List<Macro> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Macro? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun write(ctx: Context, macro: Macro) {
        File(dir(ctx), "${macro.id}.json").writeText(toJson(macro))
    }

    // ── 직렬화 ──
    private fun toJson(m: Macro): String {
        val arr = JSONArray()
        for (a in m.actions) {
            arr.put(
                JSONObject()
                    .put("delayMs", a.delayMs)
                    .put("type", a.type.name)
                    .put("x", a.x.toDouble()).put("y", a.y.toDouble())
                    .put("x2", a.x2.toDouble()).put("y2", a.y2.toDouble())
                    .put("durationMs", a.durationMs)
            )
        }
        return JSONObject()
            .put("id", m.id)
            .put("name", m.name)
            .put("createdAt", m.createdAt)
            .put("actions", arr)
            .toString()
    }

    private fun fromJson(text: String): Macro {
        val o = JSONObject(text)
        val arr = o.getJSONArray("actions")
        val actions = ArrayList<GestureRecorder.Action>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            actions.add(
                GestureRecorder.Action(
                    delayMs = a.getLong("delayMs"),
                    type = when (a.getString("type")) {
                        "SWIPE" -> GestureRecorder.Type.SWIPE
                        else -> GestureRecorder.Type.PRESS // 기존 TAP/HOLD → PRESS
                    },
                    x = a.getDouble("x").toFloat(),
                    y = a.getDouble("y").toFloat(),
                    x2 = a.getDouble("x2").toFloat(),
                    y2 = a.getDouble("y2").toFloat(),
                    durationMs = a.getLong("durationMs"),
                )
            )
        }
        return Macro(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            actions = actions,
        )
    }
}
