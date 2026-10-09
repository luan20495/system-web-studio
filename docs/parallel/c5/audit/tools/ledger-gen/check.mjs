import fs from "node:fs"; import { ROWS } from "./patch.mjs";
const raw = JSON.parse(fs.readFileSync("issues.json","utf8"));
const all = new Set(raw.map(r=>r.id)); const seen = new Map(); const dupe=[]; 
for (const r of ROWS) for (const s of r[6]) { if (seen.has(s)) dupe.push(`${s} in ${seen.get(s)} and ${r[0]}`); seen.set(s, r[0]); if(!all.has(s)) console.log("UNKNOWN", s, "in", r[0]); }
const missing=[...all].filter(i=>!seen.has(i)); console.log("raw",all.size,"mapped",seen.size,"canonical",ROWS.length); console.log("MISSING",missing.join(" ")); console.log("DUPES",dupe.join("; "));
const ids=ROWS.map(r=>r[0]); console.log("dup canon ids", ids.filter((x,i)=>ids.indexOf(x)!==i));
const by={}; for(const r of ROWS){by[r[1]]=(by[r[1]]||0)+1} console.log(by); const cl={}; for(const r of ROWS){cl[r[2]]=(cl[r[2]]||0)+1} console.log(cl);
