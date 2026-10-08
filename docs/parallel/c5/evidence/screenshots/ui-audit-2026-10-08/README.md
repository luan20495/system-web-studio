# Screenshots — UI audit 2026-10-08 (real Chromium, real stack integration ae0432f, nothing mocked)

`platform/`, `admin/`, `studio/` (home, projects, project panels) come from `scripts/ui-audit.mjs` (final run, 1440 × 900 and 390 × 800, full page on the phone width). `studio/builder-*.png`, `studio-publish-*.png` come from `scripts/ui-studio-shots.mjs` (the builder rail panels Trang / Dữ liệu / Hành động / Workflow / Giao diện, the test mode and the publish drawer), taken on the build that includes the top-bar fix. `dialogs/create-company-*.png` is the real "Tạo công ty" dialog (`scripts/ui-dialog-check.mjs`).
Data: a generated tenant "Công ty Cổ phần Ánh Dương", 25 employees with long Vietnamese names, one project. Admin organization screens show the NOT_READY panel (the organization contract does not exist yet); the organization tree with data is in `../../ui-hardening/2026-10-08/harness/` (harness fixtures, labelled as such).
Use as the visual-regression baseline for C6.
