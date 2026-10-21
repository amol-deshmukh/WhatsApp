package com.example.whatsapp.data.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Upload/download progress kept in memory, deliberately NOT in Room: writing progress to the
 * messages table would invalidate the chat's PagingSource dozens of times per second and re-query
 * the whole visible page. Only state transitions (UPLOADING -> done) are persisted.
 */
class TransferProgressTracker {
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun update(messageId: String, value: Float) {
        // Quantize to whole percents so collectors aren't flooded with tiny deltas.
        val quantized = (value * 100).toInt() / 100f
        progress.update { if (it[messageId] == quantized) it else it + (messageId to quantized) }
    }

    fun clear(messageId: String) = progress.update { it - messageId }

    fun observe(messageId: String): Flow<Float?> = progress.map { it[messageId] }.distinctUntilChanged()
}
