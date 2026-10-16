package dev.optimizer.android.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Change(
    val id: String,
    val ts: Long,
    val category: String,      // optimize | debloat | doze | launcher | play | block | maintenance | restore
    val group: String,         // fix id this entry belongs to (toggle state)
    val title: String,
    val detail: String,
    val undoCmds: List<String>,
    val undone: Boolean,
) {
    val undoable: Boolean get() = undoCmds.isNotEmpty()
}

/** Every change this app makes through root is recorded here together with the commands that revert it. */
object ChangeLog {
    @Synchronized
    fun all(): List<Change> {
        val f = Store.changeLogFile()
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val u = o.optJSONArray("undo") ?: JSONArray()
                Change(
                    o.getString("id"), o.getLong("ts"), o.optString("cat"), o.optString("group"),
                    o.optString("title"), o.optString("detail"),
                    (0 until u.length()).map { i -> u.getString(i) }, o.optBoolean("undone")
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(list: List<Change>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("ts", it.ts).put("cat", it.category).put("group", it.group)
                    .put("title", it.title).put("detail", it.detail)
                    .put("undo", JSONArray(it.undoCmds)).put("undone", it.undone)
            )
        }
        Store.changeLogFile().writeText(arr.toString())
    }

    @Synchronized
    fun add(category: String, group: String, title: String, detail: String, undoCmds: List<String>): Change {
        val c = Change(UUID.randomUUID().toString(), System.currentTimeMillis(), category, group, title, detail, undoCmds, false)
        save(all() + c)
        return c
    }

    @Synchronized
    fun markUndone(id: String, undone: Boolean = true) {
        save(all().map { if (it.id == id) it.copy(undone = undone) else it })
    }

    fun isActive(group: String) = all().any { it.group == group && !it.undone && it.undoable }

    fun activeUndoable(): List<Change> = all().filter { !it.undone && it.undoable }

    /** Runs the undo commands (root). Returns error text or null on success. */
    fun undo(c: Change): String? {
        if (!c.undoable) return "This entry cannot be undone automatically."
        val errs = StringBuilder()
        for (cmd in c.undoCmds) {
            val r = Sh.root(cmd, 60)
            if (!r.ok) errs.append("`$cmd` failed: ${r.text}\n")
        }
        return if (errs.isEmpty()) {
            markUndone(c.id)
            // keep persisted block list consistent when a block is undone
            if (c.category == "block") Store.removeBlocked(c.group.removePrefix("block:"))
            null
        } else errs.toString().trim()
    }
}
