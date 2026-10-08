// @class: unit
// Source guards of the Dynamic Organization architecture (D-C0-43). CONTRACT-INDEPENDENT: they name no H-C1-17 route and no permission; they only keep the invariants
// that were frozen before the backend exists. Three guards live here (one pass over the production files):
//   ORG-HIERARCHY   (1) no fixed hierarchy: no level index with a meaning, no fixed depth, no hard-coded type transitions
//   ORG-FAIL-CLOSED (2) the not-ready adapter stays fail-closed: no swallowed error, no guessed route, no browser persistence, no client-made ids, no invented permission name,
//                       and READY capabilities are only the ones listed in tests/guards/org-contract.json (a deliberate two-key change when C1 delivers)
//   ORG-RELATION    (3) a relation type (MANAGER / HEAD / MEMBER of a unit) is never an authorization input
// Production code only: tests, fixtures and docs are never scanned, comments are blanked first, and a single deliberate exception needs `// guard-allow: <RULE> — <reason>`.
import { read, walk, stripComments, lineOf, isNonProduction, allowedByPragma, cli, REPO, existsSync, join } from "./lib.mjs";

const CODE = /\.(tsx?|mjs|js|kt)$/;
const FRONTEND_DIRS = /^(features|packages|apps|app|lib|components)\//;
const ORG_NAME = /organi[sz]ation|employee|org-?unit|unitIcons|PersonPicker|orgunit/i;
export const orgFiles = (root) => walk(root).filter((f) => CODE.test(f) && !isNonProduction(f) && (FRONTEND_DIRS.test(f) || f.startsWith("backend/src/main/")) && ORG_NAME.test(f));
const productionFiles = (root) => walk(root).filter((f) => CODE.test(f) && !isNonProduction(f) && (FRONTEND_DIRS.test(f) || f.startsWith("backend/src/main/")));

// A level number that picks an HTML heading tag (<h2> / "h3" / aria-level) is document structure (accessibility), not the organization hierarchy: the same statement names the heading tag.
const HEADING_LEVEL = /["'`]h[1-6]["'`]|<h[1-6][\s>]|aria-level/i;
const HIER = "company|companies|department|dept|team|division|branch|region|squad|section|unit|group|level\\d*|tier\\d*|công ty|phòng|phòng ban|khối|chi nhánh|bộ phận|nhóm|đội";
const DEPTH = "(?:depth|level|lvl|tier|nesting|generation|[a-z]\\w*(?:Depth|Level)|\\w+_(?:DEPTH|LEVEL))";

function scan(root, files, rules) {
  const out = [];
  for (const f of files) {
    const raw = read(root, f); const lines = raw.split("\n"); const code = stripComments(raw);
    for (const r of rules) {
      const re = new RegExp(r.re.source, r.re.flags.includes("g") ? r.re.flags : r.re.flags + "g"); let m;
      while ((m = re.exec(code))) {
        const line = lineOf(code, m.index);
        if (r.when && !r.when(code, m, line, f)) continue;
        if (allowedByPragma(lines, line, r.rule)) continue;
        out.push({ rule: r.rule, file: f, line, message: r.msg, excerpt: lines[line - 1] });
      }
    }
  }
  return out;
}

// ------------------------------------------------------------------------------------------------------------------------------------ 1. hierarchy
const HIERARCHY_RULES = [
  { rule: "ORG-HIERARCHY-LEVEL-INDEX", msg: "a level / depth number is compared with a literal >= 1: a level index must never carry a meaning (the tree is data)",
    re: new RegExp(`\\b${DEPTH}\\b\\s*(?:===|==|!==|!=|<=|>=|<|>)\\s*[1-9]\\d*\\b|\\b[1-9]\\d*\\s*(?:===|==|!==|!=|<=|>=|<|>)\\s*\\b${DEPTH}\\b`, "g"), when: (code, m, line) => !HEADING_LEVEL.test(code.split("\n")[line - 1] ?? "") },
  { rule: "ORG-HIERARCHY-LEVEL-NAME", msg: "level 0 / depth 0 compared in the same statement as a hierarchy word: 'level 0 means company'",
    re: new RegExp(`\\b${DEPTH}\\b\\s*(?:===|==)\\s*0\\b`, "g"),
    when: (code, m, line) => new RegExp(`["'\`][^"'\`\\n]*\\b(?:${HIER})\\b`, "i").test(code.split("\n").slice(Math.max(0, line - 2), line + 1).join(" ")) },
  { rule: "ORG-HIERARCHY-SWITCH", msg: "a switch on a depth / level: one branch per level is a fixed hierarchy",
    re: new RegExp(`switch\\s*\\(\\s*[\\w.]*${DEPTH}\\w*\\s*\\)|when\\s*\\(\\s*[\\w.]*${DEPTH}\\w*\\s*\\)`, "gi") },
  { rule: "ORG-HIERARCHY-MAX-DEPTH", msg: "a fixed maximum depth / number of levels",
    re: /\b(?:MAX|MIN)_?(?:TREE_)?(?:DEPTH|LEVELS?)\b|\bmax(?:Tree)?(?:Depth|Levels?)\b\s*[:=]\s*\d|\b(?:depth|levels?)Limit\b\s*[:=]\s*\d/g },
  { rule: "ORG-HIERARCHY-LEVEL-TABLE", msg: "a table of level names / a lookup of a name by depth",
    re: /\b(?:LEVEL|DEPTH|UNIT_LEVEL|ORG_LEVEL)_?(?:NAMES|LABELS|TITLES)\b\s*[:=]|\b(?:ORG_?LEVELS|LEVELS|HIERARCHY|UNIT_HIERARCHY)\b\s*(?::\s*[\w<>\[\]]+\s*)?=\s*[\[{]|[A-Za-z_]*(?:NAMES|LABELS|LEVELS)\w*\s*\[\s*[\w.]*(?:depth|level)\w*\s*\]/g },
  { rule: "ORG-HIERARCHY-TYPE-NAME", msg: "logic keyed on a hierarchy word (a type is whatever the company defines, never 'department' / 'team' in code)",
    re: new RegExp(`(?:typeId|typeCode|unitType\\w*|type\\??\\.(?:code|name|id)|\\.kind)\\s*(?:===|==|!==|!=)\\s*["'\`](?:${HIER})["'\`]|["'\`](?:${HIER})["'\`]\\s*(?:===|==|!==|!=)\\s*(?:typeId|typeCode|unitType\\w*|type\\??\\.(?:code|name|id)|\\.kind)`, "gi") },
  { rule: "ORG-HIERARCHY-TRANSITIONS", msg: "a hard-coded table of which type may sit under which type (that is the company's data: allowedParentTypeIds)",
    re: new RegExp(`(?:["'\`](?:${HIER})["'\`]|\\b(?:${HIER})\\b)\\s*:\\s*\\[\\s*["'\`](?:${HIER})["'\`]|\\b(?:ALLOWED_(?:PARENTS?|CHILDREN|CHILD_TYPES|TRANSITIONS)|PARENT_TYPES|CHILD_TYPES|TYPE_TRANSITIONS|TYPE_HIERARCHY)\\b\\s*[:=]`, "gi") },
];
export function guardHierarchy(root = REPO) { return scan(root, orgFiles(root), HIERARCHY_RULES); }

// ------------------------------------------------------------------------------------------------------------------------------------ 2. fail-closed
const CONTRACT = (root) => { try { return JSON.parse(read(root, "tests/guards/org-contract.json")); } catch { return null; } };
const ALLOW = (root) => { try { return JSON.parse(read(root, "tests/guards/org-guards.allow.json")).allow ?? []; } catch { return []; } };
export function canonicalPermissions(root) {
  try { const m = /val CANONICAL: Set<String> = setOf\(([\s\S]*?)\)/.exec(read(root, "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt")); return new Set([...(m?.[1] ?? "").matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])); } catch { return new Set(); }
}
const ROUTE_SEG = /["'`]\/(?:[^"'`\n]*\/)?(?:organi[sz]ations?|orgs?|org-units?|organization-units?|unit-types?|org-unit-types?|employees?|employee-profiles?|positions?|grades?)(?:[/?"'`$]|\b)[^"'`\n]*["'`]/g;
export function guardFailClosed(root = REPO) {
  const out = []; const files = orgFiles(root); const contract = CONTRACT(root); const wired = new Set(contract?.wired ?? []);
  if (!contract) out.push({ rule: "ORG-FAIL-CLOSED-LEDGER", file: "tests/guards/org-contract.json", message: "missing: the list of capabilities that are wired to a published C1 contract (empty until H-C1-17)" });
  out.push(...scan(root, files, [
    { rule: "ORG-FAIL-CLOSED-SWALLOW", msg: "an empty catch swallows the error (an OrganizationNotReady would turn into silence / success)", re: /catch\s*(?:\([^)]*\))?\s*\{\s*\}/g },
    { rule: "ORG-FAIL-CLOSED-SWALLOW", msg: "a .catch() that turns a failure into a value", re: /\.catch\(\s*(?:\(\s*\w*\s*\)|\w+)\s*=>\s*(?:undefined|null|void 0|true|false|\{\s*\}|\[\s*\]|\(\s*\{\s*\}\s*\)|["'`][^"'`]*["'`]|\d+)\s*\)/g },
    { rule: "ORG-FAIL-CLOSED-PERSIST", msg: "browser persistence in organization code: a fake 'saved' state that survives a reload", re: /\b(?:localStorage|sessionStorage|indexedDB|openDatabase)\b|document\.cookie|window\.name\s*=|\bcaches\.open\b/g },
    { rule: "ORG-FAIL-CLOSED-CLIENT-ID", msg: "an id made in the browser: organization ids come from the server", re: /\brandomUUID\s*\(|\buuid(?:v4)?\s*\(|Math\.random\s*\(/g },
  ]));
  // a guessed route: any organization-ish path literal while NOTHING is wired (and in api-client, where a guessed route would really be added)
  if (wired.size === 0) {
    const where = walk(root).filter((f) => CODE.test(f) && !isNonProduction(f) && (orgFiles(root).includes(f) || /^packages\/api-client\//.test(f) || /^lib\/http-/.test(f)));
    out.push(...scan(root, where, [{ rule: "ORG-FAIL-CLOSED-ROUTE", msg: "a route for organization data while no capability is wired to a published C1 contract (guessed route)", re: ROUTE_SEG }]));
  }
  // an invented permission name: every permission-looking literal must be canonical (backend CANONICAL) or explicitly allow-listed with owner + reason
  const canon = canonicalPermissions(root); const allow = ALLOW(root); const used = new Set();
  const permFiles = [...new Set([...files, ...walk(root).filter((f) => /^packages\/permissions\//.test(f) && CODE.test(f) && !isNonProduction(f))])];
  for (const f of permFiles) {
    const raw = read(root, f); const code = stripComments(raw); const lines = raw.split("\n"); let m;
    const re = /["'`](ORG_[A-Z_]+|(?:UNIT|EMPLOYEE|POSITION|GRADE|ORGANI[SZ]ATION)_(?:MANAGE|VIEW|READ|WRITE|EDIT|MEMBERS|EXECUTE|ASSIGN|MOVE|CREATE|UPDATE|DELETE|ADMIN)|[A-Z]+_(?:MANAGE|VIEW|READ|WRITE|EDIT|MEMBERS|EXECUTE|ASSIGN|MOVE))["'`]/g;
    while ((m = re.exec(code))) {
      const token = m[1]; const line = lineOf(code, m.index);
      if (canon.size && canon.has(token)) continue;
      const a = allow.find((x) => x.file === f && x.token === token);
      if (a) { used.add(`${f}|${token}`); continue; }
      if (allowedByPragma(lines, line, "ORG-FAIL-CLOSED-PERMISSION")) continue;
      out.push({ rule: "ORG-FAIL-CLOSED-PERMISSION", file: f, line, message: `'${token}' looks like a permission but is not in the backend's canonical set (access/Permission.kt CANONICAL): an invented permission name`, excerpt: lines[line - 1] });
    }
  }
  for (const a of allow) {
    if (!a.owner || !a.reason || !a.trigger || !a.outcome) out.push({ rule: "ORG-FAIL-CLOSED-ALLOWLIST", file: "tests/guards/org-guards.allow.json", message: `allow entry ${a.file} ${a.token} needs owner, reason, trigger (when it must be revisited) and outcome (what must happen then)` });
    else if (!used.has(`${a.file}|${a.token}`)) out.push({ rule: "ORG-FAIL-CLOSED-ALLOWLIST", file: "tests/guards/org-guards.allow.json", message: `allow entry ${a.file} ${a.token} is stale (the token is gone): remove it` });
  }
  return out;
}

// ------------------------------------------------------------------------------------------------------------------------------------ 3. relation != permission
const RELATION_SRC = /\b(?:isManager|isHead|isUnitHead|isUnitManager|isLeader|isSupervisor|unitManager|unitHead|managerOf|headOf|relationType|membershipType|unitRole|orgRole|orgRelation|unitRelation|employeeUnitRole|relationKind)\b|["'`](?:MANAGER|HEAD|LEADER|SUPERVISOR|DEPUTY)["'`]|\b(?:relation\w*|membership\w*|unitRole\w*|orgRole\w*)\s*(?:===|==|!==|!=)\s*["'`]MEMBER["'`]|\b(?:Relation\w*|UnitRole|OrgRole|Membership\w*|UnitRelation)\.(?:MANAGER|HEAD|LEADER|MEMBER)\b/g;
const AUTH_SINK = /\b(?:hasPermission|hasRole|can[A-Z]\w*|isAdmin|isSystemAdmin|isTenantAdmin|authori[sz]e\w*|requirePermission|requireRole|grant\w*|allowed?\b|capabilit\w*|permissions?\b|systemAdmin|TENANT_ADMIN|WORKSPACE_ADMIN|SYSTEM_ADMIN|MEMBER_MANAGE|TENANT_MANAGE|TENANT_MEMBERS|APP_\w+|DATA_\w+|Permission\.\w+|PermissionCodes|AccessContext|ctx\.require)\b/;
const REL_TO_ROLE = /["'`]?(?:MANAGER|HEAD|LEADER|SUPERVISOR)["'`]?\s*(?::|=>|->|=)\s*[\[{]?\s*["'`]?(?:\w*_?ADMIN|\w+_MANAGE|\w+_MEMBERS|APP_\w+|DATA_\w+|WORKFLOW_\w+|QUERY_EXECUTE|ACTION_EXECUTE|Permission\.\w+)/g;
const ORG_WORDS_IN_AUTH = /\b(?:organi[sz]ation(?:Unit)?|orgUnit\w*|OrganizationUnit\w*|EmployeePosition|EmployeeOrganizationUnit|positionId|gradeId|unitId)\b/;
export function guardRelationNotPermission(root = REPO) {
  const out = []; const files = productionFiles(root);
  for (const f of files) {
    const raw = read(root, f); const lines = raw.split("\n"); const code = stripComments(raw); const cl = code.split("\n");
    // (a) the relation and an authorization decision in the same statement (a 3-line window: ternaries and && chains wrap)
    for (let i = 0; i < cl.length; i++) {
      RELATION_SRC.lastIndex = 0; if (!RELATION_SRC.test(cl[i])) continue;
      if (/\b(?:HttpMethod|method|verb|request|cors|allowedMethods)\b/i.test(cl[i]) && !/MANAGER|LEADER|isHead|headOf/.test(cl[i])) continue;      // "HEAD" is also an HTTP verb
      const win = cl.slice(Math.max(0, i - 1), i + 2).join(" ");
      if (AUTH_SINK.test(win) && !allowedByPragma(lines, i + 1, "ORG-RELATION")) out.push({ rule: "ORG-RELATION-AUTH", file: f, line: i + 1, message: "an organization relation (manager / head / member of a unit) is used in an authorization decision: structure is not permission", excerpt: lines[i] });
    }
    // (b) a table that maps a relation to a role / permission
    let m; const rb = new RegExp(REL_TO_ROLE.source, "g");
    while ((m = rb.exec(code))) { const line = lineOf(code, m.index); if (!allowedByPragma(lines, line, "ORG-RELATION")) out.push({ rule: "ORG-RELATION-MAP", file: f, line, message: "a relation type is mapped to a role / permission", excerpt: lines[line - 1] }); }
    // (c) the authorization layer itself must not read organization data
    if (/^packages\/permissions\//.test(f) || /^backend\/src\/main\/kotlin\/com\/systemwebstudio\/access\//.test(f)) {
      for (let i = 0; i < cl.length; i++) if (ORG_WORDS_IN_AUTH.test(cl[i]) && !allowedByPragma(lines, i + 1, "ORG-RELATION")) out.push({ rule: "ORG-RELATION-AUTH-LAYER", file: f, line: i + 1, message: "the authorization layer reads organization data (unit / position / grade): organization is metadata, never an input of access control", excerpt: lines[i] });
    }
  }
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const which = process.argv[2];
  const table = { hierarchy: ["ORG-HIERARCHY (no hard-coded hierarchy)", guardHierarchy], "fail-closed": ["ORG-FAIL-CLOSED (NOT_READY stays fail-closed)", guardFailClosed], relation: ["ORG-RELATION (relation is not permission)", guardRelationNotPermission] };
  if (!table[which]) { console.error("usage: node org-source-guards.mjs hierarchy|fail-closed|relation [--root DIR] [--json]"); process.exit(64); }
  cli(table[which][0], table[which][1], process.argv.slice(3));
}
void existsSync; void join;
