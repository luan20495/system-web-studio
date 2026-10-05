# BASELINE — trạng thái trước khi phát triển song song

Ghi nhận ngày 2026-10-05. **Không sửa gì để làm test xanh.** Mục nào không chạy được ghi rõ lý do; không suy diễn là pass.

## Git
- Branch hiện tại: `feat/production-hardening`
- Commit nền: `b944f892290b5e4a2575731501f0f3e7351be7c4` — "docs: architecture assessment (current system as built, gaps, V2 modular-monolith proposal, roadmap and task list)"
- Working tree sạch trước Phase 0. `main` cục bộ = `origin/main` = `da348ff…` (cũ hơn nhánh hiện tại); commit Phase 0 nằm trên `feat/production-hardening`.
- Không có worktree phụ nào trước Phase 0.

## Cấu trúc đã đối chiếu
Backend `backend/` (Spring Boot 4.1.1, Kotlin 2.2.21, JDK 21, Jackson 3 `tools.jackson`), 19 module Kotlin; Flyway **V1–V25** (V25 = `ai_providers_limits`); frontend ở **root repo** (Next.js 16.3.8, React 19.2); E2E là 8 script `e2e/*.mjs`.

## Kết quả

| Hạng mục | Kết quả | Ghi chú |
|---|---|---|
| Frontend typecheck `npx tsc --noEmit -p tsconfig.json` | **PASS** (0 lỗi) | chạy trong Phase 0, ~7 s |
| Frontend build `next build` | **KHÔNG CHẠY ĐƯỢC** | Môi trường chạy lệnh là Linux VM arm64; `node_modules` từ macOS không có `@next/swc-linux-arm64-gnu` và VM không có mạng (`EAI_AGAIN registry.npmjs.org`). Không phải lỗi repo. Cần chạy trên macOS: `npm run build`. |
| Backend `./gradlew test` | **KHÔNG CHẠY ĐƯỢC** | VM chỉ có JDK 11 (cần 21), không có Docker (Testcontainers cần Postgres/Redis/RabbitMQ/MinIO), không có Gradle cache. Cần chạy trên macOS. |
| Backend — kết quả test gần nhất có sẵn trên đĩa (`backend/build/test-results/test`, sinh 2026-10-05 04:00 UTC trên máy dev, **không phải do Phase 0 chạy**) | 44 suites · **192 tests · 0 failures · 0 errors · 3 skipped** | 3 skipped là các test live (ví dụ `OpenRouterLiveTests` bị skip theo thiết kế). README ghi "185 tests" → đã lệch, tài liệu cũ. |
| E2E (`e2e/*.mjs`: factory, code, runtime, a11y, public, sso, admin-setup) | **KHÔNG CHẠY** | Cần full stack Docker + trình duyệt; ngoài khả năng môi trường Phase 0. `docs/IMPLEMENTATION_STATUS.md` ghi kết quả lần chạy trước (vd factory/code/runtime/sso/public/admin-setup flows). |
| Secret scan `scripts/secret-scan.sh` | KHÔNG CHẠY | cần gitleaks |

## Lỗi tồn tại trước
- Không phát hiện test thất bại trong dữ liệu có sẵn.
- Tài liệu lệch: README "185 tests" so với 192 thực tế; `docs/ARCHITECTURE.md` ghi "Flyway V1–V5" trong khi thực tế tới V25.

## Việc C0 cần làm trước khi C1–C5 merge (đề xuất)
Chạy trên macOS, ghi kết quả vào mục "Re-baseline" bên dưới: `cd backend && ./gradlew test`, `npm run build`, và ít nhất `node e2e/factory-flow.mjs`.

### Re-baseline (macOS) — chưa có
