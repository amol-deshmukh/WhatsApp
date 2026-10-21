package com.example.whatsapp.data.remote.fake

import com.example.whatsapp.data.remote.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A "CDN" backed by a private directory. Transfers are chunked with a per-chunk delay to make
 * progress visible, fail with [IOException] when the (simulated) network drops, and resume from
 * the last persisted byte — the same contract as GCS/S3 resumable uploads or HTTP Range downloads.
 */
class FakeMediaStore(
    private val root: File,
    private val isNetworkOnline: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chunkSize: Int = 128 * 1024,
    private val chunkDelayMs: Long = 60,
) : MediaStore {

    private val partialDir get() = File(root, "partial").apply { mkdirs() }

    override suspend fun upload(uploadId: String, file: File, onProgress: (Float) -> Unit): String =
        withContext(ioDispatcher) {
            val partial = File(partialDir, uploadId)
            val total = file.length()
            // "Query upload status": the server tells us how many bytes it already has.
            var offset = partial.length().coerceAtMost(total)
            RandomAccessFile(file, "r").use { source ->
                FileOutputStream(partial, /* append = */ true).use { sink ->
                    val buffer = ByteArray(chunkSize)
                    source.seek(offset)
                    while (offset < total) {
                        ensureOnline()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        delay(chunkDelayMs)
                        sink.write(buffer, 0, read)
                        offset += read
                        onProgress(offset.toFloat() / total)
                    }
                }
            }
            val finalFile = File(root, uploadId)
            if (!partial.renameTo(finalFile)) throw IOException("Failed to finalize upload $uploadId")
            "$SCHEME$uploadId"
        }

    override suspend fun download(remoteUrl: String, destination: File, onProgress: (Float) -> Unit) =
        withContext(ioDispatcher) {
            val source = File(root, remoteUrl.removePrefix(SCHEME))
            if (!source.exists()) throw IOException("404: $remoteUrl")
            val partial = File(destination.path + ".part")
            val total = source.length()
            var offset = partial.length().coerceAtMost(total) // Range: bytes=offset-
            RandomAccessFile(source, "r").use { input ->
                FileOutputStream(partial, true).use { out ->
                    val buffer = ByteArray(chunkSize)
                    input.seek(offset)
                    while (offset < total) {
                        ensureOnline()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        delay(chunkDelayMs)
                        out.write(buffer, 0, read)
                        offset += read
                        onProgress(offset.toFloat() / total)
                    }
                }
            }
            if (!partial.renameTo(destination)) throw IOException("Failed to finalize download")
        }

    private fun ensureOnline() {
        if (!isNetworkOnline()) throw IOException("Network lost during transfer")
    }

    private companion object {
        const val SCHEME = "fakecdn://"
    }
}
