package com.example.whatsapp

import com.example.whatsapp.data.remote.ClientFrame
import com.example.whatsapp.data.remote.ServerFrame
import com.example.whatsapp.data.remote.fake.FakeChatServer
import com.example.whatsapp.domain.model.MessageStatus
import com.example.whatsapp.domain.model.MessageType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Verifies the server contract the client's sync design relies on. */
class FakeChatServerTest {

    private fun TestScope.server() = FakeChatServer(backgroundScope, clock = { 1_000L }, random = Random(1))

    private fun send(id: String) = ClientFrame.Send(id, "alice", MessageType.TEXT, "hi", media = null)

    @Test
    fun `duplicate sends are acked with the same seq and processed once`() = runTest {
        val server = server()
        val received = mutableListOf<ServerFrame>()
        server.attach { received += it }

        server.onFrame(send("m1"))
        server.onFrame(send("m1")) // retry after a lost ack
        advanceTimeBy(10_000); runCurrent() // backgroundScope work is ignored by advanceUntilIdle

        val acks = received.filterIsInstance<ServerFrame.Ack>()
        assertEquals(2, acks.size)
        assertEquals(acks[0].seq, acks[1].seq)
        assertEquals(1, received.filterIsInstance<ServerFrame.NewMessage>().size) // one bot reply, not two
    }

    @Test
    fun `events produced while offline are replayed after the client cursor`() = runTest {
        val server = server()
        val firstSession = mutableListOf<ServerFrame>()
        val session = server.attach { firstSession += it }

        server.onFrame(send("m1"))
        server.detach(session) // connection drops before receipts/reply arrive
        advanceTimeBy(10_000); runCurrent() // backgroundScope work is ignored by advanceUntilIdle

        val ackSeq = firstSession.filterIsInstance<ServerFrame.Ack>().single().seq
        val replayed = mutableListOf<ServerFrame>()
        server.attach { replayed += it }
        server.onFrame(ClientFrame.Hello(lastSeq = ackSeq))

        val sequenced = replayed.filterIsInstance<ServerFrame.Sequenced>()
        assertEquals(
            listOf(MessageStatus.DELIVERED, MessageStatus.READ),
            sequenced.filterIsInstance<ServerFrame.StatusChanged>().map { it.status },
        )
        assertEquals(1, sequenced.filterIsInstance<ServerFrame.NewMessage>().size)
        assertTrue(sequenced.zipWithNext().all { (a, b) -> a.seq < b.seq })
        assertEquals(ServerFrame.SyncComplete, replayed.last())

        // A cursor at the latest seq replays nothing.
        val upToDate = mutableListOf<ServerFrame>()
        server.attach { upToDate += it }
        server.onFrame(ClientFrame.Hello(lastSeq = sequenced.last().seq))
        assertEquals(listOf<ServerFrame>(ServerFrame.SyncComplete), upToDate)
    }

    @Test
    fun `stale detach does not drop a newer session`() = runTest {
        val server = server()
        val old = server.attach { }
        val received = mutableListOf<ServerFrame>()
        server.attach { received += it }
        server.detach(old)

        server.onFrame(send("m2"))
        assertTrue(received.any { it is ServerFrame.Ack })
    }
}
