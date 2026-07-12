package com.loopy.app.core.record

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 녹화 리소스.
 *
 * 스트로크와 영상은 Material 트리에 담기엔 너무 무겁다. 빌드를 목록에 띄울 때마다
 * 수천 개의 좌표를 읽을 수는 없다. 그래서 리소스는 따로 저장하고 트리는 id 로 참조만 한다.
 *
 * 이름·즐겨찾기 같은 정보는 여기 없다. 그것은 Material 의 meta 가 갖는다.
 * 리소스는 데이터일 뿐, 사용자가 보는 대상이 아니다.
 */
data class Recording(
    val id: String,
    val videoPath: String? = null,
    /** 첫 스트로크가 영상 시작 후 몇 ms 뒤인지. 편집기의 싱크 보정에 쓴다. */
    val videoOffsetMs: Long = 0L,
    /** 녹화 중 회전 변화. (매크로 기준 ms, 회전값) */
    val rotationEvents: List<RotationEvent> = emptyList(),
)

data class RotationEvent(val tMs: Long, val rotation: Int)

/**
 * 스트로크 저장소.
 *
 * id 하나당 궤적 하나. touch Material 이 이것을 참조한다.
 * 파일을 스트로크마다 나누지 않고 한 곳에 모으는 이유는, 하나의 녹화가 수십 개의
 * 스트로크를 만들어 파일이 폭증하기 때문이다.
 */
object StrokeStore {

    private const val FILE = "strokes.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private var cache: MutableMap<String, Stroke>? = null

    private fun load(ctx: Context): MutableMap<String, Stroke> {
        cache?.let { return it }
        val f = file(ctx)
        val map = HashMap<String, Stroke>()
        if (f.exists()) {
            runCatching {
                val root = JSONObject(f.readText())
                for (id in root.keys()) {
                    map[id] = fromJson(root.getJSONObject(id))
                }
            }
        }
        cache = map
        return map
    }

    private fun persist(ctx: Context) {
        val map = cache ?: return
        val root = JSONObject()
        for ((id, s) in map) root.put(id, toJson(s))
        file(ctx).writeText(root.toString())
    }

    fun get(ctx: Context, id: String): Stroke? = load(ctx)[id]

    fun put(ctx: Context, stroke: Stroke): String {
        val id = UUID.randomUUID().toString()
        load(ctx)[id] = stroke
        persist(ctx)
        return id
    }

    fun putAll(ctx: Context, strokes: List<Stroke>): List<String> {
        val map = load(ctx)
        val ids = strokes.map { s ->
            val id = UUID.randomUUID().toString()
            map[id] = s
            id
        }
        persist(ctx)
        return ids
    }

    fun update(ctx: Context, id: String, stroke: Stroke) {
        load(ctx)[id] = stroke
        persist(ctx)
    }

    fun delete(ctx: Context, ids: List<String>) {
        val map = load(ctx)
        ids.forEach { map.remove(it) }
        persist(ctx)
    }

    private fun toJson(s: Stroke): JSONObject {
        val samples = JSONArray()
        for (p in s.samples) {
            samples.put(
                JSONObject()
                    .put("t", p.t)
                    .put("x", p.nx.toDouble())
                    .put("y", p.ny.toDouble()),
            )
        }
        return JSONObject()
            .put("dur", s.durationMs)
            .put("rot", s.rotation)
            .put("samples", samples)
    }

    private fun fromJson(o: JSONObject): Stroke {
        val arr = o.getJSONArray("samples")
        val samples = ArrayList<TouchSample>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            samples.add(
                TouchSample(
                    p.optLong("t"),
                    p.optDouble("x").toFloat(),
                    p.optDouble("y").toFloat(),
                ),
            )
        }
        return Stroke(
            durationMs = o.optLong("dur"),
            samples = samples,
            rotation = o.optInt("rot", -1),
        )
    }
}

/** 녹화 메타(영상·회전) 저장소. */
object RecordingStore {

    private const val FILE = "recordings.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun readAll(ctx: Context): MutableMap<String, Recording> {
        val f = file(ctx)
        val map = HashMap<String, Recording>()
        if (!f.exists()) return map
        runCatching {
            val root = JSONObject(f.readText())
            for (id in root.keys()) {
                val o = root.getJSONObject(id)
                val evts = ArrayList<RotationEvent>()
                o.optJSONArray("rot")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        evts.add(RotationEvent(e.optLong("t"), e.optInt("r")))
                    }
                }
                map[id] = Recording(
                    id = id,
                    videoPath = o.optString("video").takeIf { it.isNotEmpty() },
                    videoOffsetMs = o.optLong("offset"),
                    rotationEvents = evts,
                )
            }
        }
        return map
    }

    fun get(ctx: Context, id: String): Recording? = readAll(ctx)[id]

    fun put(ctx: Context, rec: Recording) {
        val map = readAll(ctx)
        map[rec.id] = rec
        val root = JSONObject()
        for ((id, r) in map) {
            val evts = JSONArray()
            for (e in r.rotationEvents) {
                evts.put(JSONObject().put("t", e.tMs).put("r", e.rotation))
            }
            root.put(
                id,
                JSONObject()
                    .put("video", r.videoPath.orEmpty())
                    .put("offset", r.videoOffsetMs)
                    .put("rot", evts),
            )
        }
        file(ctx).writeText(root.toString())
    }

    fun newId(): String = UUID.randomUUID().toString()
}
