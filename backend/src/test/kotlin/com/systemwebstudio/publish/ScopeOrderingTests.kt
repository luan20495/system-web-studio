package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The order of intents (C0, 2026-10-06): lower = stale, higher = may go on, equal = only the same operation resuming. Pure. */
class ScopeOrderingTests {
    private val op = UUID.randomUUID(); private val other = UUID.randomUUID()

    @Test fun `lower is stale whatever the operation`() {
        assertThat(ScopeOrdering.of(4, op, 5, op)).isEqualTo(ScopeOrder.STALE)
        assertThat(ScopeOrdering.of(4, op, 5, other)).isEqualTo(ScopeOrder.STALE)
        assertThat(ScopeOrdering.of(4, op, 5, null)).isEqualTo(ScopeOrder.STALE)
    }
    @Test fun `higher may go on whatever moved the pointer last`() {
        assertThat(ScopeOrdering.of(6, op, 5, other)).isEqualTo(ScopeOrder.NEWER)
        assertThat(ScopeOrdering.of(1, op, 0, null)).isEqualTo(ScopeOrder.NEWER)
    }
    @Test fun `equal resumes only for the same operation`() {
        assertThat(ScopeOrdering.of(5, op, 5, op)).isEqualTo(ScopeOrder.RESUME)
        assertThat(ScopeOrdering.of(5, op, 5, other)).isEqualTo(ScopeOrder.CONFLICT)
        assertThat(ScopeOrdering.of(5, op, 5, null)).isEqualTo(ScopeOrder.CONFLICT)
    }
    @Test fun `a publish names its deployment and number, the others do not`() {
        val scope = ReleaseScope(UUID.randomUUID(), UUID.randomUUID())
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ScopeRequest(scope, ReleaseOperation.PUBLISH, op) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ScopeRequest(scope, ReleaseOperation.ROLLBACK, op, deploymentId = op, seq = 1) }
        ScopeRequest(scope, ReleaseOperation.PUBLISH, op, op, 3); ScopeRequest(scope, ReleaseOperation.UNPUBLISH, op)
    }
}
