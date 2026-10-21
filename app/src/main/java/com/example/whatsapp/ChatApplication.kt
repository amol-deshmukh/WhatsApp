package com.example.whatsapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.example.whatsapp.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.chatRepository.seedIfEmpty() }
        container.connectionManager.start()
        container.syncEngine.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundConnectionObserver())
    }

    /** Socket open while any activity is visible; closed in background (push takes over). */
    private inner class ForegroundConnectionObserver : DefaultLifecycleObserver {
        private var release: (() -> Unit)? = null

        override fun onStart(owner: LifecycleOwner) {
            release = container.connectionManager.acquire()
        }

        override fun onStop(owner: LifecycleOwner) {
            release?.invoke()
            release = null
            container.appScope.launch {
                val unsent = container.database.messageDao().observeUnsentCount().first()
                if (unsent > 0) container.transferScheduler.enqueueOutboxFlush()
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
