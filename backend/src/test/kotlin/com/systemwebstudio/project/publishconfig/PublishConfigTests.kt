package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.app.definition.PublishConfigDef
import com.systemwebstudio.app.definition.PublishMode
import com.systemwebstudio.app.definition.PublishVisibility
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Pure unit tests of T7: the policy, the service over an in-memory repository, the SHARE / PUBLISH separation and the legacy fallback. */
class PublishConfigTests {
    private class MemoryRepo : PublishConfigRepository {
        val rows = HashMap<UUID, PublishConfig>(); val hashes = HashMap<UUID, String?>()
        override fun find(projectId: UUID) = rows[projectId]
        override fun save(config: PublishConfig, linkTokenHash: String?, expectedRevision: Long?): Boolean {
            val current = rows[config.projectId]
            if ((current?.revision) != expectedRevision) return false
            rows[config.projectId] = config; hashes[config.projectId] = linkTokenHash; return true
        }
        override fun linkTokenHash(projectId: UUID) = hashes[projectId]
    }

    private class Line(val action: String, val details: Map<String, Any?>)

    private val ws = UUID.randomUUID(); private val project = UUID.randomUUID(); private val actor = UUID.randomUUID()
    private val repo = MemoryRepo()
    private val lines = ArrayList<Line>()
    private val service = PublishConfigService(repo, { action, _, _, _, details -> lines += Line(action, details) }, java.security.SecureRandom(), { Instant.parse("2026-10-05T08:00:00Z") })
    private val website = ProjectFacts("WEBSITE_STATIC", hasDataBindings = false)
    private val open = PublishLimits(publicEnabled = true, sourceAppPublicEnabled = true)
    private fun update(mode: PublishMode = PublishMode.STATIC, visibility: PublishVisibility = PublishVisibility.PRIVATE, rev: Long? = null, auth: Boolean = false, ack: Boolean = false, cache: Int? = null) =
        PublishConfigUpdate(mode, visibility, auth, cache, ack, rev)
    private fun issues(u: PublishConfigUpdate, p: ProjectFacts = website, l: PublishLimits = open) = PublishConfigPolicy.validate(u, p, l).map { it.field + ":" + it.code }

    // ---- policy ----

    @Test
    fun `mode must fit the kind of project`() {
        assertThat(issues(update(PublishMode.STATIC))).isEmpty()
        assertThat(issues(update(PublishMode.DYNAMIC))).isEmpty()
        assertThat(issues(update(PublishMode.SERVER_APP))).contains("mode:MODE_NOT_ALLOWED")
        val server = ProjectFacts("SERVER_APP", false)
        assertThat(issues(update(PublishMode.SERVER_APP), server)).isEmpty()
        assertThat(issues(update(PublishMode.STATIC), server)).contains("mode:MODE_NOT_ALLOWED")
        val source = ProjectFacts("SOURCE_WEB_APP", false)
        assertThat(issues(update(PublishMode.STATIC), source)).isEmpty()
        assertThat(issues(update(PublishMode.DYNAMIC), source)).contains("mode:MODE_NOT_ALLOWED")
        for (kind in listOf("DASHBOARD", "INTERNAL_TOOL", "WORKFLOW")) assertThat(issues(update(PublishMode.DYNAMIC), ProjectFacts(kind, true))).isEmpty()
    }

    @Test
    fun `public visibility is subject to the administrator switches and cannot require sign-in`() {
        val off = PublishLimits(publicEnabled = false, sourceAppPublicEnabled = true)
        assertThat(issues(update(visibility = PublishVisibility.PUBLIC), l = off)).contains("visibility:PUBLIC_PUBLISH_DISABLED")
        assertThat(issues(update(visibility = PublishVisibility.PRIVATE), l = off)).isEmpty()
        assertThat(issues(update(visibility = PublishVisibility.TENANT), l = off)).isEmpty()
        val noCode = PublishLimits(publicEnabled = true, sourceAppPublicEnabled = false)
        assertThat(issues(update(visibility = PublishVisibility.PUBLIC), ProjectFacts("SOURCE_WEB_APP", false), noCode)).contains("visibility:CODE_APP_PUBLIC_DISABLED")
        assertThat(issues(update(visibility = PublishVisibility.PUBLIC, auth = true))).contains("requiresAuth:AUTH_CONTRADICTS_PUBLIC")
        assertThat(issues(update(visibility = PublishVisibility.PUBLIC))).isEmpty()
    }

    @Test
    fun `data bound to an app does not become public without an explicit confirmation`() {
        val bound = ProjectFacts("DASHBOARD", hasDataBindings = true)
        assertThat(issues(update(PublishMode.DYNAMIC, PublishVisibility.PUBLIC), bound)).contains("acknowledgePublicData:PUBLIC_DATA_NOT_APPROVED")
        assertThat(issues(update(PublishMode.STATIC, PublishVisibility.PUBLIC), bound)).contains("acknowledgePublicData:PUBLIC_DATA_NOT_APPROVED")     // a static snapshot is public data too
        assertThat(issues(update(PublishMode.DYNAMIC, PublishVisibility.PUBLIC, ack = true), bound)).isEmpty()
        for (v in listOf(PublishVisibility.PRIVATE, PublishVisibility.TENANT, PublishVisibility.PRIVATE_LINK)) assertThat(issues(update(PublishMode.DYNAMIC, v), bound)).isEmpty()
    }

    @Test
    fun `cache seconds are bounded`() {
        assertThat(issues(update(cache = 0))).isEmpty()
        assertThat(issues(update(cache = 86_400))).isEmpty()
        assertThat(issues(update(cache = -1))).contains("cacheSeconds:INVALID")
        assertThat(issues(update(cache = 86_401))).contains("cacheSeconds:INVALID")
    }

    // ---- service ----

    @Test
    fun `the first configuration is revision 1 and later changes need the current revision`() {
        val first = service.set(ws, project, actor, update(visibility = PublishVisibility.TENANT), website, open)
        assertThat(first.config.revision).isEqualTo(1L)
        assertThat(first.linkToken == null).isTrue()
        assertThat(repo.find(project)!!.visibility).isEqualTo(PublishVisibility.TENANT)

        val second = service.set(ws, project, actor, update(visibility = PublishVisibility.PRIVATE, rev = 1), website, open)
        assertThat(second.config.revision).isEqualTo(2L)

        val stale = assertThrows(PublishConfigConflict::class.java) { service.set(ws, project, actor, update(visibility = PublishVisibility.PUBLIC, rev = 1), website, open) }
        assertThat(stale.currentRevision).isEqualTo(2L)
        assertThat(repo.find(project)!!.visibility).isEqualTo(PublishVisibility.PRIVATE)                  // the stale request changed nothing
        val noRevision = assertThrows(PublishConfigConflict::class.java) { service.set(ws, project, actor, update(), website, open) }
        assertThat(noRevision.currentRevision).isEqualTo(2L)
        val unexpected = assertThrows(PublishConfigConflict::class.java) { service.set(ws, UUID.randomUUID(), actor, update(rev = 5), website, open) }
        assertThat(unexpected.currentRevision == null).isTrue()
    }

    @Test
    fun `a rejected configuration stores nothing and writes no audit line`() {
        val e = assertThrows(PublishConfigException::class.java) { service.set(ws, project, actor, update(PublishMode.SERVER_APP), website, open) }
        assertThat(e.issues.map { it.code }).contains("MODE_NOT_ALLOWED")
        assertThat(repo.find(project) == null).isTrue()
        assertThat(lines).isEmpty()
    }

    @Test
    fun `a private link token is created once and only its hash is stored`() {
        val r = service.set(ws, project, actor, update(visibility = PublishVisibility.PRIVATE_LINK), website, open)
        val token = r.linkToken!!
        assertThat(token.length >= 43).isTrue()                                      // 256 bits, base64url
        assertThat(r.config.linkTokenSet).isTrue()
        val hash = repo.hashes[project]!!
        assertThat(hash == token).isFalse()
        assertThat(hash.length).isEqualTo(64)
        assertThat(service.linkMatches(project, token)).isTrue()
        assertThat(service.linkMatches(project, token + "x")).isFalse()
        assertThat(service.linkMatches(project, "")).isFalse()
        assertThat(service.linkMatches(UUID.randomUUID(), token)).isFalse()

        // changing something else while it stays a private link keeps the same link
        val again = service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.PRIVATE_LINK, rev = 1), website, open)
        assertThat(again.linkToken == null).isTrue()
        assertThat(service.linkMatches(project, token)).isTrue()

        // rotating replaces it at once
        val rotated = service.rotateLink(ws, project, actor)
        assertThat(rotated.linkToken == token).isFalse()
        assertThat(service.linkMatches(project, token)).isFalse()
        assertThat(service.linkMatches(project, rotated.linkToken!!)).isTrue()

        // leaving PRIVATE_LINK drops the token; coming back creates a new one
        val left = service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.PRIVATE, rev = 3), website, open)
        assertThat(left.config.linkTokenSet).isFalse()
        assertThat(service.linkMatches(project, rotated.linkToken!!)).isFalse()
        val back = service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.PRIVATE_LINK, rev = 4), website, open)
        assertThat(back.linkToken == null).isFalse()
        assertThat(back.linkToken == rotated.linkToken).isFalse()
    }

    @Test
    fun `rotating needs an existing private link`() {
        assertThrows(PublishConfigException::class.java) { service.rotateLink(ws, project, actor) }
        service.set(ws, project, actor, update(visibility = PublishVisibility.TENANT), website, open)
        val e = assertThrows(PublishConfigException::class.java) { service.rotateLink(ws, project, actor) }
        assertThat(e.issues.map { it.code }).contains("NOT_PRIVATE_LINK")
    }

    @Test
    fun `audit lines carry the change and never the token`() {
        val r = service.set(ws, project, actor, update(visibility = PublishVisibility.PRIVATE_LINK), website, open)
        service.rotateLink(ws, project, actor)
        assertThat(lines.map { it.action }).containsExactly("UPDATE_PUBLISH_CONFIG", "ROTATE_PUBLISH_LINK")
        assertThat(lines[0].details["linkTokenCreated"]).isEqualTo(true)
        assertThat(lines[0].details["visibility"]).isEqualTo("PRIVATE_LINK")
        for (l in lines) assertThat(l.details.values.none { it is String && it.length >= 43 }).isTrue()
        assertThat(lines[0].details.values.contains(r.linkToken)).isFalse()
        service.set(ws, project, actor, update(visibility = PublishVisibility.TENANT, rev = 2), website, open)
        assertThat(lines[2].details["previousVisibility"]).isEqualTo("PRIVATE_LINK")
    }

    @Test
    fun `public data approval is recorded only for a public app`() {
        val bound = ProjectFacts("DASHBOARD", true)
        val pub = service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.PUBLIC, ack = true), bound, open)
        assertThat(pub.config.publicDataApproved).isTrue()
        val priv = service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.PRIVATE, rev = 1, ack = true), bound, open)
        assertThat(priv.config.publicDataApproved).isFalse()
    }

    // ---- draft versus authoritative ----

    @Test
    fun `the draft in the document is only a request and changes nothing by itself`() {
        val draft = PublishConfigDef(PublishMode.DYNAMIC, PublishVisibility.TENANT, requiresAuth = true, cacheSeconds = 30)
        val request = service.fromDraft(draft, null)!!
        assertThat(request).isEqualTo(PublishConfigUpdate(PublishMode.DYNAMIC, PublishVisibility.TENANT, true, 30, false, null))
        assertThat(repo.find(project) == null).isTrue()                                    // nothing stored, nothing published
        assertThat(lines).isEmpty()
        assertThat(service.fromDraft(null, null) == null).isTrue()
        // it still has to pass every rule: a draft that says PUBLIC for an app with data is refused without the confirmation
        val publicDraft = service.fromDraft(PublishConfigDef(visibility = PublishVisibility.PUBLIC), null)!!
        val e = assertThrows(PublishConfigException::class.java) { service.set(ws, project, actor, publicDraft, ProjectFacts("DASHBOARD", true), open) }
        assertThat(e.issues.map { it.code }).contains("PUBLIC_DATA_NOT_APPROVED")
        // a draft that carries the current revision can be adopted over an existing configuration
        service.set(ws, project, actor, update(), website, open)
        assertThat(service.fromDraft(draft, repo.find(project))!!.expectedRevision).isEqualTo(1L)
    }

    // ---- what the pipeline uses ----

    @Test
    fun `without a stored policy the request decides exactly as before`() {
        assertThat(service.decisionFor(project, "PUBLIC")).isEqualTo(PublishDecision(PublishMode.STATIC, PublishVisibility.PUBLIC, false, null, false))
        assertThat(service.decisionFor(project, "PRIVATE")).isEqualTo(PublishDecision(PublishMode.STATIC, PublishVisibility.PRIVATE, false, null, false))
        assertThat(service.decisionFor(project, null).visibility).isEqualTo(PublishVisibility.PRIVATE)
    }

    @Test
    fun `with a stored policy the policy decides`() {
        service.set(ws, project, actor, update(PublishMode.DYNAMIC, PublishVisibility.TENANT, cache = 60), ProjectFacts("DASHBOARD", false), open)
        assertThat(service.decisionFor(project, "PUBLIC")).isEqualTo(PublishDecision(PublishMode.DYNAMIC, PublishVisibility.TENANT, false, 60, true))
    }

    // ---- share is not publish ----

    @Test
    fun `the publish service has no access to membership sharing or deployments`() {
        val parameterTypes = PublishConfigService::class.java.declaredConstructors.flatMap { it.parameterTypes.map { t -> t.name } }
        assertThat(parameterTypes.none { n -> listOf("Member", "Share", "Sharing", "Deployment", "Access").any { n.contains(it) } }).isTrue()
        val repoMethods = PublishConfigRepository::class.java.declaredMethods.map { it.name }
        assertThat(repoMethods.none { it.lowercase().contains("deploy") || it.lowercase().contains("member") }).isTrue()
    }
}
