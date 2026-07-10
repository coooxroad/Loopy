package com.loopy.app.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 매크로를 filesDir/macros/{id}.json 로 저장/관리. org.json 사용(의존성 없음). */
object MacroStore {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "macros").apply { mkdirs() }

    fun autoName(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MMM d a h:mm", Locale.ENGLISH).format(Date(time))

    fun saveNew(
        ctx: Context, strokes: List<Stroke>,
        videoPath: String? = null, videoOffsetMs: Long = 0L, rotation: Int = 0,
    ): Macro {
        val now = System.currentTimeMillis()
        val macro = Macro(UUID.randomUUID().toString(), autoName(now), now, strokes, videoPath, videoOffsetMs, rotation)
        write(ctx, macro)
        return macro
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val m = read(ctx, id) ?: return
        write(ctx, m.copy(name = newName))
    }

    /** 편집기에서 스트로크 변경(삭제/자르기/분할/이동/추가) 후 저장. */
    fun updateStrokes(ctx: Context, id: String, strokes: List<Stroke>) {
        val m = read(ctx, id) ?: return
        write(ctx, m.copy(strokes = strokes))
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun list(ctx: Context): List<Macro> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Macro? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun write(ctx: Context, macro: Macro) {
        File(dir(ctx), "${macro.id}.json").writeText(toJson(macro))
    }

    private fun toJson(m: Macro): String {
        val strokes = JSONArray()
        for (s in m.strokes) {
            val samples = JSONArray()
            for (p in s.samples) {
                samples.put(
                    JSONObject().put("t", p.t).put("x", p.nx.toDouble()).put("y", p.ny.toDouble())
                )
            }
            strokes.put(JSONObject().put("startMs", s.startMs).put("durationMs", s.durationMs).put("samples", samples))
        }
        return JSONObject()
            .put("id", m.id).put("name", m.name).put("createdAt", m.createdAt)
            .put("strokes", strokes)
            .put("videoPath", m.videoPath ?: JSONObject.NULL)
            .put("videoOffsetMs", m.videoOffsetMs)
            .put("rotation", m.rotation)
            .toString()
    }

    private fun fromJson(text: String): Macro {
        val o = JSONObject(text)
        val strokesArr = o.getJSONArray("strokes")
        val strokes = ArrayList<Stroke>(strokesArr.length())
        for (i in 0 until strokesArr.length()) {
            val so = strokesArr.getJSONObject(i)
            val sampArr = so.getJSONArray("samples")
            val samples = ArrayList<TouchSample>(sampArr.length())
            for (j in 0 until sampArr.length()) {
                val p = sampArr.getJSONObject(j)
                samples.add(TouchSample(p.getLong("t"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat()))
            }
            strokes.add(Stroke(so.optLong("startMs", 0L), so.optLong("durationMs", 0L), samples))
        }
        val vp = if (o.isNull("videoPath")) null else o.optString("videoPath", "").ifEmpty { null }
        return Macro(o.getString("id"), o.getString("name"), o.getLong("createdAt"), strokes, vp, o.optLong("videoOffsetMs", 0L), o.optInt("rotation", 0))
    }
}
