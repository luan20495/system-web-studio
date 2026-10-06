import type { Section } from "@/lib/http-types";
import { typeLabel } from "../features/studio/builder/core/library";

// The section form itself now lives in the Builder (features/studio/builder: Inspector + PropsForm). These two helpers stay here because other screens use them.
export const sectionLabel = typeLabel;

/** Heuristic one-line summary of a section for the outline. */
export function sectionSummary(s: Section): string {
  const p = s.props;
  for (const k of ["title", "heading", "brand", "text", "name"]) if (typeof p[k] === "string" && p[k]) return String(p[k]);
  return "";
}
