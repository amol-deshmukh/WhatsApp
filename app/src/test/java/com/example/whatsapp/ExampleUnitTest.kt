package com.example.whatsapp

import com.example.whatsapp.data.media.ImageSizing
import com.example.whatsapp.data.sync.BackoffPolicy
import com.example.whatsapp.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MessageStatusTest {
    @Test
    fun `status only moves forward`() {
        assertTrue(MessageStatus.PENDING.canTransitionTo(MessageStatus.SENT))
        assertTrue(MessageStatus.SENT.canTransitionTo(MessageStatus.READ)) // DELIVERED may be skipped
        assertFalse(MessageStatus.READ.canTransitionTo(MessageStatus.DELIVERED)) // late, out-of-order event
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.SENT))
    }

    @Test
    fun `only unsent messages can fail and failed only retries to pending`() {
        assertTrue(MessageStatus.PENDING.canTransitionTo(MessageStatus.FAILED))
        assertFalse(MessageStatus.SENT.canTransitionTo(MessageStatus.FAILED))
        assertTrue(MessageStatus.FAILED.canTransitionTo(MessageStatus.PENDING))
        assertFalse(MessageStatus.FAILED.canTransitionTo(MessageStatus.READ))
    }
}

class BackoffPolicyTest {
    @Test
    fun `ceiling grows exponentially and is capped`() {
        val policy = BackoffPolicy(baseMs = 500, maxMs = 30_000)
        assertEquals(500, policy.ceilingFor(0))
        assertEquals(1_000, policy.ceilingFor(1))
        assertEquals(8_000, policy.ceilingFor(4))
        assertEquals(30_000, policy.ceilingFor(10))
        assertEquals(30_000, policy.ceilingFor(1_000)) // no overflow
    }

    @Test
    fun `jittered delay stays within half-ceiling and ceiling`() {
        val policy = BackoffPolicy(baseMs = 500, maxMs = 30_000, random = Random(42))
        repeat(1_000) { attempt ->
            val ceiling = policy.ceilingFor(attempt % 8)
            val delay = policy.delayFor(attempt % 8)
            assertTrue(delay in ceiling / 2..ceiling)
        }
    }
}

class ImageSizingTest {
    @Test
    fun `sample size is largest power of two keeping long edge above target`() {
        assertEquals(2, ImageSizing.inSampleSize(4000, 3000, 1600)) // 2000 >= 1600, 1000 < 1600
        assertEquals(1, ImageSizing.inSampleSize(1600, 1200, 1600))
        assertEquals(4, ImageSizing.inSampleSize(3000, 8000, 1600))
    }

    @Test
    fun `scaled size preserves aspect ratio and never upscales`() {
        assertEquals(1600 to 1200, ImageSizing.scaledSize(4000, 3000, 1600))
        assertEquals(900 to 1600, ImageSizing.scaledSize(1800, 3200, 1600))
        assertEquals(800 to 600, ImageSizing.scaledSize(800, 600, 1600))
    }
}
