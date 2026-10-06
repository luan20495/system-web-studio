// Runtime values of the v2 contract mirror (packages/types). Core modules import VALUES from here (relative path, so the compiled tests run under
// plain node) and TYPES with `import type … from "@xweb/types"` (erased at compile time).
export * from "../../../../packages/types/src/contract/v2";
