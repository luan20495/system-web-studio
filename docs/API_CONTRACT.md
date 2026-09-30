# API Contract

This document defines the minimum backend contract required by the current frontend.

All JSON examples match the TypeScript domain models in `lib/types.ts`.

## 1. Load studio

### GET /v1/studio

Returns the active project, page data, AI conversation and version history.

```json
{
  "project": {
    "id": "web_00042",
    "name": "Water Purifier Website",
    "owner": "hoang.luan",
    "branch": "main",
    "visibility": "private",
    "framework": "Next.js + React",
    "authMode": "sso",
    "domain": "web42.apps.company.vn",
    "deploymentMode": "auto",
    "deploymentTarget": "self-host"
  },
  "content": {
    "heroEyebrow": "Pure living • smart water",
    "heroTitle": "Nước sạch mỗi ngày, sống khỏe mỗi ngày.",
    "heroDescription": "…",
    "products": [],
    "testimonials": [],
    "showTestimonials": true,
    "showComparison": false
  },
  "messages": [],
  "versions": [],
  "registryReuse": 94
}
```

## 2. Apply AI prompt

### POST /v1/projects/:projectId/prompts

Request:

```json
{
  "prompt": "Thêm bảng so sánh 3 sản phẩm trước phần đánh giá"
}
```

Response:

```json
{
  "content": {},
  "message": {
    "id": "m_100",
    "role": "assistant",
    "content": "Đã cập nhật website.",
    "meta": ["schema patched", "preview updated"]
  },
  "version": {
    "id": "v_100",
    "label": "v6",
    "createdAt": "2026-09-30T08:00:00Z",
    "summary": "Add comparison block",
    "commitSha": "abc1234"
  },
  "registryReuse": 96
}
```

The backend can later implement the real pipeline:

```text
Prompt
  → AI Planner
  → Registry Search
  → Schema Patch
  → Validation
  → Source Sync
  → Git Commit
  → Response
```

## 3. Update project settings

### PUT /v1/projects/:projectId

Body is the full `Project` object.

Response is the saved `Project`.

Typical backend responsibilities:

- validate project ownership
- validate domains
- persist visibility/auth settings
- store deployment preferences
- enforce tenant permissions

## 4. Publish project

### POST /v1/projects/:projectId/publish

Request:

```json
{
  "visibility": "private"
}
```

Response:

```json
{
  "status": "success",
  "visibility": "private",
  "url": "https://web42.apps.company.vn"
}
```

Recommended real publish pipeline:

```text
Publish Request
  → Authorization
  → Secret Scan
  → Dependency Scan
  → Build
  → Health Check
  → Deploy
  → Domain/Route Update
  → Audit Log
```

## Error shape

Recommended common error body:

```json
{
  "code": "PROJECT_NOT_FOUND",
  "message": "Project not found",
  "requestId": "req_123"
}
```

Use standard HTTP status codes.

## Authentication

When the backend is added, the frontend should preferably authenticate through secure HTTP-only cookies for the company SSO/session layer rather than storing long-lived access tokens in localStorage.
