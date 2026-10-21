package com.example.whatsapp.di

import android.content.Context
import androidx.work.WorkManager
import com.example.whatsapp.data.ChatRepository
import com.example.whatsapp.data.local.AppDatabase
import com.example.whatsapp.data.media.MediaProcessor
import com.example.whatsapp.data.media.TransferProgressTracker
import com.example.whatsapp.data.media.TransferScheduler
import com.example.whatsapp.data.network.NetworkMonitor
import com.example.whatsapp.data.remote.ChatTransport
import com.example.whatsapp.data.remote.MediaStore
import com.example.whatsapp.data.remote.fake.FakeChatServer
import com.example.whatsapp.data.remote.fake.FakeChatTransport
import com.example.whatsapp.data.remote.fake.FakeMediaStore
import com.example.whatsapp.data.sync.ConnectionManager
import com.example.whatsapp.data.sync.SyncEngine
import com.example.whatsapp.security.SecuritySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency injection. Hilt would be the production choice (scoping, testing overrides,
 * WorkerFactory integration); a hand-written graph keeps this sample's wiring readable in one file.
 *
 * Swapping the fakes for real implementations (OkHttp WebSocket, HTTPS CDN client) is a change to
 * this file only — nothing above the data layer knows the backend is simulated.
 */
class AppContainer(context: Context) {

    /** Outlives any screen; for work that must finish even if the user navigates away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(context)

    val securitySettings = SecuritySettings(context)

    val networkMonitor = NetworkMonitor(context, appScope)
    private val isOnline = { networkMonitor.status.value.isOnline }

    private val fakeServer = FakeChatServer(appScope)
    private val transport: ChatTransport = FakeChatTransport(fakeServer, isOnline, appScope)
    val mediaStore: MediaStore = FakeMediaStore(File(context.filesDir, "fake_cdn"), isOnline)

    val transferProgress = TransferProgressTracker()
    val mediaProcessor = MediaProcessor(context)
    val transferScheduler = TransferScheduler(WorkManager.getInstance(context))

    val connectionManager = ConnectionManager(
        transport = transport,
        networkStatus = networkMonitor.status,
        lastSeq = { database.syncStateDao().lastSeq() ?: 0 },
        scope = appScope,
    )

    val syncEngine = SyncEngine(
        db = database,
        transport = transport,
        connection = connectionManager,
        transfers = transferScheduler,
        networkStatus = networkMonitor.status,
        scope = appScope,
    )

    val chatRepository = ChatRepository(
        db = database,
        syncEngine = syncEngine,
        mediaProcessor = mediaProcessor,
        transfers = transferScheduler,
        progress = transferProgress,
    )
}
