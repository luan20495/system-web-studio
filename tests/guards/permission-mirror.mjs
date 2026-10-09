#!/usr/bin/env node
// @class: unit
// Guard 9 (D-C0-51): the permission vocabularies are ONE contract in four places and must not drift:
//   backend  access/Permission.kt          PermissionCodes.CANONICAL            what /auth/me and the role matrix speak (21 codes)
//   backend  app/definition/PermissionCodes.kt  ALL                              the AppDefinition vocabulary C2 validates (14 codes)
//   TS       packages/types/src/contract/v2/permissions.ts  PERMISSION_CODES / PORTAL_PERMISSION_CODES / ORG_PERMISSION_CODES
//   doc      docs/contracts/v2/tenant-permission.md  section 5 (14 codes), section 5b (6 organization codes)
// Rules: PERMISSION-MIRROR-APP (TS 14 == backend ALL == doc section 5) · PERMISSION-MIRROR-ORG (TS six == doc section 5b == the six backend organization codes)
//        PERMISSION-MIRROR-CANONICAL (backend CANONICAL == 14 + MEMBER_MANAGE + six == TS union) · PERMISSION-MIRROR-OBSOLETE (no alias / ORG_MANAGE in any of them)
import { read, report, REPO } from "./lib.mjs";
import { canonicalPermissions } from "./org-source-guards.mjs";

const OBSOLETE = ["ORG_MANAGE", "ORG_VIEW", "ORG_EDIT", "ORG_ADMIN", "ORG_MEMBERS", "EMPLOYEE_ADMIN", "POSITION_MANAGE", "POSITION_VIEW", "GRADE_MANAGE", "GRADE_VIEW", "UNIT_MANAGE", "UNIT_VIEW"];
const ORG6 = ["ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE"];
const tsArray = (src, name) => { const m = new RegExp(`export const ${name}\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*as const`).exec(src); return m ? new Set([...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])) : null; };
const ktList = (src, re) => { const m = re.exec(src); return m ? new Set([...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])) : null; };
const tableCodes = (text) => new Set(text.split("\n").filter((l) => l.startsWith("| `")).flatMap((l) => [...l.split("|")[1].matchAll(/`([A-Z][A-Z_]+)`/g)].map((x) => x[1])));
const diff = (a, b) => [[...a].filter((x) => !b.has(x)), [...b].filter((x) => !a.has(x))];
const eq = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));

export function guardPermissionMirror(root = REPO) {
  const out = []; const add = (rule, file, message) => out.push({ rule, file, line: 1, message, excerpt: "" });
  const tsFile = "packages/types/src/contract/v2/permissions.ts", docFile = "docs/contracts/v2/tenant-permission.md", appKt = "backend/src/main/kotlin/com/systemwebstudio/app/definition/PermissionCodes.kt";
  let ts = "", doc = "", app = ""; try { ts = read(root, tsFile); } catch { add("PERMISSION-MIRROR-APP", tsFile, "the TypeScript permission mirror is missing"); }
  try { doc = read(root, docFile); } catch { add("PERMISSION-MIRROR-APP", docFile, "the canonical contract document is missing"); } try { app = read(root, appKt); } catch { add("PERMISSION-MIRROR-APP", appKt, "app/definition/PermissionCodes.kt is missing"); }
  const tsApp = tsArray(ts, "PERMISSION_CODES"), tsPortal = tsArray(ts, "PORTAL_PERMISSION_CODES"), tsOrg = tsArray(ts, "ORG_PERMISSION_CODES"); const canon = canonicalPermissions(root);
  const beApp = ktList(app, /val ALL: List<String> = listOf\(([\s\S]*?)\)/);
  const sec5 = doc.includes("## 5. Canonical permission vocabulary") ? tableCodes(doc.split("## 5. Canonical permission vocabulary")[1].split("\nRules:")[0]) : null;
  const sec5b = doc.includes("## 5b.") ? tableCodes(doc.split("## 5b.")[1].split("\n## 5c.")[0]) : null;
  if (!tsApp || !tsPortal || !tsOrg) add("PERMISSION-MIRROR-APP", tsFile, "PERMISSION_CODES / PORTAL_PERMISSION_CODES / ORG_PERMISSION_CODES must all be exported `as const` arrays");
  if (!beApp) add("PERMISSION-MIRROR-APP", appKt, "cannot read PermissionCodes.ALL");
  if (!canon.size) add("PERMISSION-MIRROR-CANONICAL", "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt", "cannot read PermissionCodes.CANONICAL");
  if (!sec5) add("PERMISSION-MIRROR-APP", docFile, "section 5 table not found"); if (!sec5b) add("PERMISSION-MIRROR-ORG", docFile, "section 5b (organization permissions) not found");
  if (out.length) return out;
  const cmp = (rule, file, name, a, b) => { if (!eq(a, b)) { const [x, y] = diff(a, b); add(rule, file, `${name}: only on the left [${x.join(", ")}] / only on the right [${y.join(", ")}]`); } };
  cmp("PERMISSION-MIRROR-APP", tsFile, "TS PERMISSION_CODES vs backend app/definition PermissionCodes.ALL", tsApp, beApp);
  cmp("PERMISSION-MIRROR-APP", docFile, "document section 5 vs backend app/definition PermissionCodes.ALL", sec5, beApp);
  cmp("PERMISSION-MIRROR-ORG", tsFile, "TS ORG_PERMISSION_CODES vs the six canonical organization codes", tsOrg, new Set(ORG6));
  cmp("PERMISSION-MIRROR-ORG", docFile, "document section 5b vs the six canonical organization codes", sec5b, new Set(ORG6));
  const union = new Set([...tsApp, ...tsPortal, ...tsOrg]);
  cmp("PERMISSION-MIRROR-CANONICAL", tsFile, "TS union (PERMISSION_CODES + PORTAL + ORG) vs backend access PermissionCodes.CANONICAL", union, canon);
  cmp("PERMISSION-MIRROR-CANONICAL", "backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt", "backend CANONICAL vs the 14 AppDefinition codes + MEMBER_MANAGE + the six organization codes", canon, new Set([...beApp, "MEMBER_MANAGE", ...ORG6]));
  for (const [file, set] of [[tsFile, union], [docFile, new Set([...sec5, ...sec5b])], ["backend/src/main/kotlin/com/systemwebstudio/access/Permission.kt", canon], [appKt, beApp]]) for (const o of OBSOLETE) if (set.has(o)) add("PERMISSION-MIRROR-OBSOLETE", file, `'${o}' is obsolete / not canonical and must not be in a permission vocabulary`);
  return out;
}
if (import.meta.url === `file://${process.argv[1]}`) { const a = process.argv.slice(2); const ri = a.indexOf("--root"); process.exit(report("PERMISSION-MIRROR (TS == backend == contract document)", guardPermissionMirror(ri >= 0 ? a[ri + 1] : REPO), { json: a.includes("--json") })); }
