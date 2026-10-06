# B-C1-13 — `POST /api/v1/admin/applications/{id}/transfer-ownership` lets a system admin make itself owner

Owner of the file: C0 (`backend/.../admin/AdminController.kt`). C1 does **not** edit it. This is the exact patch C1 proposes.
Regression spec (disabled until the patch is applied): `backend/src/test/kotlin/com/systemwebstudio/tenancy/AdminTransferOwnershipSelfGrantSpec.kt`.

## The hole
Endpoint `POST /api/v1/admin/applications/{id}/transfer-ownership`, body `{"userId": "<target>"}`, guard `guard.require(me.userId)` (system admin only).
Condition: the caller is a SYSTEM_ADMIN **and** an enabled member of the application's workspace (any role, e.g. VIEWER) and sends `userId == caller`.
The handler checks only that the target is an enabled workspace member (`eligible`), then runs `UPDATE projects SET owner_user_id = target` and
`INSERT INTO project_members ... 'OWNER' ... ON CONFLICT DO UPDATE SET role = 'OWNER', active = TRUE`. Result: a VIEWER raises itself to project OWNER
(APP_SHARE/PROJECT_MEMBERS, PROJECT_DELETE, ...) without anyone else granting it. This is the same class as R-08 (self-grant); with
`app.tenancy.system-admin-business-access=false` a system admin is not supposed to gain business access by itself.

## Expected behaviour
| Caller | `userId` | Expected |
|---|---|---|
| system admin | another enabled workspace member | 200, unchanged (granting someone else is the platform duty) |
| system admin | itself, it is **not** the current owner | **403 `SELF_GRANT_FORBIDDEN`**, no row changes |
| system admin | itself, it already is the owner | 200 no-op (existing early return) |

## Patch (one statement, after the existing no-op return)
```kotlin
        val ws = row["workspace_id"] as UUID; val previous = row["owner_user_id"] as UUID
        if (previous == target) return jdbc.query("$appSelect WHERE p.id = ?", { rs, _ -> appRow(rs) }, id).first()
+       // B-C1-13 / R-08: nobody raises its own authority; someone else must hand over the ownership
+       if (target == me.userId) throw ApiException(HttpStatus.FORBIDDEN, "SELF_GRANT_FORBIDDEN", "You cannot make yourself the owner of an application")
        val eligible = count("SELECT count(*) ...
```
`ApiException(HttpStatus, String, String)` and `HttpStatus` are already used in this method (`NOT_WORKSPACE_MEMBER`), so no new import.

## After applying
Delete the `@Disabled` line in `AdminTransferOwnershipSelfGrantSpec` and run `./gradlew test --tests '*AdminTransferOwnershipSelfGrantSpec'`.
