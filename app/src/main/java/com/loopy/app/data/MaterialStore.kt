package com.loopy.app.data

import android.content.Context
import com.loopy.app.core.material.Material
import com.loopy.app.core.material.MaterialRegistry
import com.loopy.app.core.material.Meta
import com.loopy.app.core.material.NoParams
import com.loopy.app.core.material.TouchParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Material 트리 저장소.
 *
 * 촬영 매크로의 스트로크·영상은 무거우므로 [com.loopy.app.macro.MacroStore] 에 그대로 두고,
 * Material 은 그것을 id 로 참조만 한다. 빌드를 열 때마다 수 MB 를 읽지 않기 위해서다.
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
            // 첫 실행이거나 구버전 사용자. 기존 매크로/플레이리스트를 Material 로 옮긴다.
            val initial = Presets.all() + Migration.fromLegacy(ctx)
            save(ctx, initial)
            return initial
        }
        val stored = runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("materials") ?: JSONArray()
            (0 until arr.length()).map { readMaterial(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        // 앱 업데이트로 새 프리셋이 생겼을 수 있다. 사용자가 고친 것은 건드리지 않는다.
        val missing = Presets.all().filter { p -> stored.none { it.id == p.id } }
        if (missing.isEmpty()) return stored
        val merged = stored + missing
        save(ctx, merged)
        return merged
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
                    .put("createdAt", m.meta.createdAt),
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

    /** 촬영 매크로를 가리키는 Material 하나 만들기. 라이브러리와 빌드 양쪽에서 쓴다. */
    fun touchMaterial(macroId: String, name: String, createdAt: Long): Material = Material(
        id = newId(),
        typeId = "touch",
        params = TouchParams(macroId),
        meta = Meta(name = name, createdAt = createdAt),
    )
}
