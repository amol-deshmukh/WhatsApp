package com.example.whatsapp.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.whatsapp.ChatApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs when the app is backgrounded with unsent messages: briefly holds the connection open so the
 * SyncEngine can drain the outbox, then lets it close again. Without this, a message typed just
 * before switching apps would sit unsent until the next foreground.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        val release = container.connectionManager.acquire()
        return try {
            val drained = withTimeoutOrNull(60_000) {
                container.database.messageDao().observeUnsentCount().first { it == 0 }
            }
            if (drained != null) Result.success() else Result.retry()
        } finally {
            release()
        }
    }
}
