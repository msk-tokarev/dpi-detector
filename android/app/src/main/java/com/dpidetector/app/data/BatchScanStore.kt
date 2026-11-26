// Author: by Ivan Tokarev
package com.dpidetector.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dpidetector.app.model.WebhostItem
import org.json.JSONObject

enum class BatchJobStatus { IDLE, RUNNING, PAUSED, COMPLETED }

enum class BatchItemState { QUEUED, RUNNING, DONE, FAILED }

data class BatchQueueItem(
    val idx: Int,
    val target: String,
    val port: Int,
    val sni: String,
    val state: BatchItemState,
    val item: WebhostItem? = null,
    val error: String? = null,
    val finishedAt: Long = 0L,
) {
    val hasDpiIssue: Boolean
        get() = item != null && (item.sniBlocked || item.tcp1620.code == "DETECTED" || item.siberian.code == "DETECTED" || !item.reachable)
}

data class BatchScanUiState(
    val status: BatchJobStatus = BatchJobStatus.IDLE,
    val items: List<BatchQueueItem> = emptyList(),
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val issues: Int = 0,
    val etaSeconds: Long? = null,
    val defaultPort: Int = 443,
    val defaultSni: String = "",
    val recoveredAfterRestart: Boolean = false,
)

class BatchScanStore(context: Context) : SQLiteOpenHelper(context, "dpidetector_batch.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS batch_meta (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                status TEXT NOT NULL,
                port INTEGER NOT NULL,
                sni TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS batch_items (
                idx INTEGER PRIMARY KEY,
                target TEXT NOT NULL,
                port INTEGER NOT NULL,
                sni TEXT NOT NULL,
                state TEXT NOT NULL,
                result_json TEXT,
                error_msg TEXT,
                finished_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS batch_items")
        db.execSQL("DROP TABLE IF EXISTS batch_meta")
        onCreate(db)
    }

    @Synchronized
    fun replaceQueue(targets: List<String>, port: Int, sni: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("batch_items", null, null)
            db.delete("batch_meta", null, null)

            val meta = ContentValues().apply {
                put("id", 1)
                put("status", BatchJobStatus.RUNNING.name)
                put("port", port)
                put("sni", sni)
                put("updated_at", System.currentTimeMillis())
            }
            db.insert("batch_meta", null, meta)

            targets.forEachIndexed { index, target ->
                val cv = ContentValues().apply {
                    put("idx", index)
                    put("target", target)
                    put("port", port)
                    put("sni", sni)
                    put("state", BatchItemState.QUEUED.name)
                    put("finished_at", 0L)
                }
                db.insert("batch_items", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Recovers an interrupted RUNNING job after process death:
     * transitions job to PAUSED and puts in-flight RUNNING items back to QUEUED.
     */
    @Synchronized
    fun recoverInterrupted(): Boolean {
        val db = writableDatabase
        var wasRunning = false
        db.rawQuery("SELECT status FROM batch_meta WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) {
                val st = c.getString(0)
                wasRunning = st == BatchJobStatus.RUNNING.name
            }
        }
        if (wasRunning) {
            db.beginTransaction()
            try {
                val resetItems = ContentValues().apply { put("state", BatchItemState.QUEUED.name) }
                db.update("batch_items", resetItems, "state = ?", arrayOf(BatchItemState.RUNNING.name))
                val meta = ContentValues().apply {
                    put("status", BatchJobStatus.PAUSED.name)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("batch_meta", meta, "id = 1", null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return wasRunning
    }

    @Synchronized
    fun setJobStatus(status: BatchJobStatus) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("status", status.name)
            put("updated_at", System.currentTimeMillis())
        }
        val rows = db.update("batch_meta", cv, "id = 1", null)
        if (rows == 0 && status != BatchJobStatus.IDLE) {
            cv.put("id", 1)
            cv.put("port", 443)
            cv.put("sni", "")
            db.insert("batch_meta", null, cv)
        }
    }

    @Synchronized
    fun claimNextQueued(): BatchQueueItem? {
        val db = writableDatabase
        var claimed: BatchQueueItem? = null
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT idx, target, port, sni FROM batch_items WHERE state = ? ORDER BY idx ASC LIMIT 1",
                arrayOf(BatchItemState.QUEUED.name),
            ).use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getInt(0)
                    val target = c.getString(1)
                    val port = c.getInt(2)
                    val sni = c.getString(3)
                    val cv = ContentValues().apply { put("state", BatchItemState.RUNNING.name) }
                    db.update("batch_items", cv, "idx = ?", arrayOf(idx.toString()))
                    claimed = BatchQueueItem(
                        idx = idx,
                        target = target,
                        port = port,
                        sni = sni,
                        state = BatchItemState.RUNNING,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return claimed
    }

    @Synchronized
    fun markItemResult(
        idx: Int,
        state: BatchItemState,
        resultJson: String?,
        errorMsg: String?,
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("state", state.name)
            put("result_json", resultJson)
            put("error_msg", errorMsg)
            put("finished_at", finishedAt)
        }
        db.update("batch_items", cv, "idx = ?", arrayOf(idx.toString()))
    }

    @Synchronized
    fun resetRunningToQueued() {
        val db = writableDatabase
        val cv = ContentValues().apply { put("state", BatchItemState.QUEUED.name) }
        db.update("batch_items", cv, "state = ?", arrayOf(BatchItemState.RUNNING.name))
    }

    @Synchronized
    fun retryFailed(): Int {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("state", BatchItemState.QUEUED.name)
            putNull("error_msg")
            put("finished_at", 0L)
        }
        return db.update("batch_items", cv, "state = ?", arrayOf(BatchItemState.FAILED.name))
    }

    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("batch_items", null, null)
            db.delete("batch_meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun snapshot(etaSeconds: Long? = null, recoveredAfterRestart: Boolean = false): BatchScanUiState {
        val db = readableDatabase
        var status = BatchJobStatus.IDLE
        var port = 443
        var sni = ""
        db.rawQuery("SELECT status, port, sni FROM batch_meta WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) {
                status = runCatching { BatchJobStatus.valueOf(c.getString(0)) }.getOrDefault(BatchJobStatus.IDLE)
                port = c.getInt(1)
                sni = c.getString(2)
            }
        }

        val items = mutableListOf<BatchQueueItem>()
        db.rawQuery(
            "SELECT idx, target, port, sni, state, result_json, error_msg, finished_at FROM batch_items ORDER BY idx ASC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val st = runCatching { BatchItemState.valueOf(c.getString(4)) }.getOrDefault(BatchItemState.QUEUED)
                val rawJson = if (c.isNull(5)) null else c.getString(5)
                val errMsg = if (c.isNull(6)) null else c.getString(6)
                val parsedItem = rawJson?.let { runCatching { WebhostItem.from(JSONObject(it)) }.getOrNull() }
                items += BatchQueueItem(
                    idx = c.getInt(0),
                    target = c.getString(1),
                    port = c.getInt(2),
                    sni = c.getString(3),
                    state = st,
                    item = parsedItem,
                    error = errMsg,
                    finishedAt = c.getLong(7),
                )
            }
        }

        if (items.isEmpty()) {
            return BatchScanUiState()
        }

        val completed = items.count { it.state == BatchItemState.DONE || it.state == BatchItemState.FAILED }
        val failed = items.count { it.state == BatchItemState.FAILED }
        val issues = items.count { it.hasDpiIssue || it.state == BatchItemState.FAILED }

        return BatchScanUiState(
            status = status,
            items = items,
            total = items.size,
            completed = completed,
            failed = failed,
            issues = issues,
            etaSeconds = etaSeconds,
            defaultPort = port,
            defaultSni = sni,
            recoveredAfterRestart = recoveredAfterRestart,
        )
    }
}
