package com.systemwebstudio.logic.notification

import com.systemwebstudio.logic.action.ActionErrorCodes
import com.systemwebstudio.logic.action.AuditDomains
import com.systemwebstudio.logic.action.FakePrincipals
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.NotifyChannel
import com.systemwebstudio.logic.action.NotifyRequest
import com.systemwebstudio.logic.action.PortOutcome
import com.systemwebstudio.logic.action.PrincipalSpec
import com.systemwebstudio.logic.action.RecordingLogicAudit
import com.systemwebstudio.logic.action.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class NotificationServiceTests {
    private class FakeCatalog(
        val templates: Map<String, NotificationTemplate> = mapOf(
            "welcome" to NotificationTemplate("welcome", setOf(NotifyChannel.IN_APP, NotifyChannel.EMAIL), setOf("name")),
            "hook" to NotificationTemplate("hook", setOf(NotifyChannel.WEBHOOK))
        ),
        val endpoints: Set<String> = setOf("crm-hook")
    ) : NotificationCatalog {
        override fun template(tenantId: UUID, templateRef: String) = if (tenantId == Fx.tenantA) templates[templateRef] else null
        override fun endpointExists(tenantId: UUID, endpointRef: String) = tenantId == Fx.tenantA && endpointRef in endpoints
    }

    private class RecordingSender(override val channel: NotifyChannel, var next: (Delivery) -> SendOutcome = { SendOutcome.Sent("p1") }) : ChannelSender {
        val sent = CopyOnWriteArrayList<Delivery>()
        override fun send(delivery: Delivery): SendOutcome { sent += delivery; return next(delivery) }
    }

    private val clock = TestClock()
    private val principals = FakePrincipals(groups = mapOf("finance" to setOf(Fx.user, Fx.user2)))
    private val inApp = InMemoryInAppChannel()
    private val email = RecordingSender(NotifyChannel.EMAIL)
    private val hook = RecordingSender(NotifyChannel.WEBHOOK)
    private val store = InMemoryDeliveryStore()
    private val audit = RecordingLogicAudit()
    private val svc = NotificationService(Fx.json, FakeCatalog(), listOf(inApp, email, hook), principals, store, audit, clock)

    private fun req(channel: NotifyChannel = NotifyChannel.IN_APP, template: String = "welcome", to: List<PrincipalSpec> = listOf(PrincipalSpec.User(Fx.user2, null)),
                    params: Map<String, tools.jackson.databind.JsonNode> = mapOf("name" to Fx.str("Ada")), key: String? = "k1", endpoint: String? = null) =
        NotifyRequest(channel, template, to, params, key, endpoint)

    private fun failure(o: PortOutcome) = assertInstanceOf(PortOutcome.Failure::class.java, o)

    @Test fun `an in-app notification lands in the recipient's inbox`() {
        val o = svc.send(Fx.ctx(), req())
        assertEquals(1, assertInstanceOf(PortOutcome.Success::class.java, o).output.get("sent").asInt())
        assertEquals("welcome", inApp.inbox(Fx.tenantA, Fx.user2).single().templateRef)
        assertTrue(inApp.inbox(Fx.tenantA, Fx.user).isEmpty())
    }

    @Test fun `no recipients means the acting user`() {
        svc.send(Fx.ctx(), req(to = emptyList()))
        assertEquals(1, inApp.inbox(Fx.tenantA, Fx.user).size)
    }

    @Test fun `groups expand to their members and duplicates are collapsed`() {
        svc.send(Fx.ctx(), req(to = listOf(PrincipalSpec.Group("finance"), PrincipalSpec.User(Fx.user2, null))))
        assertEquals(1, inApp.inbox(Fx.tenantA, Fx.user).size)
        assertEquals(1, inApp.inbox(Fx.tenantA, Fx.user2).size)
    }

    @Test fun `a recipient that cannot be addressed fails the whole request before anything is sent`() {
        val o = failure(svc.send(Fx.ctx(), req(to = listOf(PrincipalSpec.User(Fx.user2, null), PrincipalSpec.User(UUID.randomUUID(), Fx.tenantB)))))
        assertEquals(ActionErrorCodes.FORBIDDEN, o.code)
        assertTrue(inApp.inbox(Fx.tenantA, Fx.user2).isEmpty())
    }

    @Test fun `unknown templates, wrong channels and missing params are refused without leaking`() {
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(template = "nope"))).code)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(channel = NotifyChannel.IN_APP, template = "hook"))).code)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(params = emptyMap()))).code)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(tenant = Fx.tenantB), req())).code)   // template of another tenant
    }

    @Test fun `a channel without a sender is NOT_IMPLEMENTED and never pretends`() {
        val o = failure(svc.send(Fx.ctx(), req(channel = NotifyChannel.SMS)))
        assertEquals(ActionErrorCodes.NOT_IMPLEMENTED, o.code); assertFalse(o.retryable)
    }

    @Test fun `a webhook goes to a registered endpoint only and never to a url`() {
        assertInstanceOf(PortOutcome.Success::class.java, svc.send(Fx.ctx(), req(NotifyChannel.WEBHOOK, "hook", emptyList(), emptyMap(), endpoint = "crm-hook")))
        assertEquals("crm-hook", hook.sent.single().endpointRef)
        assertEquals(null, hook.sent.single().recipientUserId)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(NotifyChannel.WEBHOOK, "hook", emptyList(), emptyMap(), endpoint = "https://evil.example/x"))).code)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(NotifyChannel.WEBHOOK, "hook", emptyList(), emptyMap(), endpoint = null))).code)
        assertEquals(ActionErrorCodes.INVALID_INPUT, failure(svc.send(Fx.ctx(), req(NotifyChannel.EMAIL, endpoint = "crm-hook"))).code)
    }

    @Test fun `the number of recipients is limited`() {
        val big = NotificationService(Fx.json, FakeCatalog(), listOf(inApp), FakePrincipals(groups = mapOf("g" to (1..5).map { UUID.randomUUID() }.toSet())), store, null, clock, maxRecipients = 3)
        assertEquals(ActionErrorCodes.LIMIT_EXCEEDED, failure(big.send(Fx.ctx(), req(to = listOf(PrincipalSpec.Group("g"))))).code)
    }

    @Test fun `a retry with the same key does not message a recipient twice`() {
        svc.send(Fx.ctx(), req(key = "same")); svc.send(Fx.ctx(), req(key = "same"))
        assertEquals(1, inApp.inbox(Fx.tenantA, Fx.user2).size)
        svc.send(Fx.ctx(), req(key = "other"))
        assertEquals(2, inApp.inbox(Fx.tenantA, Fx.user2).size)
    }

    @Test fun `a partial failure is retryable and the retry resends only the failed recipients`() {
        var failUser2 = true
        val flaky = RecordingSender(NotifyChannel.EMAIL) { d -> if (d.recipientUserId == Fx.user2 && failUser2) SendOutcome.Failed("SMTP_DOWN", true) else SendOutcome.Sent() }
        val s = NotificationService(Fx.json, FakeCatalog(), listOf(flaky), principals, store, audit, clock)
        val r = req(NotifyChannel.EMAIL, to = listOf(PrincipalSpec.Group("finance")), key = "mail-1")
        val first = failure(s.send(Fx.ctx(), r))
        assertTrue(first.retryable)
        assertEquals(2, flaky.sent.size)
        failUser2 = false
        assertInstanceOf(PortOutcome.Success::class.java, s.send(Fx.ctx(), r))
        assertEquals(3, flaky.sent.size)                                  // only user2 again
        assertEquals(Fx.user2, flaky.sent.last().recipientUserId)
    }

    @Test fun `a permanent failure is not retryable and a sender that throws is a retryable failure without the exception text`() {
        val bad = RecordingSender(NotifyChannel.EMAIL) { SendOutcome.Failed("MAILBOX_GONE", false, "secret detail") }
        val f = failure(NotificationService(Fx.json, FakeCatalog(), listOf(bad), principals, InMemoryDeliveryStore(), null, clock).send(Fx.ctx(), req(NotifyChannel.EMAIL)))
        assertFalse(f.retryable)
        assertFalse((f.message ?: "").contains("secret"))
        val boom = RecordingSender(NotifyChannel.EMAIL) { throw IllegalStateException("password=hunter2") }
        val g = failure(NotificationService(Fx.json, FakeCatalog(), listOf(boom), principals, InMemoryDeliveryStore(), null, clock).send(Fx.ctx(), req(NotifyChannel.EMAIL)))
        assertTrue(g.retryable); assertFalse((g.message ?: "").contains("hunter2"))
    }

    @Test fun `a claim abandoned by a crashed node is reclaimed after the lease`() {
        val key = "EMAIL:welcome:k:${Fx.user2}"
        assertTrue(store.tryClaim(Fx.tenantA, key, clock.instant()))
        assertFalse(store.tryClaim(Fx.tenantA, key, clock.instant()))                 // someone is sending
        clock.advance(Duration.ofMinutes(6))
        assertTrue(store.tryClaim(Fx.tenantA, key, clock.instant()))                  // the owner is presumed dead
        store.finish(Fx.tenantA, key, DeliveryStatus.SENT, clock.instant())
        clock.advance(Duration.ofDays(1))
        assertFalse(store.tryClaim(Fx.tenantA, key, clock.instant()))                 // SENT is final
    }

    @Test fun `delivery records are tenant scoped`() {
        assertTrue(store.tryClaim(Fx.tenantA, "k", clock.instant()))
        assertTrue(store.tryClaim(Fx.tenantB, "k", clock.instant()))
    }

    @Test fun `sending is audited but an audit failure does not undo the send`() {
        svc.send(Fx.ctx(), req())
        assertEquals(listOf("SENT"), audit.events(AuditDomains.NOTIFICATION))
        audit.failAll = true
        assertInstanceOf(PortOutcome.Success::class.java, svc.send(Fx.ctx(), req(key = "k2")))
    }

    @Test fun `params are not written to the audit trail`() {
        svc.send(Fx.ctx(), req(params = mapOf("name" to Fx.str("TOP-SECRET-VALUE"))))
        assertFalse(audit.records.joinToString { it.toString() }.contains("TOP-SECRET-VALUE"))
    }
}
