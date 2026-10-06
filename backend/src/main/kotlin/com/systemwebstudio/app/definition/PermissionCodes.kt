package com.systemwebstudio.app.definition

/**
 * The canonical permission vocabulary (docs/contracts/v2/tenant-permission.md §5, owner C1). `PermissionDef.permission` must be one of these
 * codes; a free pattern is not enough (an unknown code would be a permission nobody grants, or one that is granted by accident).
 * A conformance test (ContractConformanceTests) keeps this list equal to the contract table. Adding a code is a contract change decided by C1.
 */
object PermissionCodes {
    val ALL: List<String> = listOf(
        "APP_VIEW", "APP_USE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE",
        "DATA_SOURCE_VIEW", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE", "DATA_MUTATE",
        "ACTION_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE",
        "TENANT_MANAGE", "TENANT_MEMBERS"
    )
    private val SET = ALL.toSet()
    fun isCanonical(code: String): Boolean = code in SET
}
