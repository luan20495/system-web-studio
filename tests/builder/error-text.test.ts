// @class: unit — pure logic / server-side render of components; no browser, no network
/** M-075: the one error mapper. A table of (input → sentence) so a regression in wording or in the "never print Error.message" rule fails here. */
import test from "node:test";
import assert from "node:assert/strict";
import { ApiError } from "../../packages/api-client/src/core";
import { errorParts, errorText, ERROR_CODE_TEXT, GENERIC_ERROR, isVietnamese, reportClientError, setErrorReporter } from "../../packages/api-client/src/errorText";

const api = (status: number, code: string, message = "English message from the server", requestId?: string, extra: Partial<{ retryable: boolean; retryAfterSeconds: number }> = {}) =>
  new ApiError(status, code, message, requestId, undefined, extra.retryable, extra.retryAfterSeconds);

test("a non-ApiError never shows its own message (implementation detail), only the caller's fallback or the generic sentence", () => {
  assert.equal(errorText(new SyntaxError("Unexpected token '<', \"<!DOCTYPE \"... is not valid JSON"), "Không tải được."), "Không tải được.");
  assert.equal(errorText(new TypeError("Failed to fetch")), GENERIC_ERROR);
  assert.equal(errorText(new Error("ECONNRESET at socket.js:12")), GENERIC_ERROR);
  assert.equal(errorText("a plain string", "Thất bại."), "Thất bại.");
  assert.equal(errorText(undefined), GENERIC_ERROR);
  assert.equal(errorText(null, "X"), "X");
  assert.equal(errorParts(new Error("boom")).kind, "unknown");
});

test("the table: (status, code) → Vietnamese sentence, kind and retryability", () => {
  const rows: Array<[ApiError, RegExp, string, boolean]> = [
    [api(0, "NETWORK", "x"), /^Không kết nối được tới máy chủ\. Kiểm tra mạng/, "network", true],
    [api(0, "TIMEOUT", "x"), /không phản hồi kịp/, "timeout", true],
    [api(0, "ABORTED", "x"), /^Đã huỷ yêu cầu\.$/, "aborted", false],
    [api(0, "STREAM_ENDED", "x"), /ngắt trước khi có kết quả/, "network", true],
    [api(401, "AUTHENTICATION_REQUIRED"), /hết hạn.*Đăng nhập lại/, "auth", false],
    [api(401, "INVALID_CREDENTIALS"), /^Sai tên đăng nhập hoặc mật khẩu\.$/, "auth", false],
    [api(403, "FORBIDDEN"), /không có quyền/, "forbidden", false],
    [api(403, "CSRF_INVALID", "Missing or invalid CSRF token"), /Tải lại trang/, "forbidden", false],
    [api(404, "PROJECT_NOT_FOUND"), /Không tìm thấy/, "notfound", false],
    [api(404, "SOME_NEW_THING_NOT_FOUND"), /Không tìm thấy/, "notfound", false],
    [api(409, "REVISION_CONFLICT"), /thay đổi ở nơi khác/, "conflict", false],
    [api(409, "IDEMPOTENCY_OUTCOME_UNKNOWN"), /Chưa biết thao tác.*Kiểm tra dữ liệu/, "conflict", false],
    [api(400, "VALIDATION_FAILED", "Request validation failed"), /chưa hợp lệ/, "validation", false],
    [api(400, "WEAK_PASSWORD", "Password is too repetitive"), /tối thiểu 8 ký tự/, "validation", false],
    [api(429, "RATE_LIMITED", "x", undefined, { retryAfterSeconds: 7 }), /Thử lại sau 7 giây/, "ratelimit", true],
    [api(429, "RATE_LIMITED"), /Thử lại sau ít phút/, "ratelimit", true],
    [api(503, "SCOPE_BUSY"), /đang bận/, "busy", true],
    [api(503, "LOGIN_BUSY"), /đang bận/, "busy", true],
    [api(500, "INTERNAL_ERROR", "Unexpected server error"), /Máy chủ gặp lỗi/, "server", true],
    [api(502, "HTTP_502", "Lỗi 502"), /Máy chủ gặp lỗi/, "server", true],
    [api(400, "HTTP_400", "Lỗi 400"), /chưa hợp lệ/, "validation", false],
    [api(404, "HTTP_404", "Lỗi 404"), /Không tìm thấy/, "notfound", false],
    [api(422, "SOMETHING_NEW"), /chưa hợp lệ/, "validation", false],
    [api(409, "AI_BUDGET_EXCEEDED"), /ngân sách AI/, "conflict", false],
    [api(409, "USERNAME_TAKEN"), /Tên đăng nhập đã được dùng/, "conflict", false],
    [api(400, "INVALID_SOMETHING"), /^Giá trị không hợp lệ\.$/, "validation", false],
    [api(409, "NOT_SUBMITTABLE"), /không áp dụng được/, "conflict", false],
  ];
  for (const [e, re, kind, retry] of rows) {
    const p = errorParts(e);
    assert.match(p.message, re, `${e.status} ${e.code}`);
    assert.equal(p.kind, kind, `${e.status} ${e.code} kind`);
    assert.equal(p.retryable, retry, `${e.status} ${e.code} retryable`);
  }
});

test("the server's English message is not shown; a Vietnamese one written for people is (specific beats generic) except for session/permission/server failures", () => {
  assert.doesNotMatch(errorText(api(400, "VALIDATION_FAILED", "displayName is required (max 160 characters)")), /displayName/);
  assert.equal(errorText(api(422, "MUTATION_REJECTED", "Giá phải >= 0")), "Giá phải >= 0");
  assert.equal(errorText(api(400, "INVALID_VALUE", "Mã mô hình không hợp lệ")), "Mã mô hình không hợp lệ");
  assert.doesNotMatch(errorText(api(403, "FORBIDDEN", "Bạn không được phép làm điều đó ở đây")), /ở đây/); // 403 stays on the table
  assert.doesNotMatch(errorText(api(500, "INTERNAL_ERROR", "Lỗi máy chủ nội bộ stack Foo.kt:12")), /Foo\.kt/);
  assert.equal(isVietnamese("Giá phải >= 0"), true);
  assert.equal(isVietnamese("Request validation failed"), false);
});

test("an unknown code with a Vietnamese message shows that message; with an English one falls back to the status class", () => {
  assert.equal(errorText(api(409, "BRAND_NEW_CODE", "Mục này đang được dùng ở nơi khác.")), "Mục này đang được dùng ở nơi khác.");
  assert.match(errorText(api(409, "BRAND_NEW_CODE", "Resource is busy")), /thay đổi ở nơi khác/);
});

test("the reference code: shown for server errors and for codes the table does not know, hidden for ordinary refusals", () => {
  assert.match(errorText(api(500, "INTERNAL_ERROR", "x", "req-9")), /\(Mã tham chiếu: req-9\)$/);
  assert.match(errorText(api(409, "BRAND_NEW_CODE", "x", "req-10")), /\(Mã tham chiếu: req-10\)$/);
  assert.doesNotMatch(errorText(api(404, "PROJECT_NOT_FOUND", "x", "req-11")), /req-11/);
  assert.equal(errorParts(api(404, "PROJECT_NOT_FOUND", "x", "req-11")).reference, undefined);
  assert.equal(errorParts(api(500, "INTERNAL_ERROR", "x", "req-9")).reference, "req-9");
});

test("no wording a person should not read: 'backend', 'CSRF', 'JSON', exception, English", () => {
  const all = [...Object.values(ERROR_CODE_TEXT), GENERIC_ERROR];
  for (const t of all) {
    assert.doesNotMatch(t, /backend|csrf|\bjson\b|exception|undefined|null/i, t);
    assert.match(t, /[a-zàáảãạăâêôơưđ]/i);
  }
  // every sentence is Vietnamese (carries a tone mark) or a very short one that cannot be mistaken for English
  for (const [code, t] of Object.entries(ERROR_CODE_TEXT)) assert.ok(isVietnamese(t) || t.length < 30, `${code}: ${t}`);
});

test("plain objects with {status, code} (what other layers build) are read like an ApiError", () => {
  assert.match(errorText({ status: 404, code: "PROJECT_NOT_FOUND" }), /Không tìm thấy/);
  assert.equal(errorParts({ status: 409, code: "X", retryable: true }).retryable, true);
});

test("error reporter seam: boundary errors reach it with the reference; a throwing reporter never breaks the app; default is a no-op", () => {
  const seen: unknown[] = [];
  reportClientError(new Error("nobody listens"), "x"); // no reporter: no throw
  setErrorReporter((r) => seen.push(r));
  reportClientError(api(500, "INTERNAL_ERROR", "x", "req-1"), "ErrorBoundary");
  setErrorReporter(() => { throw new Error("reporter bug"); });
  assert.doesNotThrow(() => reportClientError(new Error("e"), "x"));
  setErrorReporter(null);
  assert.equal((seen[0] as { reference: string }).reference, "req-1");
  assert.equal((seen[0] as { where: string }).where, "ErrorBoundary");
});
