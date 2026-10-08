// @class: unit — fixture for the text extractor (tests/builder/text-extract.ts); never executed, only parsed
import { confirm } from "../../../packages/ui/src/dialogs";
import type { Visibility } from "../../../packages/i18n/src/labels";

type Hidden = "Không hiển thị vì là kiểu";
const config = { "khóa-thuộc-tính": 1, ok: "Xóa ứng dụng này?" };
export async function Sample({ v }: { v: Visibility }) {
  const a = await confirm("Xóa shared dialog?");            // the shared dialog: not native
  const b = window.confirm("Native một");                   // native
  const c = prompt("Native hai");                           // native
  const d = `Chưa có ${v} OPENROUTER_API_KEY`;              // template with a leaked constant
  return <div title="Tooltip" aria-label="Nhãn đọc to">Văn bản JSX <b>{a ? b : c}</b>{d}{config.ok}<img alt="" src="x.png"/></div>;
}
export type { Hidden };
