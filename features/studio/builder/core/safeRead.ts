/**
 * Safe READS of stored app-definition shapes (no imports of the editor modules, so definition.ts and dataFlow.ts can both use them without a cycle).
 * A mapping field stored without `transforms` (undefined / null), with the LEGACY `transform` (one object or an array) or with garbage reads as a list; a mapping / ViewModel without `fields` reads as having none.
 * Nothing is written back from here: an untouched definition keeps exactly what the server stored; an edited field is rebuilt with the canonical `transforms[]` (and without the legacy `transform`).
 */
import type { FieldMappingDef, TransformDef, ViewModelFieldDef } from "@xweb/types";

export function transformsOf(f: { transforms?: unknown; transform?: unknown } | null | undefined): TransformDef[] {
  const src = f?.transforms !== undefined && f?.transforms !== null ? f.transforms : f?.transform;
  if (Array.isArray(src)) return src.filter((t): t is TransformDef => !!t && typeof t === "object" && typeof (t as TransformDef).type === "string");
  return src && typeof src === "object" && typeof (src as TransformDef).type === "string" ? [src as TransformDef] : [];
}
/** the fields of a stored mapping; a mapping without `fields` reads as having none */
export const fieldsOf = (m: { fields?: unknown } | null | undefined): FieldMappingDef[] => (Array.isArray(m?.fields) ? (m!.fields as FieldMappingDef[]).filter((f) => !!f && typeof f === "object") : []);
/** the fields of a stored ViewModel; a ViewModel without `fields` reads as having none */
export const vmFieldsOf = (v: { fields?: unknown } | null | undefined): ViewModelFieldDef[] => (Array.isArray(v?.fields) ? (v!.fields as ViewModelFieldDef[]).filter((f) => !!f && typeof f === "object") : []);
/** a field with the given canonical `transforms[]` — the legacy `transform` is dropped (both on one field is rejected by the server, app-definition §3) */
export function withTransforms(f: FieldMappingDef, list: TransformDef[]): FieldMappingDef {
  const { transform: _legacy, ...rest } = f as FieldMappingDef & { transform?: unknown };
  return { ...rest, transforms: list };
}
