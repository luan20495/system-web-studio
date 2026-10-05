# CLAUDE.md — XWeb / System Web Studio

Nền tảng nội bộ "AI Software Factory": **Modular Monolith** Kotlin/Spring Boot (`backend/`, package `com.systemwebstudio`) + Next.js ở root repo (`app/ features/ components/ lib/`), PostgreSQL (Flyway), Redis, MinIO, RabbitMQ. Tổng quan: `README.md`, `docs/ARCHITECTURE.md`, `docs/IMPLEMENTATION_STATUS.md`, `docs/adr/`.

## Quy tắc bắt buộc (phát triển song song)
- Modular Monolith. **Không** tự chuyển microservices.
- **Đọc `docs/parallel/OWNERSHIP.md` trước khi sửa code.** Biết mình là C1–C5 nào (theo branch `agent/cN-*`).
- **Không sửa file của owner khác** (đặc biệt HOT FILES). Cần thay đổi → ghi `docs/parallel/BLOCKERS.md` / cột Depends ở `BOARD.md`.
- **Chỉ C0 được cấp số Flyway migration.** Không tự chọn số, không tự tạo migration: ghi request trong `docs/parallel/BOARD.md` (mục *Migration requests*) hoặc `BLOCKERS.md`, chờ C0 cấp version tiếp theo (V26, V27, …; một version thuộc một task). Integration branch merge migration theo thứ tự tăng dần. **Không bật `outOfOrder=true`.** Không sửa migration đã tồn tại.
- **Không đổi contract chung** (`docs/contracts/**`) nếu chưa ghi `docs/parallel/DECISIONS.md`.
- Giữ **backward compatibility**: API hiện tại, bảng `projects`, Page Schema, `STATIC_APP`. **Không xóa feature cũ** để làm V2.
- Bất biến phải bảo toàn: Page Schema + `SchemaPatchEngine` + `PageSchemaValidator`; component registry/version; immutable project versions; AI Gateway; Connector Proxy + SSRF guard + credential chỉ ở server; Audit append-only; publish pipeline; render/build/runtime planes.
- Luồng dữ liệu: UI → AppDefinition/ViewModel → Query|Action → Auth+Permission → Data Gateway → Connector → hệ thống ngoài. AI: Prompt → Structured Operation → AppDefinition → Validator → Version (AI không có model dữ liệu riêng).
- **Không merge main.** Không `push --force`, `reset --hard`, `clean -fd`. Không thêm GitHub Actions.
- Mỗi task: **test và commit riêng**.
- **Report cuối task**: files changed / tests / blockers / commit SHA.

## Lệnh thường dùng
```bash
cd backend && ./gradlew test          # Testcontainers, JDK 21 (cần Docker)
npx tsc --noEmit -p tsconfig.json     # typecheck UI
npm run build                         # next build
node e2e/factory-flow.mjs             # E2E (cần stack chạy)
```
Worktree: xem `docs/parallel/WORKTREE_SETUP.md`. Bảng điều phối: `docs/parallel/BOARD.md`.
