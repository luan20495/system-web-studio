#!/usr/bin/env python3
"""C6 DB-layer probes against a THROW-AWAY PostgreSQL 17.6 container (random loopback port, own name, removed at the end).
Never touches the shared 'hbl'/'hblpub' databases or any port other than the random one Docker assigns. Read-only w.r.t. the repo.

  python3 docs/parallel/c6/harness/db-probes.py --sha 8e91172 --out docs/parallel/c6/evidence/mac-8e91172-contract-checks

Applies every migration V1..Vn (psql, ON_ERROR_STOP) to an empty database, then:
  DB-MIG-APPLY   all migrations apply to an empty database in version order
  DB-AUD-*       audit_events is append-only at the DB layer (REQ-ML-07 / C1-AUD-01): INSERT ok; UPDATE, DELETE, TRUNCATE refused for the
                 table owner AND for a non-owner application role; a non-owner cannot disable the trigger; the OWNER can (INFO: residual, T19)
  DB-U27-*       undo script U27 is guarded (C0-MIG-27 / REQ-ML-02): refuses while a publish_configs row holds policy set after the backfill,
                 drops the table when only backfill rows remain
Output: <out>/db-probes.tsv (id<TAB>status<TAB>detail). Exit 0 = no FAIL.
"""
import argparse, os, re, subprocess, sys, time, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
MIG = os.path.join(ROOT, "backend/src/main/resources/db/migration")
IMAGE = "postgres:17.6"
OUT = []


def rec(i, st, detail):
    OUT.append((i, st, detail))
    print(f"{st:5} {i}  {detail}")


def sh(*a, check=False, inp=None):
    return subprocess.run(a, capture_output=True, text=True, input=inp, check=check)


class Pg:
    def __init__(self):
        self.name = "c6-qa-pg-" + uuid.uuid4().hex[:8]

    def start(self):
        r = sh("docker", "run", "-d", "--rm", "--name", self.name, "-e", "POSTGRES_PASSWORD=c6-throwaway", "-e", "POSTGRES_USER=studio", "-e", "POSTGRES_DB=c6qa",
               "-p", "127.0.0.1::5432", IMAGE)
        if r.returncode != 0:
            raise SystemExit("cannot start the throw-away postgres: " + r.stderr.strip())
        for _ in range(60):
            if sh("docker", "exec", self.name, "pg_isready", "-U", "studio", "-d", "c6qa").returncode == 0 and self.q("select 1").strip() == "1":
                return
            time.sleep(1)
        raise SystemExit("postgres did not become ready")

    def stop(self):
        sh("docker", "rm", "-f", self.name)

    def psql(self, sql, user="studio", ok=True):
        r = sh("docker", "exec", "-i", self.name, "psql", "-X", "-q", "-U", user, "-d", "c6qa", "-v", "ON_ERROR_STOP=1", "-At", "-f", "-", inp=sql)
        return r

    def q(self, sql, user="studio"):
        return self.psql(sql, user).stdout

    def file(self, path):
        sh("docker", "cp", path, f"{self.name}:/tmp/m.sql")
        return sh("docker", "exec", self.name, "psql", "-X", "-q", "-U", "studio", "-d", "c6qa", "-v", "ON_ERROR_STOP=1", "-f", "/tmp/m.sql")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sha", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if sh("docker", "info").returncode != 0:
        raise SystemExit("docker daemon not running")
    pg = Pg()
    try:
        pg.start()
        files = sorted((f for f in os.listdir(MIG) if re.match(r"V\d+__.*\.sql$", f)), key=lambda f: int(re.match(r"V(\d+)__", f).group(1)))
        for f in files:
            r = pg.file(os.path.join(MIG, f))
            if r.returncode != 0:
                rec("DB-MIG-APPLY", "FAIL", f"{f} failed: {r.stderr.strip()[:200]}")
                break
        else:
            rec("DB-MIG-APPLY", "PASS", f"{len(files)} migrations (V1..V{len(files)}) applied to an empty PostgreSQL 17.6 in order")

        # ---- seed the minimum graph (users -> workspace -> project) used by both probe groups
        seed = """
        INSERT INTO users (id, username, password_hash, system_admin) VALUES ('11111111-0000-0000-0000-000000000001','c6-alice','x',false);
        INSERT INTO workspaces (id, name, slug) VALUES ('22222222-0000-0000-0000-000000000001','WS1','c6-ws1');
        INSERT INTO projects (id, workspace_id, name, owner_user_id) VALUES ('33333333-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000001','P1','11111111-0000-0000-0000-000000000001');
        """
        r = pg.psql(seed)
        if r.returncode != 0:
            rec("DB-SEED", "FAIL", r.stderr.strip()[:200])
        # ---- audit append-only
        r = pg.psql("INSERT INTO audit_events (id, action, resource_type) VALUES ('44444444-0000-0000-0000-000000000001','C6_PROBE','PROBE');")
        rec("DB-AUD-1", "PASS" if r.returncode == 0 else "FAIL", "INSERT into audit_events is allowed" if r.returncode == 0 else r.stderr.strip()[:160])
        msg = "audit_events is append-only"
        for i, (label, sql) in enumerate([("UPDATE", "UPDATE audit_events SET action='X';"), ("DELETE", "DELETE FROM audit_events;"), ("TRUNCATE", "TRUNCATE audit_events;")], start=2):
            r = pg.psql(sql)
            rec(f"DB-AUD-{i}", "PASS" if (r.returncode != 0 and msg in r.stderr) else "FAIL", f"{label} by the table owner is refused with '{msg}'" if (r.returncode != 0 and msg in r.stderr) else f"{label} was NOT refused: rc={r.returncode} {r.stderr.strip()[:120]}")
        n = pg.q("SELECT count(*) FROM audit_events;").strip()
        rec("DB-AUD-5", "PASS" if n == "1" else "FAIL", f"the probe row is still there after the refused UPDATE/DELETE/TRUNCATE (count={n})")
        pg.psql("CREATE ROLE c6_app LOGIN PASSWORD 'x'; GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON audit_events TO c6_app;")
        bad = []
        for label, sql in [("UPDATE", "UPDATE audit_events SET action='X';"), ("DELETE", "DELETE FROM audit_events;"), ("TRUNCATE", "TRUNCATE audit_events;")]:
            r = pg.psql(sql, user="c6_app")
            if not (r.returncode != 0 and msg in r.stderr):
                bad.append(label)
        rec("DB-AUD-6", "FAIL" if bad else "PASS", ("a non-owner role holding full DML+TRUNCATE privileges was NOT refused for: " + ", ".join(bad)) if bad else "a non-owner application role granted UPDATE/DELETE/TRUNCATE is still refused by the triggers (the guard does not depend on privileges)")
        r = pg.psql("ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete;", user="c6_app")
        rec("DB-AUD-7", "PASS" if r.returncode != 0 else "FAIL", "a non-owner role cannot disable the trigger (permission denied)" if r.returncode != 0 else "a non-owner role could DISABLE the audit trigger")
        r = pg.psql("ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete; ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update_delete;")
        rec("DB-AUD-8", "INFO", "RESIDUAL: the table OWNER can disable and re-enable the audit trigger" + (" (confirmed)" if r.returncode == 0 else "") + " — the application connects as the owner; revoking this needs privilege hardening (MIGRATION_LEDGER item 6 / T19), which is not implemented and not required by a frozen contract yet")

        # negative control: with the trigger disabled the same UPDATE succeeds, so DB-AUD-2/3/4 would FAIL if the trigger were missing
        r = pg.psql("ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete; UPDATE audit_events SET action='C6_CONTROL'; ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update_delete;")
        v = pg.q("SELECT action FROM audit_events;").strip()
        pg.psql("ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete; UPDATE audit_events SET action='C6_PROBE'; ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update_delete;")
        rec("DB-AUD-9", "PASS" if v == "C6_CONTROL" else "FAIL", "negative control: with the trigger disabled the UPDATE succeeds, so the probes above do detect a missing trigger" if v == "C6_CONTROL" else "negative control failed: the probe cannot distinguish a present from a missing trigger")
        r = pg.psql("UPDATE audit_events SET action='X';")
        rec("DB-AUD-10", "PASS" if (r.returncode != 0 and msg in r.stderr) else "FAIL", "trigger re-enabled: the UPDATE is refused again" if (r.returncode != 0 and msg in r.stderr) else "trigger was not restored")

        # ---- U27 undo guard
        pg.psql("""INSERT INTO publish_configs (project_id, workspace_id, tenant_id, mode, visibility, revision)
                   SELECT p.id, p.workspace_id, p.tenant_id, 'STATIC', 'PRIVATE', 1 FROM projects p;""")
        u27 = open(os.path.join(ROOT, "docs/parallel/c2/undo/U27__publish_configs.sql"), encoding="utf-8").read()
        r = pg.psql("BEGIN;\n" + u27 + "\nROLLBACK;")
        ok = r.returncode == 0 and pg.q("SELECT count(*) FROM information_schema.tables WHERE table_name='publish_configs'").strip() == "1"
        rec("DB-U27-1", "PASS" if ok else "FAIL", "with only V27-backfill rows (revision 1, PRIVATE) U27 proceeds (rolled back here to keep the table)" if ok else f"U27 refused or failed on a clean backfill: {r.stderr.strip()[:140]}")
        pg.psql("UPDATE publish_configs SET revision = 2, updated_by = '11111111-0000-0000-0000-000000000001';")
        r = pg.psql("BEGIN;\n" + u27 + "\nROLLBACK;")
        refused = r.returncode != 0 and "U27 refused" in r.stderr
        still = pg.q("SELECT count(*) FROM information_schema.tables WHERE table_name='publish_configs'").strip() == "1"
        rec("DB-U27-2", "PASS" if (refused and still) else "FAIL", "with a row whose policy a person changed (revision 2) U27 refuses and the table is untouched" if (refused and still) else f"U27 did not refuse a user-edited policy row: rc={r.returncode} {r.stderr.strip()[:140]}")
        pg.psql("UPDATE publish_configs SET public_data_approved = TRUE, revision = 1, updated_by = NULL;")
        r = pg.psql("BEGIN;\n" + u27 + "\nROLLBACK;")
        rec("DB-U27-3", "PASS" if (r.returncode != 0 and "U27 refused" in r.stderr) else "FAIL", "public_data_approved = TRUE (an explicit approval) also makes U27 refuse" if (r.returncode != 0 and "U27 refused" in r.stderr) else "U27 would silently drop an explicit public-data approval")
        pg.psql("DELETE FROM publish_configs;")
        r = pg.psql(u27)
        gone = pg.q("SELECT count(*) FROM information_schema.tables WHERE table_name='publish_configs'").strip() == "0"
        rec("DB-U27-4", "PASS" if (r.returncode == 0 and gone) else "FAIL", "with no rows U27 drops publish_configs" if (r.returncode == 0 and gone) else f"U27 failed on an empty table: {r.stderr.strip()[:140]}")
    finally:
        pg.stop()
        left = sh("docker", "ps", "-a", "--filter", f"name={pg.name}", "--format", "{{.Names}}").stdout.strip()
        rec("DB-CLEANUP", "PASS" if not left else "FAIL", "throw-away container removed" if not left else f"container {left} still exists")
    out = os.path.join(ROOT, a.out) if not os.path.isabs(a.out) else a.out
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "db-probes.tsv"), "w", encoding="utf-8") as fh:
        fh.write("id\tstatus\tdetail\n")
        for i, st, d in OUT:
            fh.write(f"{i}\t{st}\t{d}\n")
    n = sum(1 for _, s, _ in OUT if s == "FAIL")
    print(f"\ndb-probes: {sum(1 for _, s, _ in OUT if s == 'PASS')} PASS · {n} FAIL · {sum(1 for _, s, _ in OUT if s == 'INFO')} INFO")
    sys.exit(1 if n else 0)


if __name__ == "__main__":
    main()
