package com.systemwebstudio.logic.workflow

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What every [WorkflowQueue] must do, whatever carries the messages. Run against the in-memory queue in the unit tests and against a real RabbitMQ
 * (quorum queue + dead-letter exchange) in the integration test, so the engine's assumptions are checked on the real thing:
 *  - a delivered message that is not acknowledged comes back (a consumer that died before its ack);
 *  - `nack(requeue = false)` goes to the dead-letter queue and never comes back on the main queue;
 *  - a message that keeps coming back without an ack is dead-lettered after the delivery limit (no infinite loop);
 *  - an acknowledged message is gone for good.
 */
abstract class WorkflowQueueContract {
    /** A fresh, empty queue (its own topology) for one test. */
    protected abstract fun newQueue(): WorkflowQueue
    /** The consumer holding this queue's unacknowledged messages dies: the broker gives them back. */
    protected abstract fun killConsumer(queue: WorkflowQueue)
    /** Lets asynchronous brokers (dead-lettering) settle; the in-memory queue needs nothing. */
    protected open fun <T> eventually(block: () -> T?): T? = block()
    protected open fun cleanup() {}

    @AfterEach fun after() = cleanup()

    private fun job(step: String = "a") = WorkflowJob.forStep(UUID.randomUUID(), UUID.randomUUID(), step)

    @Test fun `an empty queue answers null`() {
        val q = newQueue()
        assertNull(q.poll())
        assertNull(q.pollDeadLetter())
    }

    @Test fun `a published job is delivered once with delivery count 1 and its body intact`() {
        val q = newQueue()
        val j = job("step_1")
        q.publish(j)
        val lease = eventually { q.poll() }
        assertNotNull(lease)
        assertEquals(j.encode(), lease!!.body)
        assertEquals(j, WorkflowJob.decode(lease.body))
        assertEquals(1, lease.deliveryCount)
        assertNull(q.poll(), "the message is in flight, nobody else gets it")
        q.ack(lease)
        assertNull(eventually { q.poll() }, "an acknowledged message is gone for good")
        assertNull(q.pollDeadLetter())
    }

    @Test fun `a message whose consumer died before the ack is delivered again with a higher count`() {
        val q = newQueue()
        q.publish(job())
        val first = eventually { q.poll() }!!
        killConsumer(q)
        val second = eventually { q.poll() }
        assertNotNull(second, "redelivered after the consumer died")
        assertEquals(first.body, second!!.body)
        assertTrue(second.deliveryCount >= 2, "the redelivery is visible: ${second.deliveryCount}")
        q.ack(second)
        assertNull(eventually { q.poll() })
    }

    @Test fun `nack without requeue dead-letters exactly that message and it never returns to the main queue`() {
        val q = newQueue()
        val poison = job("p"); val healthy = job("h")
        q.publish(poison); q.publish(healthy)
        val a = eventually { q.poll() }!!
        val b = eventually { q.poll() }!!
        val (bad, good) = if (a.body == poison.encode()) a to b else b to a
        q.nack(bad, requeue = false)
        q.ack(good)
        val dead = eventually { q.pollDeadLetter() }
        assertNotNull(dead)
        assertEquals(poison.encode(), dead!!.body)
        q.ackDeadLetter(dead)
        assertNull(eventually { q.poll() })
        assertNull(q.pollDeadLetter(), "an acknowledged dead letter is gone")
    }

    @Test fun `a message that keeps coming back unacknowledged is dead-lettered by the delivery limit and does not loop`() {
        val q = newQueue()
        val j = job()
        q.publish(j)
        var deliveries = 0
        while (deliveries < 50) {
            eventually { q.poll() } ?: break
            deliveries++
            killConsumer(q)                      // never acked
        }
        assertTrue(deliveries in 2..20, "delivered a bounded number of times: $deliveries")
        val dead = eventually { q.pollDeadLetter() }
        assertNotNull(dead, "it ends in the dead-letter queue")
        assertEquals(j.encode(), dead!!.body)
        assertNull(eventually { q.poll() })
    }
}
