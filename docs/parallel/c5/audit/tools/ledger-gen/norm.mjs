import fs from "node:fs";
const rows = JSON.parse(fs.readFileSync("/tmp/ledger/ledger.json", "utf8"));
export const classify = (r) => {
  const [id, sev, cls, , , , , , owner, be, , , , , st0] = r; const st = (st0 || "").trim();
  if (cls === "RESEARCH") return "RESEARCH_ONLY";
  if (/^BLOCKED/.test(st)) return "BLOCKED";
  if (/^ACCEPTED/.test(st)) return "ACCEPTED_LIMITATION";
  if (/^OPEN/.test(st)) return "OPEN";
  if (/^CLOSED/.test(st)) return /NOT A DEFECT|DETECTOR|HARNESS ARTEFACT/.test(st) ? "CLOSED" : "CLOSED";
  if (/^FIXED/.test(st)) return "FIXED";
  if (/^(PARTIAL|MOSTLY FIXED|CLIENT HALF|SHELL ADOPTED|COMPONENT MERGED|LIBRARY MERGED|SHARED COPY FIXED|ADMIN PART|C5 PART|ADMIN \+ STUDIO|GUARD|BRAND|VOCABULARY|PHASE|RTL|DATA|PROVISIONAL)/.test(st)) return "PARTIAL";
  if (cls === "RESEARCH") return "RESEARCH_ONLY";
  if (st === "" ) return "OPEN";
  return "NEEDS_REVIEW";
};
if (process.argv[1].endsWith("norm.mjs")) {
  const by = {}; const nr = [];
  for (const r of rows) { const c = classify(r); (by[c] ||= []).push(r[0]); if (c === "NEEDS_REVIEW") nr.push(r[0] + " | " + (r[14] || "").slice(0, 110)); }
  for (const [k, v] of Object.entries(by)) console.log(k, v.length);
  console.log(nr.join("\n"));
  const blank = rows.filter((r) => !(r[14] || "").trim()).map((r) => r[0] + " " + r[1] + " " + r[2] + " " + r[8].slice(0, 25));
  console.log("blank status:", blank.length); console.log(blank.join("\n"));
}
