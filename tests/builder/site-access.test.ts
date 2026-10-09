// @class: unit — pure logic / server-side render of components; no browser, no network
import test from "node:test";
import assert from "node:assert/strict";
import { safeSitePath, safeTicketRedirect, webUrl } from "../../features/studio/siteAccessModel";

test("M-051: the site path follows the server's safePath rule (anything odd becomes '/')", () => {
  for (const ok of ["/", "/san-pham", "/a/b?x=1#y", "/tin-tuc/bai-1"]) assert.equal(safeSitePath(ok), ok);
  for (const bad of [null, undefined, "", "  ", "san-pham", "//evil.example", "/\\evil", "/a/../b", "https://evil.example/", "javascript:alert(1)", "/a\nb", "/a\u0000", "/" + "x".repeat(512)]) assert.equal(safeSitePath(bad), "/", String(bad));
});

test("M-051 (R-04): webUrl refuses control characters that the URL parser would drop (\"/\\t/evil.example\" is a protocol-relative URL)", () => {
  for (const bad of ["/\t/evil.example", "/\n/evil.example", "/\r/evil.example", "https://a.example/\u0000", "/a\\b", "//evil.example"]) assert.equal(webUrl(bad), undefined, JSON.stringify(bad));
  assert.equal(webUrl("/assets/a.png"), "/assets/a.png"); assert.equal(webUrl("https://sites.example.vn/x"), "https://sites.example.vn/x");
});

test("M-051: only a plain https ticket link to /_access is followed", () => {
  assert.equal(safeTicketRedirect("https://sites.example.vn/_access?ticket=abc"), "https://sites.example.vn/_access?ticket=abc");
  assert.equal(safeTicketRedirect("http://sites.local:3086/_access?ticket=t1", "http:"), "http://sites.local:3086/_access?ticket=t1", "a local stack: this page is on http too");
  assert.equal(safeTicketRedirect("http://sites.local:3086/_access?ticket=t1", "https:"), null, "a page on https never follows an http redirect, whatever the host");
  for (const bad of [undefined, null, 42, "", "javascript:alert(1)//_access?ticket=a", "data:text/html,<script>1</script>", "//sites.example.vn/_access?ticket=a", "/_access?ticket=a",
    "http://sites.example.vn/_access?ticket=a", "https://u:p@sites.example.vn/_access?ticket=a", "https://sites.example.vn/login?ticket=a", "https://sites.example.vn/_access", "https://sites.example.vn/_access?ticket=",
    "https://sites.example.vn/_access_x?ticket=a", "https://" + "a".repeat(2050)]) assert.equal(safeTicketRedirect(bad), null, String(bad));
});

test("M-051: server URLs put into href/src are http(s) or a same-origin path; script and data URLs are dropped", () => {
  for (const ok of ["https://sites.example.vn/acme/", "http://127.0.0.1:9000/assets/a.png?X-Amz-Signature=1", "/api/v1/assets/a1/download"]) assert.equal(webUrl(ok), ok);
  for (const bad of [null, undefined, "", "javascript:alert(1)", " javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<b>x</b>", "blob:https://x/1", "vbscript:x", "//evil.example/x", "/\\evil.example", "https://u:p@x.vn/", "notaurl"]) assert.equal(webUrl(bad), undefined, String(bad));
});
