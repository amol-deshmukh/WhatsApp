package com.example.whatsapp

import android.app.Application
import android.os.StrictMode
import androidx.tracing.trace
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
        if (BuildConfig.DEBUG) enableStrictMode()
        container = trace("ChatApplication.createContainer") { AppContainer(this) }
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

    /** Debug only: logs main-thread disk/network access and leaked closeables, which are jank and leak sources. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
