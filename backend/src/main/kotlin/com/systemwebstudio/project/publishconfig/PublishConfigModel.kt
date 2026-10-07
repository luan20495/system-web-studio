package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.app.definition.PublishMode
import com.systemwebstudio.app.definition.PublishVisibility
import java.time.Instant
import java.util.UUID

/*
 * T7 — publish configuration (D-C2-06, D-C2-08).
 *
 * SHARE != PUBLISH. Share = who may open or edit the project in the studio (project / workspace members, owned by the sharing module).
 * Publish = serving the app to the outside (deployments). Nothing in this package reads or writes membership, and nothing in the sharing
 * code reads or writes this.
 *
 * Two places could be mistaken for "the publish state", and neither is:
 *   - AppDefinition.publishConfig is a DRAFT inside the versioned document: an intention that can be saved, restored and diffed without
 *     any effect on what is live;
 *   - this PublishConfig (table publish_configs, one row per project) is the approved POLICY used when the next deployment is created;
 *   - what is actually served is the deployment (and its immutable visibility) the site points at. Changing the policy does not touch a live
 *     deployment, and a rollback only moves the site pointer to an older deployment: it neither reads nor rewrites this policy.
 */

/** The authoritative publication policy of one project. [linkToken] is never part of it: only a hash is stored, the token is shown once. */
data class PublishConfig(
    val projectId: UUID, val workspaceId: UUID,
    val mode: PublishMode, val visibility: PublishVisibility,
    val requiresAuth: Boolean = false, val cacheSeconds: Int? = null,
    /** an authorised publisher confirmed that data bound to this app may appear on a PUBLIC site (see [PublishConfigPolicy]) */
    val publicDataApproved: Boolean = false,
    /** true when a PRIVATE_LINK token exists (its hash is stored, never the token) */
    val linkTokenSet: Boolean = false,
    val revision: Long = 1, val updatedBy: UUID? = null, val updatedAt: Instant? = null
)

/** What a publisher asks for. [expectedRevision] is null only when no configuration exists yet. */
data class PublishConfigUpdate(
    val mode: PublishMode, val visibility: PublishVisibility,
    val requiresAuth: Boolean = false, val cacheSeconds: Int? = null,
    val acknowledgePublicData: Boolean = false, val expectedRevision: Long? = null
)

/** What the policy needs to know about the project (read by the caller; the policy itself touches nothing). */
data class ProjectFacts(
    /** projects.app_kind: WEBSITE_STATIC | SOURCE_WEB_APP | DASHBOARD | INTERNAL_TOOL | WORKFLOW | SERVER_APP */
    val appKind: String,
    /** the current document has at least one data binding (data would be rendered into the published app) */
    val hasDataBindings: Boolean
)

/** Administrator switches that limit publishing (settings `publish.public-enabled`, `source-apps.public-publish-enabled`). */
data class PublishLimits(val publicEnabled: Boolean, val sourceAppPublicEnabled: Boolean)

data class PolicyIssue(val field: String, val code: String, val message: String)

/** What the publish pipeline asks for a new deployment. */
data class PublishDecision(val mode: PublishMode, val visibility: PublishVisibility, val requiresAuth: Boolean, val cacheSeconds: Int?, val fromConfig: Boolean)

class PublishConfigException(val issues: List<PolicyIssue>) : RuntimeException(issues.joinToString("; ") { it.field + ": " + it.message })

class PublishConfigConflict(val currentRevision: Long?) : RuntimeException("publish configuration changed elsewhere")
