package com.systemwebstudio.wiring

import com.systemwebstudio.logic.action.ActorKind as LogicActorKind
import com.systemwebstudio.tenancy.ActorKind as TenancyActorKind

/**
 * C0 · D-C0-14: C4 keeps its own `logic.action.ActorKind` (the `logic.*` packages may not import `tenancy`). Every crossing of the boundary goes through
 * this object, by NAME, and an unknown name is an error the caller turns into a denial — it is never mapped to USER.
 * `ActionContractV2Tests` pins both enums to the same four names, so a drift fails a test before it can reach production.
 */
object ActorKinds {
    fun toTenancy(kind: LogicActorKind): TenancyActorKind =
        TenancyActorKind.entries.firstOrNull { it.name == kind.name } ?: throw IllegalArgumentException("actor kind is not known to tenancy")

    fun toLogic(kind: TenancyActorKind): LogicActorKind =
        LogicActorKind.entries.firstOrNull { it.name == kind.name } ?: throw IllegalArgumentException("actor kind is not known to the logic runtime")
}
