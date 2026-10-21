package com.example.whatsapp.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.example.whatsapp.domain.model.MessageType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class PreparedMedia(
    val type: MessageType,
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val microThumbnail: ByteArray,
)

class MediaTooLargeException(val limitBytes: Long) : IOException("Media exceeds ${limitBytes / (1024 * 1024)} MB")

/**
 * Client-side media preparation before upload:
 *  - images are downscaled (long edge 1600px) and re-encoded as JPEG q=80: a 4-8MB camera photo
 *    becomes ~200-400KB. Re-encoding also strips EXIF (GPS location!) — a privacy requirement.
 *  - videos are size-checked and copied; production would transcode with Media3 Transformer
 *    (H.264 720p, ~1.5Mbps) to cut upload size 5-10x.
 *  - a ~64px "micro thumbnail" is generated and sent inline with the message, so the receiver
 *    renders a blurred preview instantly, before the full media is downloaded.
 *
 * Files are copied into app-private storage because a content:// Uri grant from the picker does
 * not survive process death, while the upload must.
 */
class MediaProcessor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val mediaDir: File get() = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun prepare(uri: Uri, messageId: String): PreparedMedia = withContext(ioDispatcher) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("video/")) prepareVideo(uri, messageId, mime) else prepareImage(uri, messageId)
    }

    private fun prepareImage(uri: Uri, messageId: String): PreparedMedia {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("Unsupported image")

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.inSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
        }
        val sampled = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Failed to decode image")

        val rotation = resolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).rotationDegrees } ?: 0
        }
        val (targetW, targetH) = ImageSizing.scaledSize(sampled.width, sampled.height, MAX_IMAGE_DIMENSION)
        val scaled = if (targetW != sampled.width) sampled.scale(targetW, targetH) else sampled
        val upright = scaled.rotate(rotation)

        val file = File(mediaDir, "$messageId.jpg")
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val thumb = upright.microThumbnail()

        return PreparedMedia(
            type = MessageType.IMAGE,
            file = file,
            mimeType = "image/jpeg",
            width = upright.width,
            height = upright.height,
            durationMs = null,
            microThumbnail = thumb,
        )
    }

    private fun prepareVideo(uri: Uri, messageId: String, mime: String): PreparedMedia {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        if (size > MAX_VIDEO_BYTES) throw MediaTooLargeException(MAX_VIDEO_BYTES)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            var width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (rotation == 90 || rotation == 270) width = height.also { height = width }
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val frame = retriever.getFrameAtTime(0) ?: throw IOException("Unreadable video")

            val extension = if (mime == "video/mp4") "mp4" else mime.substringAfter('/', "mp4")
            val file = File(mediaDir, "$messageId.$extension")
            context.contentResolver.openInputStream(uri).use { input ->
                file.outputStream().use { output -> input?.copyTo(output) ?: throw IOException("Unreadable video") }
            }

            return PreparedMedia(
                type = MessageType.VIDEO,
                file = file,
                mimeType = mime,
                width = width,
                height = height,
                durationMs = duration,
                microThumbnail = frame.microThumbnail(),
            )
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.microThumbnail(): ByteArray {
        val (w, h) = ImageSizing.scaledSize(width, height, MICRO_THUMB_DIMENSION)
        val small = scale(w, h)
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 50, out)
            out.toByteArray()
        }
    }

    companion object {
        const val MAX_IMAGE_DIMENSION = 1600
        const val JPEG_QUALITY = 80
        const val MICRO_THUMB_DIMENSION = 64
        const val MAX_VIDEO_BYTES = 64L * 1024 * 1024
    }
}
