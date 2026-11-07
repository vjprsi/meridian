#!/usr/bin/env python3.12
"""Database integrity sweep across every service DB on the box.
For each DB: table/row census (populated vs empty), generic FK-orphan sweep,
duplicate idempotency_key detection, and ledger double-entry balance check.
Runs psql via `sg docker -c docker exec -i meridian-postgres psql`. Stdlib only."""
import subprocess, json, sys, collections

PGUSER = "meridian"
CONTAINER = "meridian-postgres"


def psql(db, sql):
    """Run SQL on <db>, return list of rows (each a list of column strings). Tab-separated, no header."""
    cmd = ["sg", "docker", "-c",
           f"docker exec -i {CONTAINER} psql -U {PGUSER} -d {db} -At -F '\t' -v ON_ERROR_STOP=0 -q -f -"]
    p = subprocess.run(cmd, input=sql, capture_output=True, text=True, timeout=180)
    out = p.stdout.strip()
    err = p.stderr.strip()
    rows = [line.split("\t") for line in out.splitlines()] if out else []
    return rows, err


def list_dbs():
    rows, _ = psql("postgres", "select datname from pg_database where datistemplate=false and datname not in ('postgres') order by 1;")
    return [r[0] for r in rows if r and r[0]]


def qident(s):
    return '"' + s.replace('"', '""') + '"'


REPORT = {"dbs": {}, "findings": []}


def finding(sev, kind, db, detail):
    REPORT["findings"].append({"severity": sev, "kind": kind, "db": db, "detail": detail})
    print(f"  [{sev}] {kind} :: {db} :: {detail}")


def sweep_db(db):
    info = {"tables": 0, "populated": 0, "empty": 0, "total_rows": 0,
            "empty_tables": [], "fk_checked": 0, "fk_orphans": 0, "dup_idem": 0}
    # --- tables in user schemas ---
    rows, err = psql(db, """select n.nspname, c.relname from pg_class c
        join pg_namespace n on n.oid=c.relnamespace
        where c.relkind='r' and n.nspname not in ('pg_catalog','information_schema')
        order by 1,2;""")
    tables = [(r[0], r[1]) for r in rows if len(r) == 2]
    info["tables"] = len(tables)
    if not tables:
        REPORT["dbs"][db] = info
        return
    # --- accurate row counts (one UNION query) ---
    union = "\nUNION ALL\n".join(
        f"select '{s}.{t}' as t, count(*)::bigint from {qident(s)}.{qident(t)}" for s, t in tables)
    rows, err = psql(db, union + ";")
    counts = {}
    for r in rows:
        if len(r) == 2:
            try:
                counts[r[0]] = int(r[1])
            except ValueError:
                pass
    for (s, t) in tables:
        key = f"{s}.{t}"
        n = counts.get(key, 0)
        info["total_rows"] += n
        if n > 0:
            info["populated"] += 1
        else:
            info["empty"] += 1
            info["empty_tables"].append(key)
    # --- FK orphan sweep (single-column FKs only) ---
    rows, err = psql(db, """select tc.constraint_name, tc.table_schema, tc.table_name, kcu.column_name,
            ccu.table_schema, ccu.table_name, ccu.column_name
        from information_schema.table_constraints tc
        join information_schema.key_column_usage kcu
            on tc.constraint_name=kcu.constraint_name and tc.table_schema=kcu.table_schema
        join information_schema.constraint_column_usage ccu
            on ccu.constraint_name=tc.constraint_name and ccu.table_schema=tc.table_schema
        where tc.constraint_type='FOREIGN KEY'
        order by 1;""")
    fks = collections.defaultdict(list)
    for r in rows:
        if len(r) == 7:
            fks[r[0]].append(r)
    single = [v[0] for v in fks.values() if len(v) == 1]
    if single:
        parts = []
        for cn, cs, ct, ccol, fs, ft, fcol in single:
            parts.append(
                f"select '{cn}' as fk, count(*)::bigint from {qident(cs)}.{qident(ct)} c "
                f"left join {qident(fs)}.{qident(ft)} p on c.{qident(ccol)}=p.{qident(fcol)} "
                f"where c.{qident(ccol)} is not null and p.{qident(fcol)} is null")
        rows2, err2 = psql(db, "\nUNION ALL\n".join(parts) + ";")
        info["fk_checked"] = len(single)
        for r in rows2:
            if len(r) == 2:
                try:
                    orphans = int(r[1])
                except ValueError:
                    continue
                if orphans > 0:
                    info["fk_orphans"] += orphans
                    finding("HIGH", "fk_orphan", db, f"{r[0]}: {orphans} orphaned rows (child FK with no parent)")
    # --- duplicate idempotency_key ---
    rows, err = psql(db, "select table_schema, table_name from information_schema.columns "
                         "where column_name='idempotency_key' and table_schema not in ('pg_catalog','information_schema');")
    idem_tables = [(r[0], r[1]) for r in rows if len(r) == 2]
    if idem_tables:
        parts = []
        for s, t in idem_tables:
            parts.append(f"select '{s}.{t}' as t, count(*)::bigint from "
                         f"(select idempotency_key from {qident(s)}.{qident(t)} where idempotency_key is not null "
                         f"group by idempotency_key having count(*)>1) x")
        rows2, _ = psql(db, "\nUNION ALL\n".join(parts) + ";")
        for r in rows2:
            if len(r) == 2:
                try:
                    dups = int(r[1])
                except ValueError:
                    continue
                if dups > 0:
                    info["dup_idem"] += dups
                    finding("HIGH", "dup_idempotency_key", db, f"{r[0]}: {dups} duplicated idempotency_key groups")
    REPORT["dbs"][db] = info


def ledger_balance_check():
    """ledger_db: every journal entry's postings must net to zero (sum signed amount = 0)."""
    db = "ledger_db"
    # discover posting table + columns
    rows, _ = psql(db, "select table_schema, table_name from information_schema.tables "
                       "where table_name in ('posting','postings','ledger_posting','journal_posting','entry','entries') "
                       "and table_schema not in ('pg_catalog','information_schema');")
    if not rows:
        print("  ledger: no posting table discovered; columns probe...")
    # probe columns of likely posting table
    for r in rows:
        s, t = r[0], r[1]
        cols, _ = psql(db, f"select column_name from information_schema.columns where table_schema='{s}' and table_name='{t}';")
        colset = {c[0] for c in cols if c}
        # need a journal-entry grouping id + a direction/signed amount
        grp = next((c for c in ("journal_entry_id", "entry_id", "journal_id", "transaction_id") if c in colset), None)
        amt = "amount" if "amount" in colset else None
        direction = next((c for c in ("direction", "side", "dc", "drcr") if c in colset), None)
        print(f"  ledger posting candidate {s}.{t}: grp={grp} amt={amt} dir={direction} cols={sorted(colset)[:12]}")
        if grp and amt and direction:
            sql = (f"select count(*) from (select {qident(grp)} as g, "
                   f"sum(case when upper({qident(direction)}::text) like 'D%' then {qident(amt)} "
                   f"else -{qident(amt)} end) as net "
                   f"from {qident(s)}.{qident(t)} group by {qident(grp)} having "
                   f"sum(case when upper({qident(direction)}::text) like 'D%' then {qident(amt)} "
                   f"else -{qident(amt)} end) <> 0) x;")
            rows2, err = psql(db, sql)
            if rows2 and rows2[0] and rows2[0][0].isdigit():
                n = int(rows2[0][0])
                if n == 0:
                    print(f"  ledger: OK — all journal entries balance (debits==credits) in {s}.{t}")
                    REPORT["findings"].append({"severity": "INFO", "kind": "ledger_balanced", "db": db,
                                               "detail": f"{s}.{t}: all entries net to zero"})
                else:
                    finding("CRITICAL", "ledger_imbalance", db, f"{s}.{t}: {n} journal entries do NOT net to zero")
            else:
                print(f"  ledger: balance query inconclusive ({err[:120]})")


def main():
    dbs = list_dbs()
    print(f"DB integrity sweep: {len(dbs)} databases")
    for db in dbs:
        try:
            sweep_db(db)
        except Exception as e:
            finding("ERROR", "sweep_error", db, f"{type(e).__name__}: {e}")
    print("\n== LEDGER DOUBLE-ENTRY CHECK ==")
    try:
        ledger_balance_check()
    except Exception as e:
        finding("ERROR", "ledger_error", "ledger_db", str(e))
    # census summary
    tot_t = sum(v["tables"] for v in REPORT["dbs"].values())
    tot_p = sum(v["populated"] for v in REPORT["dbs"].values())
    tot_e = sum(v["empty"] for v in REPORT["dbs"].values())
    tot_fk = sum(v["fk_checked"] for v in REPORT["dbs"].values())
    tot_orph = sum(v["fk_orphans"] for v in REPORT["dbs"].values())
    tot_dup = sum(v["dup_idem"] for v in REPORT["dbs"].values())
    tot_rows = sum(v["total_rows"] for v in REPORT["dbs"].values())
    print("\n== DB CENSUS ==")
    print(f"  databases={len(REPORT['dbs'])}  tables={tot_t}  populated={tot_p}  empty={tot_e}  rows={tot_rows}")
    print(f"  FK constraints checked={tot_fk}  orphan rows={tot_orph}  duplicate idempotency groups={tot_dup}")
    print("\n  per-DB (populated/total, rows):")
    for db in sorted(REPORT["dbs"]):
        v = REPORT["dbs"][db]
        print(f"    {db:28s} {v['populated']:3d}/{v['tables']:<3d} tables  {v['total_rows']:>8d} rows"
              + (f"  FKok({v['fk_checked']})" if v["fk_orphans"] == 0 else f"  FK-ORPHANS={v['fk_orphans']}"))
    crit = [f for f in REPORT["findings"] if f["severity"] in ("CRITICAL", "HIGH", "ERROR")]
    print(f"\n== INTEGRITY FINDINGS: {len(crit)} (CRITICAL/HIGH/ERROR) ==")
    for f in crit:
        print(f"  [{f['severity']}] {f['kind']} {f['db']}: {f['detail']}")
    json.dump(REPORT, open("/tmp/qa_db.json", "w"), default=str)
    print("\nwrote /tmp/qa_db.json")


if __name__ == "__main__":
    main()
