package com.systemwebstudio.app.definition

import com.systemwebstudio.schema.PageSchemaValidator
import com.systemwebstudio.schema.Violation
import tools.jackson.databind.JsonNode

/** Top-level keys of an app definition document. */
object AppDefinitionKeys {
    const val SCHEMA_VERSION = "schemaVersion"
    const val KIND = "kind"
    const val THEME = "theme"
    const val PUBLISH_CONFIG = "publishConfig"
    const val EXTENSIONS = "extensions"
    const val CURRENT_SCHEMA_VERSION = 2

    /** the optional V2 collections, in canonical write order */
    val COLLECTIONS = listOf("dataSources", "viewModels", "queries", "mappings", "actions", "workflows", "permissions", "dataBindings")
    val ENVELOPE = setOf(SCHEMA_VERSION, KIND)
    val V2_KEYS: Set<String> = ENVELOPE + COLLECTIONS + THEME + PUBLISH_CONFIG + EXTENSIONS

    /** keys of the Page Schema as it exists today (the validator ignores other top-level keys; V2 documents must not carry them) */
    val LEGACY_KEYS = setOf("page", "sections", "pages", "site", "seo")

    /** true when the document uses any V2 key; a plain legacy Page Schema is NOT touched by any V2 rule */
    fun declaresV2(schema: JsonNode): Boolean = schema.isObject && V2_KEYS.any { schema.has(it) }
}

object AppDefinitionLimits {
    const val MAX_DATA_SOURCES = 20
    const val MAX_QUERIES = 100
    const val MAX_MAPPINGS = 100
    const val MAX_VIEW_MODELS = 100
    const val MAX_ACTIONS = 100
    const val MAX_WORKFLOWS = 30
    const val MAX_PERMISSIONS = 100
    const val MAX_BINDINGS = 200
    const val MAX_PARAMS = 20
    const val MAX_FIELDS = 100
    const val MAX_STEPS = 50
    const val MAX_MAPPING_FIELDS = 200
    const val MAX_ACTION_INPUTS = 64
    const val MAX_STEP_INPUTS = 32
    const val MAX_PRINCIPALS = 50
    const val MAX_CHAIN = 8
    const val MAX_BRANCHES = 10
    const val MAX_CONDITION_DEPTH = 5
    const val MAX_CONDITION_NODES = 20
    const val MAX_NAME = 80
    const val MAX_DESCRIPTION = 300
    const val MAX_STRING = 2000
    const val MAX_ROWS = 10_000
    const val MAX_CACHE_SECONDS = 86_400
    const val MAX_THEME_COLORS = 12
    const val MAX_EXTENSIONS = 10
    const val MAX_EXTENSION_DEPTH = 6
}

/**
 * Reads the V2 part of a document into the typed model while collecting violations (path + message, same style as PageSchemaValidator).
 * One reader serves both the validator (reports everything) and the codec (throws when anything is wrong), so the rules exist once.
 * It checks structure, types, patterns and limits INSIDE each definition; references BETWEEN definitions are checked by the validator.
 */
internal class AppDefinitionReader(private val out: MutableList<Violation>) {
    companion object {
        val ID = PageSchemaValidator.SECTION_ID
        /** a view model / mapping target field name (C3 `FieldMapping.TO`) */
        val IDENT = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        val PROP = Regex("^[A-Za-z][A-Za-z0-9]{0,39}$")
        /** a column name optionally followed by `.segments` (object keys or array indexes): C3 `FieldMapping.FROM` */
        val SOURCE_PATH = Regex("^[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}(\\.[A-Za-z0-9_][A-Za-z0-9_ -]{0,63}){0,7}$")
        /** a plain reference id used by actions / workflows (C4 `ActionDefinitionValidator.REF_ID`) */
        val REF_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        /** dotted plain segments, at most 8 (C4 `InputResolver.isValidPath`) */
        val PLAIN_PATH_SEGMENT = Regex("^[A-Za-z0-9_-]{1,64}$")
        val STEP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val TIMEZONE = Regex("^[A-Za-z][A-Za-z0-9_+/-]{0,63}$")
        /** the id of an approved query / operation in the data platform catalog (C3 `SqlQueryDefinition.ID`) */
        val OPERATION_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        /** a declared query parameter (C3 `QueryParamSpec.NAME`) */
        val PARAM_NAME = Regex("^[a-z][A-Za-z0-9_]{0,39}$")
        /** a declared action input (C4 `ActionDefinitionValidator` input names) */
        val INPUT_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
        val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$")
        val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        val THEME_COLOR_NAME = Regex("^[a-z][A-Za-z0-9]{0,23}$")
        val DATA_SOURCE_TYPE = Regex("^[a-z][a-z0-9-]{0,31}$")
        val CRON = Regex("^[0-9*/,\\-]{1,30}( [0-9*/,\\-]{1,30}){4}$")
        val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val ACTION_KEYS = setOf("id", "name", "type", "enabled", "trigger", "pageRef", "queryRef", "viewModelRef", "dataSourceRef", "operationKey", "workflowRef",
            "channel", "templateRef", "endpointRef", "recipients", "permissionRef", "inputs", "inputMapping", "idempotency", "limits", "onSuccess", "onError")
        private val COMPARE_OPS = listOf("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS")
        val TRANSFORM_TYPE = Regex("^[A-Za-z]{1,20}$")
        const val MAX_TRANSFORMS = 8
        val EXTENSION_KEY = Regex("^[a-z][a-z0-9-]{0,31}(\\.[a-z][a-z0-9-]{0,31}){0,3}$")

        /** a URL with a scheme, or a script-ish scheme: never allowed anywhere in the V2 part (references go by id) */
        private val URLISH = Regex("(?i)(://|^\\s*(javascript|vbscript|file):|^\\s*data:\\S)")

        /** object keys that would smuggle raw URLs, SQL, credentials or code into the free-form extension area (compared lower-case, without _ and -) */
        private val FORBIDDEN_KEYS = setOf(
            "password", "passwd", "secret", "clientsecret", "token", "accesstoken", "refreshtoken", "apikey", "accesskey", "privatekey",
            "credential", "credentials", "connectionstring", "connstring", "authorization", "sql", "rawsql", "url", "uri", "endpoint", "script", "javascript", "eval"
        )

        /** shared with the template sanitizer (template/TemplateSanitizer.kt) so both apply the same free-form rules */
        fun isForbiddenKey(key: String): Boolean = key.lowercase().replace("_", "").replace("-", "") in FORBIDDEN_KEYS
        fun looksLikeUrl(text: String): Boolean = URLISH.containsMatchIn(text)
    }

    private fun v(path: String, message: String) { out += Violation(path, message) }
    private fun join(at: String, key: String) = if (at.isEmpty()) key else "$at.$key"

    // ---- document ----

    fun read(root: JsonNode): AppDefinitionV2 {
        if (!root.isObject) { v("$", "app definition must be an object"); return AppDefinitionV2() }
        val declared = root.has(AppDefinitionKeys.SCHEMA_VERSION)
        val version = root.get(AppDefinitionKeys.SCHEMA_VERSION)?.let { n ->
            if (n.isInt && n.asInt() == AppDefinitionKeys.CURRENT_SCHEMA_VERSION) n.asInt()
            else { v(AppDefinitionKeys.SCHEMA_VERSION, "must be ${AppDefinitionKeys.CURRENT_SCHEMA_VERSION}"); null }
        }
        val kind = enumOf<AppDefinitionKind>(root, AppDefinitionKeys.KIND, "")
        // a document that declares itself V2 may only use known keys; an undeclared (legacy) one keeps today's behaviour of ignoring unknown keys
        val known = AppDefinitionKeys.LEGACY_KEYS + AppDefinitionKeys.V2_KEYS
        val legacy = LinkedHashMap<String, JsonNode>()
        root.propertyNames().forEach { k ->
            if (k in AppDefinitionKeys.V2_KEYS) return@forEach
            if (declared && k !in known) v(k, "unknown top-level field '$k'")
            root.get(k)?.let { legacy[k] = it }
        }
        return AppDefinitionV2(
            schemaVersion = version, kind = kind, pageSchema = legacy,
            dataSources = list(root, "dataSources", "", AppDefinitionLimits.MAX_DATA_SOURCES, this::dataSource),
            viewModels = list(root, "viewModels", "", AppDefinitionLimits.MAX_VIEW_MODELS, this::viewModel),
            queries = list(root, "queries", "", AppDefinitionLimits.MAX_QUERIES, this::query),
            mappings = list(root, "mappings", "", AppDefinitionLimits.MAX_MAPPINGS, this::mapping),
            actions = list(root, "actions", "", AppDefinitionLimits.MAX_ACTIONS, this::action),
            workflows = list(root, "workflows", "", AppDefinitionLimits.MAX_WORKFLOWS, this::workflow),
            permissions = list(root, "permissions", "", AppDefinitionLimits.MAX_PERMISSIONS, this::permission),
            dataBindings = list(root, "dataBindings", "", AppDefinitionLimits.MAX_BINDINGS, this::dataBinding),
            theme = theme(root),
            publishConfig = publishConfig(root),
            extensions = extensions(root)
        )
    }

    // ---- definitions ----

    private fun dataSource(n: JsonNode, at: String): DataSourceDef? {
        if (!obj(n, at, setOf("id", "name", "type", "sourceRef", "description"))) return null
        val id = id(n, "id", at, true)
        val type = pattern(n, "type", at, DATA_SOURCE_TYPE, "lowercase letters, digits and dashes, starting with a letter, up to 32 characters", true)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val sourceRef = pattern(n, "sourceRef", at, UUID_TEXT, "a data source id (UUID) registered in the data platform", false)
        val description = text(n, "description", at, AppDefinitionLimits.MAX_DESCRIPTION)
        return if (id == null || type == null) null else DataSourceDef(id, name, type, sourceRef, description)
    }

    private fun query(n: JsonNode, at: String): QueryDef? {
        if (!obj(n, at, setOf("id", "name", "dataSourceRef", "mode", "operationKey", "params", "maxRows", "public"))) return null
        val id = id(n, "id", at, true)
        val ds = id(n, "dataSourceRef", at, true)
        val mode = enumOf<QueryMode>(n, "mode", at) ?: QueryMode.READ
        val key = pattern(n, "operationKey", at, OPERATION_KEY, "an operation key (letters, digits, . _ -)", false)
        val params = params(n, "params", at, PARAM_NAME, "a parameter name (lowercase first letter, then letters, digits, _)")
        val maxRows = int(n, "maxRows", at, 1, AppDefinitionLimits.MAX_ROWS)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val public = bool(n, "public", at) ?: false
        return if (id == null || ds == null) null else QueryDef(id, name, ds, mode, key, params, maxRows, public)
    }

    private fun mapping(n: JsonNode, at: String): MappingDef? {
        if (!obj(n, at, setOf("id", "name", "description", "queryRef", "fields", "errorPolicy", "version"))) return null
        val id = id(n, "id", at, true)
        val q = id(n, "queryRef", at, true)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val description = text(n, "description", at, AppDefinitionLimits.MAX_DESCRIPTION)
        val policy = enumOf<MappingErrorPolicy>(n, "errorPolicy", at) ?: MappingErrorPolicy.NULL_FIELD
        val version = int(n, "version", at, 1, 1_000_000_000)?.toLong() ?: 1L
        val fields = list(n, "fields", at, AppDefinitionLimits.MAX_MAPPING_FIELDS) { f, p -> fieldMapping(f, p) }
        if (id != null && q != null && !n.has("fields")) v(join(at, "fields"), "is required (a mapping needs at least one field)")
        else if (id != null && q != null && fields.isEmpty() && !hasProblemAt(join(at, "fields"))) v(join(at, "fields"), "a mapping needs at least one field")
        return if (id == null || q == null) null else MappingDef(id, name, q, fields, policy, version, description)
    }

    /** one column of a mapping; the key set and the rules are C3's `MappingJson` / `FieldMapping` */
    private fun fieldMapping(f: JsonNode, p: String): FieldMappingDef? {
        if (!obj(f, p, setOf("from", "to", "transforms", "transform", "default", "nullable", "validation", "description"))) return null
        val from = pattern(f, "from", p, SOURCE_PATH, "a plain column / response path such as customer.name (no expressions)", false)
        val to = pattern(f, "to", p, IDENT, "a field name (letters, digits, _)", true)
        val transforms = transforms(f, p)
        val default = raw(f, "default", p)?.also { d ->
            if (!d.isValueNode) v(join(p, "default"), "must be a plain value")
            else if (d.isString) scanFree(d, join(p, "default"), 1)
        }
        val nullable = bool(f, "nullable", p) ?: true
        val validation = raw(f, "validation", p)?.let { fieldValidation(it, join(p, "validation")) }
        val description = text(f, "description", p, AppDefinitionLimits.MAX_DESCRIPTION)
        if (to != null && from == null && !f.has("from") && transforms.isEmpty() && !f.has("transforms") && !f.has("transform") && default == null && !hasProblemAt(p))
            v(p, "a field needs a source (from), a transform or a default")
        return if (to == null) null else FieldMappingDef(from, to, transforms, default, nullable, validation, description)
    }

    /**
     * The transforms of one mapping field. Canonical key `transforms` = an array of at most 8 transform objects. The legacy key `transform`
     * (C3's previous reader: a single object, or an array) is accepted for backward compatibility and normalised to the same list; both keys
     * together are rejected. Each element needs a text `type`; which types exist and their members are C3's `Transforms` (closed set).
     */
    private fun transforms(f: JsonNode, p: String): List<JsonNode> {
        val canonical = raw(f, "transforms", p)
        val legacy = raw(f, "transform", p)
        if (canonical != null && legacy != null) { v(join(p, "transform"), "use either 'transforms' (canonical) or the legacy 'transform', not both"); return emptyList() }
        val key = if (canonical != null) "transforms" else "transform"
        val node = canonical ?: legacy ?: return emptyList()
        val tp = join(p, key)
        val items: List<JsonNode> = when {
            node.isArray -> node.toList()
            node.isObject && key == "transform" -> listOf(node)
            key == "transforms" -> { v(tp, "must be an array of transform objects"); return emptyList() }
            else -> { v(tp, "must be a transform object or an array of them"); return emptyList() }
        }
        if (items.size > MAX_TRANSFORMS) { v(tp, "at most $MAX_TRANSFORMS transforms"); return emptyList() }
        items.forEachIndexed { i, e ->
            val ep = "$tp[$i]"
            if (!e.isObject) v(ep, "must be an object")
            else {
                val type = e.get("type")
                if (type == null || !type.isString || !TRANSFORM_TYPE.matches(type.asString())) v(join(ep, "type"), "a transform needs a text type (letters only)")
                scanFree(e, ep, 1)
            }
        }
        return items
    }

    private fun fieldValidation(n: JsonNode, at: String): FieldValidationDef? {
        if (!obj(n, at, setOf("minLength", "maxLength", "min", "max", "oneOf", "format"))) return null
        fun num(key: String): JsonNode? = raw(n, key, at)?.also { if (!it.isNumber) v(join(at, key), "must be a number") }?.takeIf { it.isNumber }
        val oneOf = raw(n, "oneOf", at)?.let { o ->
            val op = join(at, "oneOf")
            if (!o.isArray) { v(op, "must be an array"); null }
            else if (o.size() !in 1..200) { v(op, "must have 1 to 200 values"); null }
            else {
                o.forEachIndexed { i, e -> if (!e.isValueNode) v("$op[$i]", "must be a plain value") else if (e.isString) scanFree(e, "$op[$i]", 1) }
                o.toList()
            }
        }
        return FieldValidationDef(int(n, "minLength", at, 0, 100_000), int(n, "maxLength", at, 0, 100_000), num("min"), num("max"), oneOf, enumOf<ValueFormat>(n, "format", at))
    }

    private fun viewModel(n: JsonNode, at: String): ViewModelDef? {
        if (!obj(n, at, setOf("id", "name", "description", "queryRef", "mappingRef", "cardinality", "fields"))) return null
        val id = id(n, "id", at, true)
        val q = id(n, "queryRef", at, false)
        val m = id(n, "mappingRef", at, false)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val description = text(n, "description", at, AppDefinitionLimits.MAX_DESCRIPTION)
        val card = enumOf<Cardinality>(n, "cardinality", at) ?: Cardinality.LIST
        val fields = list(n, "fields", at, AppDefinitionLimits.MAX_FIELDS) { f, p ->
            if (!obj(f, p, setOf("name", "type", "label", "description"))) null
            else {
                val fname = pattern(f, "name", p, IDENT, "a field name (letters, digits, _)", true)
                val type = enumOf<FieldType>(f, "type", p) ?: FieldType.STRING
                val label = text(f, "label", p, AppDefinitionLimits.MAX_NAME)
                val fdescription = text(f, "description", p, AppDefinitionLimits.MAX_DESCRIPTION)
                if (fname == null) null else ViewModelFieldDef(fname, type, label, fdescription)
            }
        }
        return if (id == null) null else ViewModelDef(id, name, q, m, card, fields, description)
    }

    private fun action(n: JsonNode, at: String): ActionDef? {
        if (!obj(n, at, ACTION_KEYS)) return null
        val id = refId(n, "id", at, true)
        val type = enumOf<ActionType>(n, "type", at, required = true)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val trigger = n.get("trigger")?.let { t ->
            val p = join(at, "trigger")
            if (t.isNull) { v(p, "must not be null"); null }
            else if (!obj(t, p, setOf("sectionId", "event"))) null
            else {
                val s = id(t, "sectionId", p, true)
                val e = string(t, "event", p, true)
                if (e != null && e !in ActionEvents.ALL) { v(join(p, "event"), "must be one of ${ActionEvents.ALL.joinToString()}"); null }
                else if (s == null || e == null) null else ActionTriggerDef(s, e)
            }
        }
        val queryRef = id(n, "queryRef", at, false)
        val vmRef = id(n, "viewModelRef", at, false)
        val wfRef = refId(n, "workflowRef", at, false)
        val dsRef = id(n, "dataSourceRef", at, false)
        val opKey = pattern(n, "operationKey", at, OPERATION_KEY, "an operation key (letters, digits, . _ -)", false)
        val inputs = list(n, "inputs", at, AppDefinitionLimits.MAX_ACTION_INPUTS) { p, path -> actionInput(p, path) }
        val perm = id(n, "permissionRef", at, false)
        val page = id(n, "pageRef", at, false)
        val enabled = bool(n, "enabled", at) ?: true
        val channel = enumOf<NotifyChannelName>(n, "channel", at)
        val templateRef = pattern(n, "templateRef", at, REF_ID, "a plain template id", false)
        val endpointRef = pattern(n, "endpointRef", at, REF_ID, "a registered endpoint id (never a URL)", false)
        val recipients = list(n, "recipients", at, AppDefinitionLimits.MAX_PRINCIPALS) { p, path -> principal(p, path) }
        val inputMapping = inputMapping(n, at)
        val idempotency = enumOf<IdempotencyPolicyName>(n, "idempotency", at)
        val limits = raw(n, "limits", at)?.let { l ->
            val lp = join(at, "limits")
            if (!obj(l, lp, setOf("timeoutMillis"))) null else ActionLimitsDef(int(l, "timeoutMillis", lp, 1, 3_600_000))
        }
        val onSuccess = idList(n, "onSuccess", at, AppDefinitionLimits.MAX_CHAIN)
        val onError = idList(n, "onError", at, AppDefinitionLimits.MAX_CHAIN)
        return if (id == null || type == null) null else ActionDef(id, name, type, trigger, queryRef, vmRef, wfRef, dsRef, opKey, inputs, perm, page,
            enabled, channel, templateRef, endpointRef, recipients, inputMapping, idempotency, limits, onSuccess, onError)
    }

    private fun actionInput(p: JsonNode, path: String): ActionInputDef? {
        if (!obj(p, path, setOf("name", "type", "required", "maxLength"))) return null
        val name = pattern(p, "name", path, INPUT_NAME, "an input name (letters, digits, _)", true)
        val type = enumOf<ActionInputType>(p, "type", path) ?: ActionInputType.ANY
        val required = bool(p, "required", path) ?: false
        val maxLength = int(p, "maxLength", path, 1, 100_000)
        if (maxLength != null && type != ActionInputType.STRING) v(join(path, "maxLength"), "is only valid for STRING inputs")
        return if (name == null) null else ActionInputDef(name, type, required, maxLength)
    }

    private fun inputMapping(n: JsonNode, at: String): Map<String, InputSourceDef> {
        val m = raw(n, "inputMapping", at) ?: return emptyMap()
        val mp = join(at, "inputMapping")
        if (!m.isObject) { v(mp, "must be an object"); return emptyMap() }
        if (m.size() > AppDefinitionLimits.MAX_ACTION_INPUTS) { v(mp, "at most ${AppDefinitionLimits.MAX_ACTION_INPUTS} entries"); return emptyMap() }
        val out = LinkedHashMap<String, InputSourceDef>()
        m.propertyNames().forEach { name ->
            val p = "$mp.$name"
            if (!INPUT_NAME.matches(name)) { v(p, "input name must match ${INPUT_NAME.pattern}"); return@forEach }
            val src = m.get(name)
            if (src == null || !obj(src, p, setOf("source", "path", "name", "value", "key"))) return@forEach
            val kind = enumOf<InputSourceKind>(src, "source", p, required = true) ?: return@forEach
            val path = string(src, "path", p, false)
            val nm = pattern(src, "name", p, INPUT_NAME, "a name (letters, digits, _)", false)
            val value = raw(src, "value", p)
            val key = enumOf<ContextKeyName>(src, "key", p)
            when (kind) {
                InputSourceKind.COMPONENT_STATE, InputSourceKind.VIEW_MODEL -> if (path == null) v(join(p, "path"), "is required for $kind") else plainPath(path, join(p, "path"), false)
                InputSourceKind.PREVIOUS_RESULT -> if (path == null) v(join(p, "path"), "is required for PREVIOUS_RESULT (may be empty)") else plainPath(path, join(p, "path"), true)
                InputSourceKind.ROUTE_PARAM, InputSourceKind.FORM_FIELD -> if (nm == null && !src.has("name")) v(join(p, "name"), "is required for $kind")
                InputSourceKind.LITERAL -> if (value == null) v(join(p, "value"), "is required for LITERAL") else scanFree(value, join(p, "value"), 1)
                InputSourceKind.CONTEXT -> if (key == null && !src.has("key")) v(join(p, "key"), "is required for CONTEXT")
            }
            val used = listOfNotNull("path".takeIf { src.has("path") }, "name".takeIf { src.has("name") }, "value".takeIf { src.has("value") }, "key".takeIf { src.has("key") })
            val allowed = when (kind) {
                InputSourceKind.COMPONENT_STATE, InputSourceKind.VIEW_MODEL, InputSourceKind.PREVIOUS_RESULT -> setOf("path")
                InputSourceKind.ROUTE_PARAM, InputSourceKind.FORM_FIELD -> setOf("name")
                InputSourceKind.LITERAL -> setOf("value")
                InputSourceKind.CONTEXT -> setOf("key")
            }
            used.filter { it !in allowed }.forEach { v(join(p, it), "is not used by source $kind") }
            out[name] = InputSourceDef(kind, path, nm, value, key)
        }
        return out
    }

    private fun principal(n: JsonNode, at: String): PrincipalDef? {
        if (!obj(n, at, setOf("kind", "userId", "tenantId", "groupId", "role", "departmentId"))) return null
        val kind = enumOf<PrincipalKind>(n, "kind", at, required = true)
        val userId = pattern(n, "userId", at, UUID_TEXT, "a user id (UUID)", false)
        val tenantId = pattern(n, "tenantId", at, UUID_TEXT, "a tenant id (UUID)", false)
        val groupId = pattern(n, "groupId", at, REF_ID, "a plain group id", false)
        val role = pattern(n, "role", at, REF_ID, "a plain role id", false)
        val department = pattern(n, "departmentId", at, REF_ID, "a plain department id", false)
        when (kind) {
            PrincipalKind.USER -> if (userId == null && !n.has("userId")) v(join(at, "userId"), "is required for USER")
            PrincipalKind.GROUP -> if (groupId == null && !n.has("groupId")) v(join(at, "groupId"), "is required for GROUP")
            PrincipalKind.ROLE -> if (role == null && !n.has("role")) v(join(at, "role"), "is required for ROLE")
            else -> {}
        }
        return if (kind == null) null else PrincipalDef(kind, userId, tenantId, groupId, role, department)
    }

    private fun workflow(n: JsonNode, at: String): WorkflowDef? {
        if (!obj(n, at, setOf("id", "name", "enabled", "trigger", "schedule", "timezone", "startStepId", "compensateOnCancel", "limits", "steps"))) return null
        val id = refId(n, "id", at, true)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val enabled = bool(n, "enabled", at) ?: true
        val trigger = enumOf<WorkflowTriggerType>(n, "trigger", at) ?: WorkflowTriggerType.MANUAL
        val schedule = pattern(n, "schedule", at, CRON, "a 5-field cron expression", false)
        val timezone = pattern(n, "timezone", at, TIMEZONE, "a time zone id such as Asia/Ho_Chi_Minh", false)
        if (trigger == WorkflowTriggerType.SCHEDULE && schedule == null && !n.has("schedule")) v(join(at, "schedule"), "is required for a SCHEDULE trigger")
        if (trigger != WorkflowTriggerType.SCHEDULE && n.has("schedule")) v(join(at, "schedule"), "is only allowed for a SCHEDULE trigger")
        if (trigger != WorkflowTriggerType.SCHEDULE && n.has("timezone")) v(join(at, "timezone"), "is only allowed for a SCHEDULE trigger")
        val start = pattern(n, "startStepId", at, STEP_ID, "a step id", false)
        val compensate = bool(n, "compensateOnCancel", at) ?: false
        val limits = raw(n, "limits", at)?.let { l ->
            val lp = join(at, "limits")
            if (!obj(l, lp, setOf("maxSteps", "maxStepExecutions", "maxRetries", "maxDurationSeconds", "maxPayloadBytes", "maxDepth", "maxStepTimeoutMillis"))) null
            else WorkflowLimitsDef(int(l, "maxSteps", lp, 1, 1000), int(l, "maxStepExecutions", lp, 1, 100_000), int(l, "maxRetries", lp, 0, 100),
                int(l, "maxDurationSeconds", lp, 1, 2_592_000), int(l, "maxPayloadBytes", lp, 1, 10_485_760), int(l, "maxDepth", lp, 1, 10),
                int(l, "maxStepTimeoutMillis", lp, 1, 3_600_000))
        }
        val steps = list(n, "steps", at, AppDefinitionLimits.MAX_STEPS) { s, p -> step(s, p) }
        return if (id == null) null else WorkflowDef(id, name, trigger, schedule, steps, enabled, timezone, start, compensate, limits)
    }

    private fun step(s: JsonNode, p: String): WorkflowStepDef? {
        if (!obj(s, p, setOf("id", "kind", "actionRef", "inputs", "next", "onError", "retry", "timeoutMillis", "waitSeconds", "approval", "branches", "defaultNext", "compensationActionRef"))) return null
        val sid = pattern(s, "id", p, STEP_ID, "a step id (letters, digits, . _ -)", true)
        val explicitKind = enumOf<StepKind>(s, "kind", p)
        val action = refId(s, "actionRef", p, false)
        val next = pattern(s, "next", p, STEP_ID, "a step id", false)
        val onError = pattern(s, "onError", p, STEP_ID, "a step id", false)
        val defaultNext = pattern(s, "defaultNext", p, STEP_ID, "a step id", false)
        val compensation = refId(s, "compensationActionRef", p, false)
        val inputs = valueRefs(s, p)
        val retry = raw(s, "retry", p)?.let { r ->
            val rp = join(p, "retry")
            if (!obj(r, rp, setOf("maxAttempts", "initialBackoffMillis", "multiplier", "maxBackoffMillis"))) null
            else {
                val multiplier = raw(r, "multiplier", rp)?.also {
                    if (!it.isNumber || it.asDouble() < 1.0 || it.asDouble() > 10.0) v(join(rp, "multiplier"), "must be a number between 1 and 10")
                }?.takeIf { it.isNumber }
                RetryDef(int(r, "maxAttempts", rp, 1, 100), int(r, "initialBackoffMillis", rp, 0, 86_400_000), multiplier, int(r, "maxBackoffMillis", rp, 0, 86_400_000))
            }
        }
        val timeout = int(s, "timeoutMillis", p, 1, 3_600_000)
        val wait = int(s, "waitSeconds", p, 1, 2_592_000)
        val approval = raw(s, "approval", p)?.let { approval(it, join(p, "approval")) }
        val branches = list(s, "branches", p, AppDefinitionLimits.MAX_BRANCHES) { b, bp ->
            if (!obj(b, bp, setOf("condition", "next"))) null
            else {
                val cond = raw(b, "condition", bp)
                val bnext = pattern(b, "next", bp, STEP_ID, "a step id", true)
                if (cond == null && !b.has("condition")) v(join(bp, "condition"), "is required")
                else if (cond != null) condition(cond, join(bp, "condition"), 1, IntArray(1))
                if (cond == null || bnext == null) null else BranchDef(cond, bnext)
            }
        }
        val kind = explicitKind ?: if (action != null) StepKind.ACTION else StepKind.END
        if (sid != null && !hasProblemAt(p)) when (kind) {
            StepKind.ACTION -> if (action == null) v(join(p, "actionRef"), "is required for an ACTION step")
            StepKind.WAIT -> if (wait == null) v(join(p, "waitSeconds"), "is required for a WAIT step")
            StepKind.APPROVAL -> if (approval == null) v(join(p, "approval"), "is required for an APPROVAL step")
            StepKind.BRANCH -> if (branches.isEmpty()) v(join(p, "branches"), "at least one branch is required for a BRANCH step")
            StepKind.END -> {}
        }
        return if (sid == null) null else WorkflowStepDef(sid, action, next, onError, explicitKind, inputs, retry, timeout, wait, approval, branches, defaultNext, compensation)
    }

    private fun approval(n: JsonNode, at: String): ApprovalDef? {
        if (!obj(n, at, setOf("title", "approvers", "requiredApprovals", "expiresInSeconds", "allowSelfApproval", "notifyTemplateRef", "onReject", "onExpire"))) return null
        val title = text(n, "title", at, 200)
        if (title != null && title.isBlank()) v(join(at, "title"), "must not be blank")
        if (title == null && !n.has("title")) v(join(at, "title"), "is required")
        val approvers = list(n, "approvers", at, AppDefinitionLimits.MAX_PRINCIPALS) { p, path -> principal(p, path) }
        if (approvers.isEmpty() && !hasProblemAt(join(at, "approvers"))) v(join(at, "approvers"), "at least one approver is required")
        return ApprovalDef(title, approvers, int(n, "requiredApprovals", at, 1, 100), int(n, "expiresInSeconds", at, 1, 2_592_000), bool(n, "allowSelfApproval", at),
            pattern(n, "notifyTemplateRef", at, REF_ID, "a plain template id", false), pattern(n, "onReject", at, STEP_ID, "a step id", false), pattern(n, "onExpire", at, STEP_ID, "a step id", false))
    }

    private fun valueRefs(s: JsonNode, p: String): Map<String, ValueRefDef> {
        val m = raw(s, "inputs", p) ?: return emptyMap()
        val mp = join(p, "inputs")
        if (!m.isObject) { v(mp, "must be an object"); return emptyMap() }
        if (m.size() > AppDefinitionLimits.MAX_STEP_INPUTS) { v(mp, "at most ${AppDefinitionLimits.MAX_STEP_INPUTS} entries"); return emptyMap() }
        val out = LinkedHashMap<String, ValueRefDef>()
        m.propertyNames().forEach { name ->
            if (!INPUT_NAME.matches(name)) { v("$mp.$name", "input name must match ${INPUT_NAME.pattern}"); return@forEach }
            valueRef(m.get(name), "$mp.$name")?.let { out[name] = it }
        }
        return out
    }

    /** `{from: INPUT|STEP|LITERAL, ...}` (C4 `ValueRef`) */
    private fun valueRef(n: JsonNode?, at: String): ValueRefDef? {
        if (n == null || !obj(n, at, setOf("from", "path", "stepId", "value"))) return null
        val from = enumOf<ValueRefFrom>(n, "from", at, required = true) ?: return null
        val path = string(n, "path", at, false)
        val stepId = pattern(n, "stepId", at, STEP_ID, "a step id", false)
        val value = raw(n, "value", at)
        when (from) {
            ValueRefFrom.INPUT -> path?.let { plainPath(it, join(at, "path"), true) }
            ValueRefFrom.STEP -> { path?.let { plainPath(it, join(at, "path"), true) }; if (stepId == null && !n.has("stepId")) v(join(at, "stepId"), "is required for STEP") }
            ValueRefFrom.LITERAL -> if (value == null) v(join(at, "value"), "is required for LITERAL") else scanFree(value, join(at, "value"), 1)
        }
        return ValueRefDef(from, path, stepId, value)
    }

    /**
     * C4's condition tree: `{op,left,right}` | `{all:[..]}` | `{any:[..]}` | `{not:cond}` | `{exists:ref}`. Bounded in depth and size; the
     * meaning is C4's. Kept as JSON in [BranchDef.condition].
     */
    private fun condition(n: JsonNode, at: String, depth: Int, count: IntArray) {
        if (depth > AppDefinitionLimits.MAX_CONDITION_DEPTH) { v(at, "condition nested deeper than ${AppDefinitionLimits.MAX_CONDITION_DEPTH} levels"); return }
        if (++count[0] > AppDefinitionLimits.MAX_CONDITION_NODES) { if (count[0] == AppDefinitionLimits.MAX_CONDITION_NODES + 1) v(at, "condition has more than ${AppDefinitionLimits.MAX_CONDITION_NODES} nodes"); return }
        if (!n.isObject) { v(at, "must be an object"); return }
        when {
            n.has("all") || n.has("any") -> {
                val key = if (n.has("all")) "all" else "any"
                if (!obj(n, at, setOf(key))) return
                val items = n.get(key)
                if (!items.isArray || items.size() == 0) v(join(at, key), "must be a non-empty array of conditions")
                else items.forEachIndexed { i, c -> condition(c, "${join(at, key)}[$i]", depth + 1, count) }
            }
            n.has("not") -> { if (obj(n, at, setOf("not"))) condition(n.get("not"), join(at, "not"), depth + 1, count) }
            n.has("exists") -> { if (obj(n, at, setOf("exists"))) valueRef(n.get("exists"), join(at, "exists")) }
            else -> {
                if (!obj(n, at, setOf("op", "left", "right"))) return
                val op = string(n, "op", at, true)
                if (op != null && op !in COMPARE_OPS) v(join(at, "op"), "must be one of ${COMPARE_OPS.joinToString()}")
                if (!n.has("left")) v(join(at, "left"), "is required") else valueRef(n.get("left"), join(at, "left"))
                if (!n.has("right")) v(join(at, "right"), "is required") else valueRef(n.get("right"), join(at, "right"))
            }
        }
    }

    private fun permission(n: JsonNode, at: String): PermissionDef? {
        if (!obj(n, at, setOf("id", "name", "permission", "resourceType", "resourceRef"))) return null
        val id = id(n, "id", at, true)
        val name = text(n, "name", at, AppDefinitionLimits.MAX_NAME)
        val code = string(n, "permission", at, true)
        if (code != null && !PermissionCodes.isCanonical(code)) v(join(at, "permission"), "must be one of the canonical permission codes: ${PermissionCodes.ALL.joinToString()}")
        val type = enumOf<PermissionResourceType>(n, "resourceType", at, required = true)
        val ref = refId(n, "resourceRef", at, true)
        return if (id == null || code == null || !PermissionCodes.isCanonical(code) || type == null || ref == null) null else PermissionDef(id, name, code, type, ref)
    }

    private fun dataBinding(n: JsonNode, at: String): DataBindingDef? {
        if (!obj(n, at, setOf("id", "sectionId", "prop", "viewModelRef", "queryRef", "mappingRef"))) return null
        val id = id(n, "id", at, true)
        val section = id(n, "sectionId", at, true)
        val prop = pattern(n, "prop", at, PROP, "a prop name (letters and digits)", true)
        val vm = id(n, "viewModelRef", at, false)
        val q = id(n, "queryRef", at, false)
        val m = id(n, "mappingRef", at, false)
        if ((vm == null) == (q == null) && !hasProblemAt(at)) v(at, "exactly one of viewModelRef or queryRef is required")
        if (m != null && vm != null) v(join(at, "mappingRef"), "mappingRef can only be combined with queryRef (a view model already names its mapping)")
        if (m != null && q == null && vm == null) v(join(at, "mappingRef"), "mappingRef needs queryRef")
        return if (id == null || section == null || prop == null) null else DataBindingDef(id, section, prop, vm, q, m)
    }

    private fun publishConfig(root: JsonNode): PublishConfigDef? {
        val n = root.get(AppDefinitionKeys.PUBLISH_CONFIG) ?: return null
        val at = AppDefinitionKeys.PUBLISH_CONFIG
        if (n.isNull) { v(at, "must not be null"); return null }
        if (!obj(n, at, setOf("mode", "visibility", "requiresAuth", "cacheSeconds"))) return null
        val mode = enumOf<PublishMode>(n, "mode", at) ?: PublishMode.STATIC
        // SERVER_APP is a source (server runtime) project; an app definition cannot describe one, so it is not a legal draft value
        if (mode == PublishMode.SERVER_APP) v(join(at, "mode"), "SERVER_APP is not available for an app definition; it is set in publish_configs for source projects")
        val visibility = enumOf<PublishVisibility>(n, "visibility", at) ?: PublishVisibility.PRIVATE
        val auth = bool(n, "requiresAuth", at) ?: false
        if (auth && visibility == PublishVisibility.PUBLIC) v(join(at, "requiresAuth"), "contradicts visibility PUBLIC")
        val cache = int(n, "cacheSeconds", at, 0, AppDefinitionLimits.MAX_CACHE_SECONDS)
        return PublishConfigDef(mode, visibility, auth, cache)
    }

    private fun theme(root: JsonNode): ThemeDef? {
        val n = root.get(AppDefinitionKeys.THEME) ?: return null
        val at = AppDefinitionKeys.THEME
        if (n.isNull) { v(at, "must not be null"); return null }
        if (!obj(n, at, setOf("colors", "fontFamily", "radius"))) return null
        val colors = LinkedHashMap<String, String>()
        raw(n, "colors", at)?.let { c ->
            val cp = join(at, "colors")
            if (!c.isObject) v(cp, "must be an object")
            else {
                if (c.size() > AppDefinitionLimits.MAX_THEME_COLORS) v(cp, "at most ${AppDefinitionLimits.MAX_THEME_COLORS} colours")
                c.propertyNames().forEach { k ->
                    val value = c.get(k)
                    if (!THEME_COLOR_NAME.matches(k)) v("$cp.$k", "colour name must be letters and digits, starting with a lowercase letter, up to 24 characters")
                    else if (value == null || !value.isString || !COLOR.matches(value.asString())) v("$cp.$k", "must be a colour like #1A2B3C")
                    else colors[k] = value.asString()
                }
            }
        }
        return ThemeDef(colors, enumOf<ThemeFont>(n, "fontFamily", at), enumOf<ThemeRadius>(n, "radius", at))
    }

    private fun extensions(root: JsonNode): Map<String, JsonNode> {
        val n = root.get(AppDefinitionKeys.EXTENSIONS) ?: return emptyMap()
        val at = AppDefinitionKeys.EXTENSIONS
        if (n.isNull) { v(at, "must not be null"); return emptyMap() }
        if (!n.isObject) { v(at, "must be an object"); return emptyMap() }
        if (n.size() > AppDefinitionLimits.MAX_EXTENSIONS) v(at, "at most ${AppDefinitionLimits.MAX_EXTENSIONS} extension namespaces")
        val res = LinkedHashMap<String, JsonNode>()
        n.propertyNames().forEach { k ->
            val p = "$at.$k"
            val value = n.get(k) ?: return@forEach
            if (!EXTENSION_KEY.matches(k)) v(p, "extension namespace must look like vendor or vendor.feature (lowercase letters, digits, dashes)")
            else if (!value.isObject) v(p, "must be an object")
            else { scanFree(value, p, 1); res[k] = value }
        }
        return res
    }

    // ---- building blocks ----

    private fun hasProblemAt(at: String) = out.any { it.path == at || it.path.startsWith("$at.") }

    /** the value must be a JSON object; unknown fields are reported */
    private fun obj(n: JsonNode, at: String, allowed: Set<String>): Boolean {
        if (!n.isObject) { v(at, "must be an object"); return false }
        n.propertyNames().filter { it !in allowed }.forEach { v(join(at, it), "unknown field '$it'") }
        return true
    }

    /** the value of an optional key: null when absent; an explicit JSON null is a violation */
    private fun raw(n: JsonNode, key: String, at: String): JsonNode? {
        val x = n.get(key) ?: return null
        if (x.isNull) { v(join(at, key), "must not be null"); return null }
        return x
    }

    private fun string(n: JsonNode, key: String, at: String, required: Boolean): String? {
        val x = raw(n, key, at)
        if (x == null) { if (required && !n.has(key)) v(join(at, key), "is required"); return null }
        if (!x.isString) { v(join(at, key), "must be a string"); return null }
        val s = x.asString()
        if (s.length > AppDefinitionLimits.MAX_STRING) { v(join(at, key), "longer than ${AppDefinitionLimits.MAX_STRING} characters"); return null }
        if (URLISH.containsMatchIn(s)) { v(join(at, key), "URLs are not allowed here; refer to a registered data source by id"); return null }
        return s
    }

    /** an id of an action / workflow / step or a reference to one: C4's plain reference id (`REF_ID`), a superset of [ID] */
    private fun refId(n: JsonNode, key: String, at: String, required: Boolean): String? =
        pattern(n, key, at, REF_ID, "a plain id (letters, digits, . _ -), up to 128 characters", required)

    private fun idList(n: JsonNode, key: String, at: String, max: Int): List<String> =
        list(n, key, at, max) { e, p ->
            if (!e.isString) { v(p, "must be a string"); null }
            else if (!REF_ID.matches(e.asString())) { v(p, "must be a plain action id"); null }
            else e.asString()
        }

    /** dotted plain segments, at most 8, no expressions / wildcards / brackets (C4 `InputResolver.isValidPath`) */
    private fun plainPath(path: String, at: String, allowEmpty: Boolean) {
        if (path.isEmpty()) { if (!allowEmpty) v(at, "must not be empty"); return }
        val parts = path.split('.')
        if (parts.size > 8 || !parts.all { PLAIN_PATH_SEGMENT.matches(it) }) v(at, "must be dotted plain segments (at most 8), no expressions")
    }

    private fun id(n: JsonNode, key: String, at: String, required: Boolean): String? =
        pattern(n, key, at, ID, "lowercase letters, digits and dashes, starting with a letter or digit, up to 64 characters", required)

    private fun pattern(n: JsonNode, key: String, at: String, re: Regex, what: String, required: Boolean): String? {
        val s = string(n, key, at, required) ?: return null
        if (!re.matches(s)) { v(join(at, key), "must be $what"); return null }
        return s
    }

    private fun text(n: JsonNode, key: String, at: String, max: Int): String? {
        val s = string(n, key, at, false) ?: return null
        if (s.length > max) { v(join(at, key), "longer than $max characters"); return null }
        return s
    }

    private fun bool(n: JsonNode, key: String, at: String): Boolean? {
        val x = raw(n, key, at) ?: return null
        if (!x.isBoolean) { v(join(at, key), "must be a boolean"); return null }
        return x.asBoolean()
    }

    private fun int(n: JsonNode, key: String, at: String, min: Int, max: Int): Int? {
        val x = raw(n, key, at) ?: return null
        if (!x.isInt) { v(join(at, key), "must be an integer"); return null }
        val value = x.asInt()
        if (value < min || value > max) { v(join(at, key), "must be between $min and $max"); return null }
        return value
    }

    private inline fun <reified E : Enum<E>> enumOf(n: JsonNode, key: String, at: String, required: Boolean = false): E? {
        val x = raw(n, key, at)
        if (x == null) { if (required && !n.has(key)) v(join(at, key), "is required"); return null }
        if (!x.isString) { v(join(at, key), "must be a string"); return null }
        val s = x.asString()
        val found = enumValues<E>().firstOrNull { it.name == s }
        if (found == null) v(join(at, key), "must be one of ${enumValues<E>().joinToString { it.name }}")
        return found
    }

    private fun <T : Any> list(n: JsonNode, key: String, at: String, max: Int, item: (JsonNode, String) -> T?): List<T> {
        val x = raw(n, key, at) ?: return emptyList()
        val path = join(at, key)
        if (!x.isArray) { v(path, "must be an array"); return emptyList() }
        if (x.size() > max) { v(path, "at most $max entries"); return emptyList() }
        val res = ArrayList<T>()
        x.forEachIndexed { i, e -> item(e, "$path[$i]")?.let { res.add(it) } }
        return res
    }

    private fun params(n: JsonNode, key: String, at: String, namePattern: Regex, nameWhat: String): List<ParamDef> =
        list(n, key, at, AppDefinitionLimits.MAX_PARAMS) { p, path ->
            if (!obj(p, path, setOf("name", "type", "required", "default"))) null
            else {
                val name = pattern(p, "name", path, namePattern, nameWhat, true)
                val type = enumOf<ParamType>(p, "type", path, required = true)
                val required = bool(p, "required", path) ?: true
                val default = raw(p, "default", path)
                if (default != null && type != null) checkDefault(default, type, join(path, "default"))
                if (name == null || type == null) null else ParamDef(name, type, required, default)
            }
        }

    /** a default is a plain scalar of the declared type, never an expression */
    private fun checkDefault(value: JsonNode, type: ParamType, at: String) {
        when (type) {
            ParamType.STRING -> if (!value.isString) v(at, "must be a string") else scanFree(value, at, 1)
            ParamType.INTEGER -> if (!value.isIntegralNumber) v(at, "must be an integer")
            ParamType.NUMBER -> if (!value.isNumber) v(at, "must be a number")
            ParamType.BOOLEAN -> if (!value.isBoolean) v(at, "must be a boolean")
            ParamType.TIMESTAMP -> if (!value.isString || !TIMESTAMP.matches(value.asString())) v(at, "must be a timestamp like 2026-10-05T08:30:00Z")
            ParamType.DATE -> if (!value.isString || !DATE.matches(value.asString())) v(at, "must be a date like 2026-10-05")
        }
    }

    /** free-form JSON (extension area, string defaults): bounded depth, no URLs, no code-like or credential-like keys */
    private fun scanFree(n: JsonNode, at: String, depth: Int) {
        if (depth > AppDefinitionLimits.MAX_EXTENSION_DEPTH) { v(at, "nested deeper than ${AppDefinitionLimits.MAX_EXTENSION_DEPTH} levels"); return }
        when {
            n.isString -> {
                val s = n.asString()
                if (s.length > AppDefinitionLimits.MAX_STRING) v(at, "longer than ${AppDefinitionLimits.MAX_STRING} characters")
                else if (URLISH.containsMatchIn(s)) v(at, "URLs are not allowed here; refer to a registered data source by id")
            }
            n.isArray -> n.forEachIndexed { i, e -> scanFree(e, "$at[$i]", depth + 1) }
            n.isObject -> n.propertyNames().forEach { k ->
                if (k.lowercase().replace("_", "").replace("-", "") in FORBIDDEN_KEYS) v("$at.$k", "key '$k' is not allowed (no URLs, SQL, credentials or code)")
                n.get(k)?.let { scanFree(it, "$at.$k", depth + 1) }
            }
        }
    }
}
