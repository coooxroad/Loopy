package com.loopy.app.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 플레이리스트를 filesDir/playlists/{id}.json 로 저장/관리. */
object PlaylistStore {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "playlists").apply { mkdirs() }

    fun save(
        ctx: Context,
        name: String,
        macroIds: List<String>,
        shuffle: Boolean,
        cycles: Int,
        gapMs: Int,
        existingId: String? = null,
    ): Playlist {
        val pl = Playlist(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            macroIds = macroIds,
            shuffle = shuffle,
            cycles = cycles,
            gapMs = gapMs,
        )
        File(dir(ctx), "${pl.id}.json").writeText(toJson(pl))
        return pl
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun list(ctx: Context): List<Playlist> =
        (dir(ctx).listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun read(ctx: Context, id: String): Playlist? =
        runCatching { fromJson(File(dir(ctx), "$id.json").readText()) }.getOrNull()

    private fun toJson(p: Playlist): String {
        val ids = JSONArray()
        p.macroIds.forEach { ids.put(it) }
        return JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("createdAt", p.createdAt)
            .put("macroIds", ids)
            .put("shuffle", p.shuffle)
            .put("cycles", p.cycles)
            .put("gapMs", p.gapMs)
            .toString()
    }

    private fun fromJson(text: String): Playlist {
        val o = JSONObject(text)
        val arr = o.getJSONArray("macroIds")
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) ids.add(arr.getString(i))
        return Playlist(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            macroIds = ids,
            shuffle = o.getBoolean("shuffle"),
            cycles = o.getInt("cycles"),
            gapMs = o.optInt("gapMs", 0),
        )
    }
}
