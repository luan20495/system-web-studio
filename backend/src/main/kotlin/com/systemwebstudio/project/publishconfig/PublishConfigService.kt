package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.app.definition.PublishMode
import com.systemwebstudio.app.definition.PublishVisibility
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Where publish configurations live (table `publish_configs`, needs the migration requested in BOARD.md). In-memory in tests. */
interface PublishConfigRepository {
    fun find(projectId: UUID): PublishConfig?
    /** inserts when [expectedRevision] is null and no row exists; otherwise compare-and-swap on revision. Returns false when the revision no longer matches. */
    fun save(config: PublishConfig, linkTokenHash: String?, expectedRevision: Long?): Boolean
    fun linkTokenHash(projectId: UUID): String?
}

/** One line in the audit trail; carries no token and no secret. */
fun interface PublishConfigAudit { fun record(action: String, workspaceId: UUID, projectId: UUID, actorId: UUID, details: Map<String, Any?>) }

/** The result of setting a configuration: the stored policy and, only when a PRIVATE_LINK token was just created, the token (shown once). */
data class PublishConfigResult(val config: PublishConfig, val linkToken: String? = null)

/**
 * Sets and reads the publication policy. The caller (controller) has already established WHO may do this (PROJECT_PUBLISH on the project);
 * this class only enforces WHAT is allowed and keeps the history honest. It has no dependency on membership/sharing and none on deployments:
 * saving a policy never starts, stops, or changes a deployment.
 */
class PublishConfigService(
    private val repo: PublishConfigRepository,
    private val audit: PublishConfigAudit,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Instant = { Instant.now() }
) {
    fun get(projectId: UUID): PublishConfig? = repo.find(projectId)

    fun set(workspaceId: UUID, projectId: UUID, actorId: UUID, update: PublishConfigUpdate, project: ProjectFacts, limits: PublishLimits): PublishConfigResult {
        val issues = PublishConfigPolicy.validate(update, project, limits)
        if (issues.isNotEmpty()) throw PublishConfigException(issues)
        val existing = repo.find(projectId)
        if (existing == null && update.expectedRevision != null) throw PublishConfigConflict(null)
        if (existing != null && update.expectedRevision != existing.revision) throw PublishConfigConflict(existing.revision)

        // a PRIVATE_LINK needs a token: keep the current one when the visibility stays PRIVATE_LINK, otherwise create one (shown once)
        val keepLink = update.visibility == PublishVisibility.PRIVATE_LINK && existing?.linkTokenSet == true
        val newToken = if (update.visibility == PublishVisibility.PRIVATE_LINK && !keepLink) newToken() else null
        val hash = when { newToken != null -> sha256(newToken); keepLink -> repo.linkTokenHash(projectId); else -> null }

        val config = PublishConfig(
            projectId, workspaceId, update.mode, update.visibility, update.requiresAuth, update.cacheSeconds,
            publicDataApproved = update.visibility == PublishVisibility.PUBLIC && update.acknowledgePublicData,
            linkTokenSet = hash != null, revision = (existing?.revision ?: 0) + 1, updatedBy = actorId, updatedAt = now()
        )
        if (!repo.save(config, hash, existing?.revision)) throw PublishConfigConflict(repo.find(projectId)?.revision)
        audit.record("UPDATE_PUBLISH_CONFIG", workspaceId, projectId, actorId, mapOf(
            "mode" to config.mode.name, "visibility" to config.visibility.name, "requiresAuth" to config.requiresAuth, "cacheSeconds" to config.cacheSeconds,
            "publicDataApproved" to config.publicDataApproved, "linkTokenCreated" to (newToken != null),
            "previousVisibility" to existing?.visibility?.name, "revision" to config.revision))
        return PublishConfigResult(config, newToken)
    }

    /**
     * Turns the DRAFT of the current document into a policy request. Nothing is applied by this: the caller passes the result to [set], which
     * runs every rule and writes an audit line, so a draft that arrived through an edit, an AI proposal or a restored version can never
     * change how the app is published by itself. Returns null when the document has no draft.
     */
    fun fromDraft(draft: com.systemwebstudio.app.definition.PublishConfigDef?, current: PublishConfig?, acknowledgePublicData: Boolean = false): PublishConfigUpdate? =
        draft?.let { PublishConfigUpdate(it.mode, it.visibility, it.requiresAuth, it.cacheSeconds, acknowledgePublicData, current?.revision) }

    /** replaces the PRIVATE_LINK token; the old link stops working at once. Returns the new token (shown once). */
    fun rotateLink(workspaceId: UUID, projectId: UUID, actorId: UUID): PublishConfigResult {
        val existing = repo.find(projectId) ?: throw PublishConfigException(listOf(PolicyIssue("visibility", "NO_CONFIG", "there is no publish configuration")))
        if (existing.visibility != PublishVisibility.PRIVATE_LINK) throw PublishConfigException(listOf(PolicyIssue("visibility", "NOT_PRIVATE_LINK", "only a PRIVATE_LINK app has a link")))
        val token = newToken()
        val updated = existing.copy(linkTokenSet = true, revision = existing.revision + 1, updatedBy = actorId, updatedAt = now())
        if (!repo.save(updated, sha256(token), existing.revision)) throw PublishConfigConflict(repo.find(projectId)?.revision)
        audit.record("ROTATE_PUBLISH_LINK", workspaceId, projectId, actorId, mapOf("revision" to updated.revision))
        return PublishConfigResult(updated, token)
    }

    /** constant-time check used by the serving plane for a PRIVATE_LINK request; false when there is no link or the token is wrong */
    fun linkMatches(projectId: UUID, token: String): Boolean {
        val stored = repo.linkTokenHash(projectId) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), sha256(token).toByteArray(Charsets.UTF_8))
    }

    /**
     * What the publish pipeline should use for a NEW deployment. With a stored policy the policy decides (and an explicit different request
     * is refused by the caller); without one the request decides exactly as before this feature existed, so projects published earlier,
     * API clients and the current UI keep working unchanged.
     */
    fun decisionFor(projectId: UUID, legacyRequestedVisibility: String?): PublishDecision {
        val stored = repo.find(projectId)
        if (stored != null) return PublishDecision(stored.mode, stored.visibility, stored.requiresAuth, stored.cacheSeconds, fromConfig = true)
        val visibility = if (legacyRequestedVisibility == "PUBLIC") PublishVisibility.PUBLIC else PublishVisibility.PRIVATE
        return PublishDecision(PublishMode.STATIC, visibility, requiresAuth = false, cacheSeconds = null, fromConfig = false)
    }

    private fun newToken(): String {
        val bytes = ByteArray(32); random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
