package com.loopy.app.data

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.MaterialRegistry
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Material 트리 저장소.
 *
 * 궤적과 영상은 무거우므로 [com.loopy.app.core.record.StrokeStore] 와
 * [com.loopy.app.core.record.RecordingStore] 에 두고, Material 은 id 로 참조만 한다.
 * 빌드를 목록에 띄울 때마다 수천 개의 좌표를 읽지 않기 위해서다.
 *
 * schema 필드를 두는 이유: 구조는 앞으로도 바뀐다. 바뀔 것을 알면서 버전을 안 붙이면
 * 나중에 사용자 데이터를 버리게 된다.
 */
object MaterialStore {

    private const val SCHEMA = 1
    private const val FILE = "materials.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): List<Material> {
        val f = file(ctx)
        if (!f.exists()) {
            val migrated = Migration.fromLegacy(ctx)
            if (migrated.isNotEmpty()) save(ctx, migrated)
            return migrated
        }
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("materials") ?: JSONArray()
            (0 until arr.length()).map { readMaterial(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, materials: List<Material>) {
        val arr = JSONArray()
        for (m in materials) arr.put(writeMaterial(m))
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("materials", arr)
        file(ctx).writeText(root.toString())
    }

    fun upsert(ctx: Context, material: Material) {
        val all = load(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == material.id }
        if (i >= 0) all[i] = material else all.add(material)
        save(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }

    fun get(ctx: Context, id: String): Material? = load(ctx).firstOrNull { it.id == id }

    // ── 직렬화 ──

    private fun writeMaterial(m: Material): JSONObject {
        val kids = JSONArray()
        for (c in m.children) kids.put(writeMaterial(c))
        return JSONObject()
            .put("id", m.id)
            .put("type", m.typeId)
            .put("enabled", m.enabled)
            .put("params", JSONObject(m.params.toMap()))
            .put("children", kids)
            .put(
                "meta",
                JSONObject()
                    .put("name", m.meta.name)
                    .put("note", m.meta.note)
                    .put("favorite", m.meta.favorite)
                    .put("folder", m.meta.folder)
                    .put("createdAt", m.meta.createdAt)
                    .put("x", m.meta.x.toDouble())
                    .put("y", m.meta.y.toDouble()),
            )
    }

    private fun readMaterial(o: JSONObject): Material {
        val typeId = o.getString("type")
        val type = MaterialRegistry.find(typeId)

        val paramsMap = HashMap<String, Any?>()
        o.optJSONObject("params")?.let { p ->
            for (k in p.keys()) paramsMap[k] = p.get(k)
        }

        val kidsArr = o.optJSONArray("children") ?: JSONArray()
        val kids = (0 until kidsArr.length()).map { readMaterial(kidsArr.getJSONObject(it)) }

        val mo = o.optJSONObject("meta")
        val meta = Meta(
            name = mo?.optString("name").orEmpty(),
            note = mo?.optString("note").orEmpty(),
            favorite = mo?.optBoolean("favorite") ?: false,
            folder = mo?.optString("folder")?.takeIf { it.isNotEmpty() },
            createdAt = mo?.optLong("createdAt") ?: 0L,
            x = (mo?.optDouble("x") ?: 0.0).toFloat().let { if (it.isNaN()) 0f else it },
            y = (mo?.optDouble("y") ?: 0.0).toFloat().let { if (it.isNaN()) 0f else it },
        )

        return Material(
            id = o.getString("id"),
            typeId = typeId,
            // 미등록 타입(플러그인 미설치 등)이라도 데이터를 잃지 않도록 빈 파라미터로 살려둔다.
            params = type?.parse(paramsMap) ?: NoParams,
            children = kids,
            meta = meta,
            enabled = o.optBoolean("enabled", true),
        )
    }

    fun newId(): String = UUID.randomUUID().toString()

}
