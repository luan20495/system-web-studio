import type { AppDefinitionV2, Section } from "@xweb/types";

export const sec = (id: string, type: string, props: Record<string, unknown> = {}): Section => ({ id, type, props } as Section);

export function doc(extra: Partial<AppDefinitionV2> = {}): AppDefinitionV2 {
  return {
    page: "Trang chủ",
    sections: [sec("s-hero", "Hero", { title: "Xin chào" }), sec("s-text", "TextBlock", { body: "x" }), sec("s-foot", "Footer", {})],
    pages: [],
    ...extra,
  } as AppDefinitionV2;
}
