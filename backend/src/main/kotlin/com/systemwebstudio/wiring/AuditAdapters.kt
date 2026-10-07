package com.systemwebstudio.wiring

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.logic.action.ActionAuditEntry
import com.systemwebstudio.logic.action.ActionAuditPort
import com.systemwebstudio.logic.action.LogicAuditPort
import com.systemwebstudio.logic.action.LogicAuditRecord

/**
 * C0 · C4's two audit ports -> `AuditService` (the existing `audit_events` table; no migration). C4 treats the STARTED record of an action as fail-closed
 * (an exception here becomes `AUDIT_UNAVAILABLE` and the action does not run), so these adapters let the exception propagate. What is written is ids, phases,
 * codes and durations — never an input value, parameter, token or message of a downstream system.
 */
class ActionAuditAdapter(private val audit: AuditService) : ActionAuditPort {
    override fun record(entry: ActionAuditEntry) {
        val details = LinkedHashMap<String, Any?>()
        details["phase"] = entry.phase.name
        details["mode"] = entry.mode.name
        details["tenant"] = entry.tenantId.toString()
        details["actionType"] = entry.actionType?.name
        details["runId"] = entry.runId
        details["trigger"] = entry.trigger?.kind?.name
        details["event"] = entry.trigger?.eventName
        details["errorCode"] = entry.errorCode
        details["retryable"] = entry.retryable
        details["durationMillis"] = entry.durationMillis
        details["requestId"] = entry.requestId
        audit.record(
            action = "ACTION_" + entry.phase.name,
            resourceType = "ACTION",
            resourceId = entry.actionId.take(64),
            workspaceId = entry.workspaceId,
            projectId = entry.projectId,
            actorId = entry.actor.userId,
            oldValue = null,
            newValue = details
        )
    }
}

class LogicAuditAdapter(private val audit: AuditService) : LogicAuditPort {
    override fun record(record: LogicAuditRecord) {
        val details = LinkedHashMap<String, Any?>()
        details["mode"] = record.mode.name
        details["tenant"] = record.tenantId.toString()
        details["runId"] = record.runId
        details["errorCode"] = record.errorCode
        details["attributes"] = record.attributes
        audit.record(
            action = (record.domain + "_" + record.event).take(64),
            resourceType = record.resourceType.take(48),
            resourceId = record.resourceId?.take(64),
            workspaceId = null,
            projectId = record.appId,
            actorId = record.actor?.userId,
            oldValue = null,
            newValue = details
        )
    }
}
