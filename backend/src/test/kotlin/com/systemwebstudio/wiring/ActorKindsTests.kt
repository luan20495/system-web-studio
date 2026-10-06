package com.systemwebstudio.wiring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.systemwebstudio.logic.action.ActorKind as LogicActorKind
import com.systemwebstudio.tenancy.ActorKind as TenancyActorKind

/** D-C0-14: the two enums are converted by name at the adapter boundary, in both directions, for every kind. */
class ActorKindsTests {
    @Test
    fun `every logic kind converts to the tenancy kind of the same name and back`() {
        for (kind in LogicActorKind.entries) {
            val t = ActorKinds.toTenancy(kind)
            assertThat(t.name).isEqualTo(kind.name)
            assertThat(ActorKinds.toLogic(t)).isEqualTo(kind)
        }
    }

    @Test
    fun `the two enums have exactly the same names`() {
        assertThat(LogicActorKind.entries.map { it.name }).containsExactlyElementsOf(TenancyActorKind.entries.map { it.name })
    }

    @Test
    fun `USER is never produced for another kind`() {
        assertThat(ActorKinds.toTenancy(LogicActorKind.SYSTEM)).isEqualTo(TenancyActorKind.SYSTEM)
        assertThat(ActorKinds.toTenancy(LogicActorKind.APP_TOKEN)).isEqualTo(TenancyActorKind.APP_TOKEN)
        assertThat(ActorKinds.toTenancy(LogicActorKind.SERVICE)).isEqualTo(TenancyActorKind.SERVICE)
        assertThat(ActorKinds.toLogic(TenancyActorKind.SERVICE)).isEqualTo(LogicActorKind.SERVICE)
    }
}
