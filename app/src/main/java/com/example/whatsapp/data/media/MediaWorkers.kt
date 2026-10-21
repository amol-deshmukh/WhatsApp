package com.example.whatsapp.data.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.whatsapp.ChatApplication
import com.example.whatsapp.domain.model.TransferState
import com.example.whatsapp.security.childSafely
import com.example.whatsapp.security.sha256Hex
import java.io.File
import java.io.IOException

private const val MAX_ATTEMPTS = 5

/**
 * Uploads a message's media, then records the remote URL. Recording the URL is what makes the
 * message eligible for the outbox, so the chat message is only sent once its media exists on the
 * CDN — the receiver never gets a message pointing at a missing blob.
 *
 * Production: call setForeground() for large videos so the upload survives the 10-minute
 * background execution limit, and show a progress notification.
 */
class MediaUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        val dao = container.database.messageDao()
        val progress = container.transferProgress
        val messageId = inputData.getString(TransferScheduler.KEY_MESSAGE_ID) ?: return Result.failure()

        val message = dao.get(messageId) ?: return Result.success() // deleted meanwhile
        if (message.mediaRemoteUrl != null) return Result.success() // already done (idempotent)
        val file = message.mediaLocalPath?.let(::File)?.takeIf { it.exists() }
            ?: return Result.failure().also { dao.markFailed(messageId, TransferState.FAILED) }

        dao.setTransferState(messageId, TransferState.UPLOADING)
        return try {
            val url = container.mediaStore.upload(messageId, file) { progress.update(messageId, it) }
            dao.setUploaded(messageId, url)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                dao.markFailed(messageId, TransferState.FAILED)
                Result.failure()
            } else {
                dao.setTransferState(messageId, TransferState.UPLOAD_PENDING)
                Result.retry()
            }
        } finally {
            progress.clear(messageId)
        }
    }
}

class MediaDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ChatApplication).container
        val dao = container.database.messageDao()
        val progress = container.transferProgress
        val messageId = inputData.getString(TransferScheduler.KEY_MESSAGE_ID) ?: return Result.failure()

        val message = dao.get(messageId) ?: return Result.success()
        if (message.mediaLocalPath != null) return Result.success()
        val url = message.mediaRemoteUrl ?: return Result.failure()

        val extension = if (message.mediaMime?.startsWith("video/") == true) "mp4" else "jpg"
        // messageId came off the wire: validated in SyncEngine, and the path is confined here too.
        val destination = try {
            container.mediaProcessor.mediaDir.childSafely("$messageId.$extension")
        } catch (e: IOException) {
            dao.setTransferState(messageId, TransferState.FAILED)
            return Result.failure()
        }

        dao.setTransferState(messageId, TransferState.DOWNLOADING)
        return try {
            container.mediaStore.download(url, destination) { progress.update(messageId, it) }
            // Integrity: the bytes must match the hash and size the sender put in the message.
            // A CDN/MITM can't substitute content, and a corrupted transfer is never shown or parsed.
            if (destination.length() != message.mediaSizeBytes || destination.sha256Hex() != message.mediaSha256) {
                destination.delete()
                dao.setTransferState(messageId, TransferState.FAILED)
                return Result.failure()
            }
            dao.setDownloaded(messageId, destination.absolutePath)
            Result.success()
        } catch (e: IOException) {
            dao.setTransferState(messageId, TransferState.DOWNLOAD_AVAILABLE)
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } finally {
            progress.clear(messageId)
        }
    }
}
