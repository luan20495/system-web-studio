# WORKTREE_SETUP — mỗi Claude một git worktree

Repo gốc (C0): `/Users/hoangluan/code/HBL`. Worktree nằm cạnh repo, tên thư mục `xweb-c1…c5`.
Chỉ lệnh **không phá hủy**. Không merge main. Không GitHub Actions. Không `reset --hard`, `clean -fd`, `push --force`, `worktree remove --force`.

## 0. Điều kiện trước
Commit Phase 0 đã nằm trên nhánh nền (branch hiện tại của C0). Worktree phải tách từ **commit Phase 0** để cả 5 agent cùng có `CLAUDE.md`, `docs/parallel/**`, `docs/contracts/**`.

```bash
cd /Users/hoangluan/code/HBL
git status --short            # phải sạch
BASE=$(git rev-parse HEAD)    # commit Phase 0
echo "$BASE"
```

## 1. Tạo worktree (từ BASE, branch mới)

```bash
cd /Users/hoangluan/code/HBL
git worktree add -b agent/c1-tenancy   ../xweb-c1 "$BASE"
git worktree add -b agent/c2-app-model ../xweb-c2 "$BASE"
git worktree add -b agent/c3-data      ../xweb-c3 "$BASE"
git worktree add -b agent/c4-workflow  ../xweb-c4 "$BASE"
git worktree add -b agent/c5-web       ../xweb-c5 "$BASE"
git worktree list
```

`git worktree add -b` thất bại nếu branch/thư mục đã tồn tại — đó là hành vi an toàn mong muốn; không dùng `-B`/`--force`.

## 2. Cài dependency trong mỗi worktree
`node_modules/`, `backend/build/`, `.env` bị ignore nên không có sẵn trong worktree.

```bash
for n in 1 2 3 4 5; do
  (cd ../xweb-c$n && cp ../HBL/.env .env && npm ci)
done
# Backend: gradle tự tải; kiểm tra nhanh
(cd ../xweb-c1/backend && ./gradlew --version)
```
(`.env` chứa secret: chỉ copy cục bộ, không commit.)

## 3. Mỗi agent làm việc
```bash
cd ../xweb-cN
cat CLAUDE.md docs/parallel/OWNERSHIP.md
git branch --show-current          # phải đúng agent/cN-...
# làm task → test → commit riêng
```
Cổng/stack local dùng chung (`./scripts/run-local.sh`, cổng 3100, Docker) dễ xung đột giữa worktree: chỉ chạy một stack tại một thời điểm, hoặc dùng test Testcontainers (`./gradlew test`) cô lập.

## 4. Cập nhật từ nhánh nền (C0 làm, không phải agent)
C0 cherry-pick/merge nhánh agent vào nhánh tích hợp. Agent **không** merge main. Muốn lấy thay đổi contract mới: C0 báo SHA, agent chạy `git cherry-pick <sha>`.

## 5. Dọn dẹp (khi xong, không bắt buộc)
```bash
cd /Users/hoangluan/code/HBL
git worktree remove ../xweb-c1   # chỉ khi sạch; lệnh từ chối nếu còn thay đổi
git worktree list
```
