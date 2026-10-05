# ADR 0003 — Click-to-select in the preview via postMessage
Status: accepted (2026-10-02)

The preview is `<iframe srcdoc sandbox>`. In **AI mode** the sandbox is empty (no scripts). In **Design mode** (editors only) it is
`sandbox="allow-scripts"` — still no `allow-same-origin`, forms, popups or top navigation — and the renderer injects one inline script
(with the page's CSP nonce, because srcdoc inherits the parent policy) that posts `{type:"studio:select", sectionId}` to the parent.
The parent accepts a message only if `event.source` is that iframe's window and the id exists in the current schema. All page text is
HTML-escaped and links are limited to `#anchors`, so user content cannot add scripts. Viewers always get the script-free sandbox.
This is the only loosening of the sandbox and exists solely for this feature.
