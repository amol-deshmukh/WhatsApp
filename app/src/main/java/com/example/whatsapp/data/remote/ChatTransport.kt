package com.example.whatsapp.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/**
 * A single long-lived, bidirectional connection to the chat server (WebSocket/MQTT in production).
 * Reconnection policy lives in [com.example.whatsapp.data.sync.ConnectionManager], not here, so the
 * transport stays a dumb pipe that is easy to fake.
 */
interface ChatTransport {
    /** Frames from the server, in order. Single consumer. */
    val frames: Flow<ServerFrame>

    val isOpen: StateFlow<Boolean>

    @Throws(IOException::class)
    suspend fun connect()

    @Throws(IOException::class)
    suspend fun send(frame: ClientFrame)

    fun close()
}

/**
 * Blob storage for media (a CDN / object store in production). Media bytes never travel over the
 * chat socket: the message only carries a URL + metadata + micro thumbnail.
 */
interface MediaStore {
    /** Resumable upload: implementations continue from the bytes the server already has for [uploadId]. */
    @Throws(IOException::class)
    suspend fun upload(uploadId: String, file: File, onProgress: (Float) -> Unit): String

    /** Resumable download into [destination] (continues a partial file when present). */
    @Throws(IOException::class)
    suspend fun download(remoteUrl: String, destination: File, onProgress: (Float) -> Unit)
}
