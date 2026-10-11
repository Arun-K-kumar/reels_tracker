package com.reelcounter.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One row per reel you watched (counted after a minimum dwell time). */
class ReelDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "reels.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE views (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, author TEXT)")
        db.execSQL("CREATE INDEX idx_views_ts ON views(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(ts: Long, author: String?) {
        val cv = ContentValues().apply {
            put("ts", ts)
            put("author", author)
        }
        writableDatabase.insert("views", null, cv)
        changes.update { it + 1 }
    }

    fun allTimestamps(): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT ts FROM views ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    fun topAuthors(limit: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT author, COUNT(*) c FROM views WHERE author IS NOT NULL AND author != '' " +
                "GROUP BY author ORDER BY c DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    fun clear() {
        writableDatabase.delete("views", null, null)
        changes.update { it + 1 }
    }

    companion object {
        @Volatile private var inst: ReelDb? = null

        fun get(ctx: Context): ReelDb =
            inst ?: synchronized(this) { inst ?: ReelDb(ctx).also { inst = it } }

        /** Bumped on every write so the UI can refresh live. */
        val changes = MutableStateFlow(0)
    }
}
