package com.example.whatsapp.data.media

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.whatsapp.data.sync.OutboxWorker
import java.util.concurrent.TimeUnit

/**
 * Media transfers run in WorkManager rather than a ViewModel/app scope because they must survive
 * the user leaving the screen, the app being backgrounded, and process death. Unique work names
 * keyed by message id make enqueueing idempotent (double-taps, retries, re-launch).
 */
class TransferScheduler(private val workManager: WorkManager) {

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueUpload(messageId: String) {
        val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
            .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_UPLOAD)
            .build()
        workManager.enqueueUniqueWork("upload-$messageId", ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueDownload(messageId: String) {
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("download-$messageId", ExistingWorkPolicy.KEEP, request)
    }

    /** Backstop for messages still unsent when the app goes to the background. */
    fun enqueueOutboxFlush() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("outbox-flush", ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val TAG_UPLOAD = "media-upload"
    }
}
