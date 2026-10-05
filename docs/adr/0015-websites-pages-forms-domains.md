# ADR 0015: Multi-page websites, forms, custom domains and CDN caching
Status: accepted and implemented (2026-10-03). Stage G. Extends ADR 0009 (static sites).

## Multi-page sites
The page schema stays backward compatible: the root `sections` are the home page; `pages[]` holds the other pages
(`id`, `slug`, `title`, `seo{title,description,noindex}`, `sections`); `site` holds `title`, `home{title,seo}`, `navigation[]` and
`notFound{title,message}`. Section ids are unique across the whole site, so every existing section operation keeps working; `ADD_SECTION`
takes a `pageId`. New operations: `ADD_PAGE`, `UPDATE_PAGE`, `REMOVE_PAGE` (also removes navigation links to it), `SET_NAVIGATION`,
`UPDATE_SITE`. Limits: 20 pages, 12 links, slug `^[a-z0-9]+(-[a-z0-9]+)*$` (reserved: assets, api, _forms, _app, …).

A publish renders every page from ONE schema version (`/render-site` of the render worker) into one content-addressed artifact:
`index.html`, `<slug>/index.html`, `404.html` — an atomic snapshot; rollback restores all pages together. The server maps directory URLs
(`/about/` → `about/index.html`, `/about` → 301 `about/`) and answers unknown paths with the site's own 404 page; its "home" link is a fixed
token replaced by the site root computed by the server after the bytes passed the manifest integrity check.

**Navigation links** are one of: a page of the site (rendered as a relative URL, works on the gateway path and on a custom domain), an
anchor `#id`, or a plain `https://` URL on a host approved by an admin (`site.external-link-domains`). `javascript:`, `data:`, `http:`,
user-info URLs and unknown pages are rejected. The approved-host list is enforced when a link is added and again at publish time, not on
every save, so removing a host from the list does not block editing.

## Forms
A `ContactForm` on a published website is a plain HTML form (no script; CSP `form-action 'self'`) posting to `<site>/_forms/<sectionId>`.
The gateway accepts only that POST (urlencoded, ≤16 KiB, 6/min per IP); the API accepts it only for a live PUBLIC website whose current
deployment contains that ContactForm, checks `Origin`, validates every field (name, email, phone, message), drops spam silently (honeypot
field, link flood), rate-limits per visitor (5/10 min) and per site (200/h), and stores the submission with the visitor IP as a salted
SHA-256 hash only. Submissions are personal data: visible, exportable (CSV, formula-injection safe) and deletable by project editors only,
audited, and deleted by the cleanup job after `retention.form-submission-days`.

## Custom domains
A publisher adds a host name; ownership is proven by a DNS TXT record `_hbl-verify.<host>` = a random token (no DNS credentials are asked
for or stored). The gateway serves any Host that is not the company sites host from `/sites/_host/<path>`; the API serves it only for a
VERIFIED domain of a live **public** website (private sites keep the company sites host, where their session cookie lives). TLS is
terminated in front of the gateway (CDN or tunnel); `tls_status` is the result of a real HTTPS request with JDK certificate and host-name
validation, never assumed. Platform hosts cannot be claimed; a host belongs to one website; at most 5 per website.

**Public pilot:** serving a custom domain additionally needs the owner's DNS and a tunnel/CDN route for that host — that is infrastructure
outside this repository (status: implemented and tested locally through the gateway; not live on the pilot until a domain is routed).

## CDN caching
Pages: `public, no-cache` (revalidate with ETag every time) so unpublish, rollback and visibility changes apply immediately. Files under
`assets/` are addressed by an asset id whose bytes never change: `public, max-age=31536000, immutable` (public sites only). Private sites:
`private, no-store`. The gateway's nginx cache and any CDN in front follow these headers; no purge API is needed.
