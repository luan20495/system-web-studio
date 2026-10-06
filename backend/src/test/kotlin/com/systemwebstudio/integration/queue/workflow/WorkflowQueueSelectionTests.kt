package com.systemwebstudio.integration.queue.workflow

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** H-5: which queue adapter the application starts with. Pure decision logic; the Spring bean and the broker are covered by the integration tests. */
class WorkflowQueueSelectionTests {
    private fun resolve(configured: String?, vararg profiles: String) = WorkflowQueueSelection.resolve(configured, profiles.toList())

    @Test fun `explicit values select the adapter, case and spaces do not matter`() {
        assertEquals(WorkflowQueueMode.AMQP, resolve("amqp"))
        assertEquals(WorkflowQueueMode.AMQP, resolve(" AMQP "))
        assertEquals(WorkflowQueueMode.MEMORY, resolve("memory", "dev"))
        assertEquals(WorkflowQueueMode.MEMORY, resolve("Memory", "test"))
    }

    @Test fun `unset means amqp in production and memory elsewhere`() {
        assertEquals(WorkflowQueueMode.AMQP, resolve(null, "prod"))
        assertEquals(WorkflowQueueMode.AMQP, resolve("", "production"))
        assertEquals(WorkflowQueueMode.AMQP, resolve("  ", "other", "PROD"))
        assertEquals(WorkflowQueueMode.MEMORY, resolve(null))
        assertEquals(WorkflowQueueMode.MEMORY, resolve(null, "dev"))
        assertEquals(WorkflowQueueMode.MEMORY, resolve(null, "test"))
    }

    @Test fun `the in-memory queue is refused in production, however it was asked for`() {
        assertThrows(IllegalStateException::class.java) { resolve("memory", "prod") }
        assertThrows(IllegalStateException::class.java) { resolve("MEMORY", "dev", "production") }
        assertEquals(WorkflowQueueMode.AMQP, resolve("amqp", "prod"))
    }

    @Test fun `a typo is a start-up error, never a silent fallback to memory`() {
        val e = assertThrows(IllegalArgumentException::class.java) { resolve("rabbit") }
        assertTrue(e.message!!.contains("rabbit"))
        assertThrows(IllegalArgumentException::class.java) { resolve("amq", "prod") }
        assertThrows(IllegalArgumentException::class.java) { resolve("true") }
    }

    // ------------------------------------------------------------------------------------------------ the shared connection

    private class FakeConn : Connection {
        @Volatile var open = true; val closed = AtomicInteger()
        override fun createChannel(): Channel = throw UnsupportedOperationException()
        override fun isOpen() = open
        override fun close() { open = false; closed.incrementAndGet() }
    }

    @Test fun `an open connection is reused, a closed one is replaced`() {
        val made = mutableListOf<FakeConn>()
        val cached = CachedBrokerConnection { FakeConn().also { made += it } }
        val first = cached()
        assertSame(first, cached())
        assertEquals(1, made.size)
        made[0].open = false
        val second = cached()
        assertEquals(2, made.size)
        assertSame(made[1], second)
    }

    @Test fun `close closes the current connection once and the next call opens a new one`() {
        val made = mutableListOf<FakeConn>()
        val cached = CachedBrokerConnection { FakeConn().also { made += it } }
        cached()
        cached.close(); cached.close()
        assertEquals(1, made[0].closed.get())
        cached()
        assertEquals(2, made.size)
    }

    @Test fun `a failing close does not escape`() {
        val cached = CachedBrokerConnection { object : Connection {
            override fun createChannel(): Channel = throw UnsupportedOperationException()
            override fun isOpen() = true
            override fun close() { throw java.io.IOException("boom") }
        } }
        cached()
        cached.close()
    }
}
