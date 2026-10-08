// @class: harness — test-only: the CSS set of the Platform / Admin apps for the browser-harness pages (NOT part of any shipped bundle).
// The CSS set of the Platform / Admin apps, in the order of apps/admin/app/layout.tsx and apps/platform/app/layout.tsx (NOT builder.css).
// tests/builder/shared-ui.test.ts checks that the real layouts import exactly this list, so a harness page here is the real thing, not a look-alike.
import "../../packages/ui/src/styles/globals.css";
import "../../packages/ui/src/styles/responsive.css";
import "../../packages/ui/src/styles/http.css";
import "../../packages/ui/src/styles/factory.css";
import "../../packages/ui/src/styles/ui.css";
