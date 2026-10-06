package com.systemwebstudio.project.publishconfig

import com.systemwebstudio.app.definition.PublishMode
import com.systemwebstudio.app.definition.PublishVisibility

/**
 * The rules of a publish configuration, as a pure function (no I/O) so they are the same for the API, "adopt the draft" and tests.
 *
 *  - mode must fit what the project is: a SERVER_APP project is served by the server runtime (mode SERVER_APP only); a source web app is
 *    served as built files (STATIC only); every other kind is STATIC or DYNAMIC and never SERVER_APP.
 *  - PUBLIC needs the administrator switch (and the code-app switch for a source app) and cannot require sign-in.
 *  - data on a PUBLIC site: if the app binds data, the publisher must say so explicitly ([PublishConfigUpdate.acknowledgePublicData]);
 *    a private dataset must never reach the open web as a side effect of a visibility change (D-C5-03).
 *  - cacheSeconds is bounded.
 * Existing deployments are never judged by these rules again: they were accepted when they were created.
 */
object PublishConfigPolicy {
    const val MAX_CACHE_SECONDS = 86_400
    private val STATIC_ONLY = setOf("SOURCE_WEB_APP")

    fun validate(update: PublishConfigUpdate, project: ProjectFacts, limits: PublishLimits): List<PolicyIssue> {
        val issues = ArrayList<PolicyIssue>()
        val serverApp = project.appKind == "SERVER_APP"
        when {
            serverApp && update.mode != PublishMode.SERVER_APP ->
                issues += PolicyIssue("mode", "MODE_NOT_ALLOWED", "a server app is served by the server runtime: mode must be SERVER_APP")
            !serverApp && update.mode == PublishMode.SERVER_APP ->
                issues += PolicyIssue("mode", "MODE_NOT_ALLOWED", "SERVER_APP is only for server app projects")
            project.appKind in STATIC_ONLY && update.mode != PublishMode.STATIC ->
                issues += PolicyIssue("mode", "MODE_NOT_ALLOWED", "a source web app is published as built files: mode must be STATIC")
        }
        if (update.visibility == PublishVisibility.PUBLIC) {
            if (!limits.publicEnabled) issues += PolicyIssue("visibility", "PUBLIC_PUBLISH_DISABLED", "public publishing is disabled by the administrator")
            else if (project.appKind == "SOURCE_WEB_APP" && !limits.sourceAppPublicEnabled)
                issues += PolicyIssue("visibility", "CODE_APP_PUBLIC_DISABLED", "public publishing of code apps is disabled by the administrator")
            if (update.requiresAuth) issues += PolicyIssue("requiresAuth", "AUTH_CONTRADICTS_PUBLIC", "a PUBLIC app cannot require sign-in; use TENANT or PRIVATE")
            if (project.hasDataBindings && !update.acknowledgePublicData)
                issues += PolicyIssue("acknowledgePublicData", "PUBLIC_DATA_NOT_APPROVED", "this app shows data from data sources; confirm that this data may be public")
        }
        update.cacheSeconds?.let { if (it !in 0..MAX_CACHE_SECONDS) issues += PolicyIssue("cacheSeconds", "INVALID", "must be between 0 and $MAX_CACHE_SECONDS") }
        return issues
    }
}
