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
    fun `the two enums have exactly the same names, except PUBLIC_SITE which exists only in tenancy`() {
        // D-C0-35: the anonymous visitor of a published site is a tenancy actor kind ONLY. C4's runtime has no such kind, so a public call can never reach it.
        assertThat(TenancyActorKind.entries.map { it.name }.filter { it != "PUBLIC_SITE" }).containsExactlyElementsOf(LogicActorKind.entries.map { it.name })
        assertThat(TenancyActorKind.entries.map { it.name }.filter { it !in LogicActorKind.entries.map { k -> k.name } }).containsExactly("PUBLIC_SITE")
    }

    @Test
    fun `a PUBLIC_SITE is not convertible for the action and workflow runtime, and no logic kind converts to it`() {
        // the adapters turn this exception into a denial ("actor kind not authorised"); it is never mapped to USER
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { ActorKinds.toLogic(TenancyActorKind.PUBLIC_SITE) }
        for (kind in LogicActorKind.entries) assertThat(ActorKinds.toTenancy(kind)).isNotEqualTo(TenancyActorKind.PUBLIC_SITE)
    }

    @Test
    fun `USER is never produced for another kind`() {
        assertThat(ActorKinds.toTenancy(LogicActorKind.SYSTEM)).isEqualTo(TenancyActorKind.SYSTEM)
        assertThat(ActorKinds.toTenancy(LogicActorKind.APP_TOKEN)).isEqualTo(TenancyActorKind.APP_TOKEN)
        assertThat(ActorKinds.toTenancy(LogicActorKind.SERVICE)).isEqualTo(TenancyActorKind.SERVICE)
        assertThat(ActorKinds.toLogic(TenancyActorKind.SERVICE)).isEqualTo(LogicActorKind.SERVICE)
    }
}
