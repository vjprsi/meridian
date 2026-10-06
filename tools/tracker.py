"""Builds tracker/Meridian_Tracker.xlsx from the spec.

Manual edits survive regeneration: for the Roadmap, Backlog, Decisions and Risks sheets the
columns listed in KEEP are read back from the existing workbook (matched by ID) and re-applied.
"""
from __future__ import annotations

import re
from datetime import date
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.formatting.rule import CellIsRule, FormulaRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

FONT = "Arial"
HDR_FILL = PatternFill("solid", fgColor="1F3864")
HDR_FONT = Font(name=FONT, bold=True, color="FFFFFF", size=10)
BODY = Font(name=FONT, size=10)
BOLD = Font(name=FONT, size=10, bold=True)
TITLE = Font(name=FONT, size=16, bold=True, color="1F3864")
SUB = Font(name=FONT, size=10, italic=True, color="595959")
INPUT_FILL = PatternFill("solid", fgColor="FFF2CC")
THIN = Side(style="thin", color="D9D9D9")
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
WRAP = Alignment(wrap_text=True, vertical="top")

STATUSES = ["Not started", "In progress", "Blocked", "In review", "Done"]
STATUS_RANK = {"Not started": 0, "Blocked": 1, "In progress": 2, "In review": 3, "Done": 4}
PRIORITIES = ["P0", "P1", "P2", "P3"]
DECISION_STATUS = ["Open", "Proposed", "Decided", "Superseded"]
RISK_LEVELS = ["Low", "Medium", "High", "Critical"]

KEEP = {
    "Roadmap": ["Status", "Owner", "Start", "Due", "Done on", "Notes"],
    "Backlog": ["Status", "Owner", "Priority", "Est. days", "Start", "Due", "Done on", "Notes"],
    "Decisions": ["Status", "Decision", "Owner", "Decided on", "Notes"],
    "Risks": ["Status", "Owner", "Notes"],
}


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def read_existing(path: Path) -> dict:
    """{sheet: {id: {col: value}}} for KEEP columns of the existing workbook."""
    out: dict = {}
    if not path.exists():
        return out
    try:
        wb = load_workbook(path)
    except Exception:
        return out
    for sheet, cols in KEEP.items():
        if sheet not in wb.sheetnames:
            continue
        ws = wb[sheet]
        header = [c.value for c in ws[1]]
        if "ID" not in header:
            continue
        idx = {h: i for i, h in enumerate(header)}
        rows = {}
        for r in ws.iter_rows(min_row=2, values_only=True):
            rid = r[idx["ID"]]
            if rid:
                rows[rid] = {c: r[idx[c]] for c in cols if c in idx}
        out[sheet] = rows
    return out


def write_table(ws, headers, rows, widths=None, keep=None, wrap_cols=()):
    ws.append(headers)
    for c in ws[1]:
        c.font, c.fill, c.border = HDR_FONT, HDR_FILL, BORDER
        c.alignment = Alignment(vertical="center", wrap_text=True)
    hidx = {h: i for i, h in enumerate(headers)}
    for r in rows:
        r = list(r)
        if keep and r[hidx["ID"]] in keep:
            for col, val in keep[r[hidx["ID"]]].items():
                if col in hidx and val not in (None, ""):
                    if col == "Status" and val in STATUS_RANK and r[hidx[col]] in STATUS_RANK \
                            and val != "Blocked" and STATUS_RANK[r[hidx[col]]] > STATUS_RANK[val]:
                        continue  # spec reports more progress than the sheet: keep the spec's status
                    r[hidx[col]] = val
        ws.append(r)
    for row in ws.iter_rows(min_row=2, max_row=ws.max_row):
        for c in row:
            c.font, c.border = BODY, BORDER
            if headers[c.column - 1] in wrap_cols:
                c.alignment = WRAP
    ws.freeze_panes = "B2" if len(headers) > 6 else "A2"
    ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{max(ws.max_row, 2)}"
    for i, h in enumerate(headers, 1):
        w = (widths or {}).get(h)
        if w is None:
            vals = [len(str(ws.cell(row=j, column=i).value or "")) for j in range(1, min(ws.max_row, 300) + 1)]
            w = min(max(vals + [len(h)]) + 2, 60)
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.row_dimensions[1].height = 30


def add_list_validation(ws, col_letter, values, n_rows):
    dv = DataValidation(type="list", formula1='"' + ",".join(values) + '"', allow_blank=True)
    dv.add(f"{col_letter}2:{col_letter}{max(n_rows, 2) + 500}")
    ws.add_data_validation(dv)


def mark_inputs(ws, headers, cols, n_rows):
    for h in cols:
        if h in headers:
            i = headers.index(h) + 1
            for r in range(2, n_rows + 2):
                ws.cell(row=r, column=i).fill = INPUT_FILL


def status_colours(ws, col_letter, n_rows):
    rng = f"{col_letter}2:{col_letter}{max(n_rows, 2) + 500}"
    for val, colour in [("Done", "C6EFCE"), ("In progress", "DDEBF7"), ("Blocked", "FFC7CE"), ("In review", "FFEB9C")]:
        ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=[f'"{val}"'],
                                                      fill=PatternFill("solid", fgColor=colour)))


def build(path: Path, domains, enums, endpoints, events, apps, plan, api_by_table) -> str:
    keep = read_existing(path)
    wb = Workbook()
    dash = wb.active
    dash.title = "Dashboard"

    # ---------------- Roadmap ----------------
    ws = wb.create_sheet("Roadmap")
    steps = plan.get("steps", [])
    h = ["ID", "Phase", "Step", "Deliverables", "Exit criteria", "Status", "Owner", "Start", "Due", "Done on", "Notes"]
    rows = [[s["id"], s["phase"], s["name"], s.get("deliverables", ""), s.get("exit", ""),
             s.get("status", "Not started"), "", s.get("start"), s.get("due"), None, ""] for s in steps]
    write_table(ws, h, rows, widths={"Deliverables": 70, "Exit criteria": 45, "Step": 34, "Notes": 30,
                                     "Start": 12, "Due": 12, "Done on": 12},
                keep=keep.get("Roadmap"), wrap_cols=("Deliverables", "Exit criteria", "Step"))
    add_list_validation(ws, "F", STATUSES, len(rows))
    status_colours(ws, "F", len(rows))
    mark_inputs(ws, h, ["Status", "Owner", "Start", "Due", "Done on", "Notes"], len(rows))
    for r in range(2, len(rows) + 2):
        for col in ("H", "I", "J"):
            ws[f"{col}{r}"].number_format = "yyyy-mm-dd"

    # ---------------- Backlog ----------------
    ws = wb.create_sheet("Backlog")
    tasks = []

    def task(tid, phase, domain, service, ttype, title, prio="P1", est=None, status="Not started", dep=""):
        tasks.append([tid, phase, domain, service, ttype, title, dep, status, "", prio, est, None, None, None, ""])

    for t in plan.get("tasks", []):
        task(t["id"], t.get("phase", 0), t.get("domain", "platform"), t.get("service", ""), t.get("type", "Platform"),
             t["title"], t.get("priority", "P1"), t.get("est"), t.get("status", "Not started"), t.get("depends", ""))
    for d in domains:
        task(f"SPEC-{d.id}", d.phase, d.id, "", "Spec",
             f"{d.title}: data model, APIs and events specified in spec/domains/{d.id}.yaml",
             "P0", 3, "Done")
        for s in d.services:
            n_ep = sum(1 for e in endpoints if e.service == s.name)
            base = f"{s.name}"
            task(f"SVC-{base}-SCAFFOLD", d.phase, d.id, s.name, "Service",
                 f"{s.name}: Spring Boot module scaffold, config, security, health, OpenAPI codegen", "P0", 2)
            task(f"SVC-{base}-DB", d.phase, d.id, s.name, "Database",
                 f"{s.name}: Flyway migrations for {s.database} ({len(s.tables)} tables), RLS, seed data",
                 "P0", max(1, len(s.tables) // 3), dep=f"SVC-{base}-SCAFFOLD")
            task(f"SVC-{base}-DOMAIN", d.phase, d.id, s.name, "Service",
                 f"{s.name}: domain model, repositories, business rules", "P0", max(3, len(s.tables)),
                 dep=f"SVC-{base}-DB")
            for a in s.apis:
                n = sum(1 for e in endpoints if e.service == s.name and e.resource == a.resource)
                task(f"API-{a.resource}", d.phase, d.id, s.name, "API",
                     f"{s.name}: implement {a.path} ({n} endpoints)", "P1", max(1, n // 2),
                     dep=f"SVC-{base}-DOMAIN")
            if s.events:
                task(f"SVC-{base}-EVENTS", d.phase, d.id, s.name, "Events",
                     f"{s.name}: outbox + Kafka publishing for {len(s.events)} events", "P1", 2,
                     dep=f"SVC-{base}-DOMAIN")
            task(f"SVC-{base}-TEST", d.phase, d.id, s.name, "Test",
                 f"{s.name}: unit, property-based, Testcontainers and Pact contract tests ({n_ep} endpoints)",
                 "P0", max(2, n_ep // 4), dep=f"SVC-{base}-DOMAIN")
    for app in apps.get("apps", []):
        task(f"APP-{app['id']}-SHELL", app.get("phase", 1), "channels", app["id"], "Web/Mobile",
             f"{app['name']}: app shell, auth, navigation, theming, i18n", "P0", 5)
        for sc in app.get("screens", []):
            task(f"UI-{app['id']}-{slug(sc['name'])}", sc.get("phase", app.get("phase", 1)), "channels", app["id"],
                 "UI", f"{app['name']}: {sc['name']}", sc.get("priority", "P1"), sc.get("est", 2),
                 dep=f"APP-{app['id']}-SHELL")
    h = ["ID", "Phase", "Domain", "Service / App", "Type", "Task", "Depends on", "Status", "Owner", "Priority",
         "Est. days", "Start", "Due", "Done on", "Notes"]
    write_table(ws, h, tasks, widths={"Task": 80, "Notes": 30, "Start": 12, "Due": 12, "Done on": 12,
                                      "Depends on": 28, "ID": 40},
                keep=keep.get("Backlog"), wrap_cols=("Task",))
    add_list_validation(ws, "H", STATUSES, len(tasks))
    add_list_validation(ws, "J", PRIORITIES, len(tasks))
    status_colours(ws, "H", len(tasks))
    mark_inputs(ws, h, KEEP["Backlog"], len(tasks))
    for r in range(2, len(tasks) + 2):
        for col in ("L", "M", "N"):
            ws[f"{col}{r}"].number_format = "yyyy-mm-dd"
    n_tasks = len(tasks)

    # ---------------- Services ----------------
    ws = wb.create_sheet("Services")
    rows = []
    for d in domains:
        for s in d.services:
            rows.append([s.name, d.title, d.phase, s.database, f"Java 25 / Spring Boot 4", len(s.tables),
                         sum(len(t.columns) for t in s.tables),
                         sum(1 for e in endpoints if e.service == s.name), len(s.events), s.description])
    write_table(ws, ["Service", "Domain", "Phase", "Database", "Stack", "Tables", "Columns", "Endpoints",
                     "Events", "Description"], rows, widths={"Description": 80}, wrap_cols=("Description",))

    # ---------------- Databases ----------------
    ws = wb.create_sheet("Databases")
    rows = []
    for d in domains:
        for s in d.services:
            rows.append([s.database, s.database[:-3] if s.database.endswith("_db") else s.database, s.name,
                         d.title, "PostgreSQL 18 (distributed SQL for ledger)" if d.id == "ledger" else "PostgreSQL 18",
                         len(s.tables), sum(len(t.columns) for t in s.tables),
                         f"generated/db/{s.database}/V001__init.sql"])
    write_table(ws, ["Database", "Schema", "Owning service", "Domain", "Engine", "Tables", "Columns",
                     "Migration"], rows)

    # ---------------- Tables ----------------
    ws = wb.create_sheet("Tables")
    rows = []
    for d in domains:
        for s in d.services:
            for t in s.tables:
                rows.append([f"{s.database}.{t.name}", s.database, t.name, d.title, s.name,
                             "Append-only" if t.immutable else "Mutable", "Yes" if t.tenant else "No",
                             len(t.columns), sum(1 for c in t.columns if c.pii),
                             ", ".join(sorted(api_by_table.get(t.name, []))) or "internal",
                             ", ".join(t.fr), t.description])
    write_table(ws, ["Qualified name", "Database", "Table", "Domain", "Service", "Mutability", "Tenant RLS",
                     "Columns", "PII columns", "API paths", "Requirements", "Description"], rows,
                widths={"Description": 70, "API paths": 50}, wrap_cols=("Description",))
    n_tables = len(rows)

    # ---------------- Columns (data dictionary + API mapping) ----------------
    ws = wb.create_sheet("Columns")
    rows = []
    from generate import pg_type  # local import to avoid cycle at module load
    for d in domains:
        for s in d.services:
            for t in s.tables:
                for i, c in enumerate(t.columns, 1):
                    key = "PK" if c.pk else (f"FK → {c.fk}.id" if c.fk else (f"ref → {c.ref}" if c.ref else ("UQ" if c.uq else "")))
                    api_field = c.name if c.in_api else "—"
                    access = "—" if not c.in_api else ("read-only" if not c.writable else "read/write")
                    rows.append([s.database, t.name, i, c.name, pg_type(c),
                                 c.arg if c.type == "enum" else "", "No" if c.nn else "Yes", key,
                                 c.default or "", "Yes" if c.pii else "", "Yes" if c.secret else "",
                                 api_field, access, "Standard" if c.std else "Domain", c.description])
    write_table(ws, ["Database", "Table", "#", "Column", "SQL type", "Enum", "Nullable", "Key / reference",
                     "Default", "PII", "Secret", "API field", "API access", "Origin", "Description"], rows,
                widths={"Description": 60, "#": 5})
    n_cols = len(rows)

    # ---------------- Enums ----------------
    ws = wb.create_sheet("Enums")
    rows = [[name, i + 1, v] for name, vals in sorted(enums.items()) for i, v in enumerate(vals)]
    write_table(ws, ["Enum", "#", "Value"], rows)

    # ---------------- API Endpoints ----------------
    ws = wb.create_sheet("API Endpoints")
    rows = [[e.op_id, e.method, e.path, e.summary, e.domain, e.service, e.resource, e.table, e.kind, e.scope,
             e.request_schema, e.response_schema, ", ".join(e.fr)] for e in endpoints]
    write_table(ws, ["Operation ID", "Method", "Path", "Summary", "Domain", "Service", "Resource", "Table",
                     "Kind", "Scope", "Request schema", "Response schema", "Requirements"], rows,
                widths={"Path": 60, "Summary": 45})
    n_ep = len(rows)

    # ---------------- Events ----------------
    ws = wb.create_sheet("Events")
    write_table(ws, ["Event type", "Topic", "Domain", "Producer service", "Payload (table)", "Description"],
                [list(r) for r in events], widths={"Description": 60})

    # ---------------- Apps & Screens ----------------
    ws = wb.create_sheet("Apps & Screens")
    rows = []
    for app in apps.get("apps", []):
        for sc in app.get("screens", []):
            rows.append([app["name"], app.get("platform", ""), app.get("users", ""), sc["name"],
                         sc.get("phase", app.get("phase", 1)), ", ".join(sc.get("apis", [])), sc.get("description", "")])
    write_table(ws, ["App", "Platform", "Users", "Screen", "Phase", "API operations used", "Description"], rows,
                widths={"API operations used": 70, "Description": 60}, wrap_cols=("API operations used", "Description"))
    n_screens = len(rows)

    # ---------------- Requirements traceability ----------------
    ws = wb.create_sheet("Requirements")
    reqs = plan.get("requirements", {})
    tbl_by_fr, ep_by_fr = {}, {}
    for d in domains:
        for s in d.services:
            for t in s.tables:
                for fr in t.fr:
                    tbl_by_fr.setdefault(fr, []).append(t.name)
    for e in endpoints:
        for fr in e.fr:
            ep_by_fr.setdefault(fr, []).append(e.op_id)
    rows = []
    for fr, info in reqs.items():
        tb = tbl_by_fr.get(fr, [])
        ep = ep_by_fr.get(fr, [])
        rows.append([fr, info.get("module", ""), info.get("priority", ""), info.get("text", ""), len(tb), len(ep),
                     "Covered" if tb or ep else "Gap", ", ".join(tb), ", ".join(ep[:40]) + (" …" if len(ep) > 40 else "")])
    write_table(ws, ["Req ID", "Module", "Priority", "Requirement", "Tables", "Endpoints", "Spec coverage",
                     "Tables (names)", "Endpoints (operation ids)"], rows,
                widths={"Requirement": 70, "Tables (names)": 40, "Endpoints (operation ids)": 60},
                wrap_cols=("Requirement",))
    ws.conditional_formatting.add(f"G2:G{max(len(rows), 1) + 1}", CellIsRule(operator="equal", formula=['"Gap"'],
                                  fill=PatternFill("solid", fgColor="FFC7CE")))
    n_reqs = len(rows)

    # ---------------- Decisions & Risks ----------------
    ws = wb.create_sheet("Decisions")
    rows = [[x["id"], x["title"], x.get("options", ""), x.get("recommendation", ""), x.get("status", "Open"),
             x.get("decision", ""), "", None, ""] for x in plan.get("decisions", [])]
    h = ["ID", "Decision needed", "Options", "Recommendation", "Status", "Decision", "Owner", "Decided on", "Notes"]
    write_table(ws, h, rows, widths={"Decision needed": 45, "Options": 50, "Recommendation": 50, "Decision": 40},
                keep=keep.get("Decisions"), wrap_cols=("Decision needed", "Options", "Recommendation", "Decision"))
    add_list_validation(ws, "E", DECISION_STATUS, len(rows))
    mark_inputs(ws, h, KEEP["Decisions"], len(rows))

    ws = wb.create_sheet("Risks")
    rows = [[x["id"], x["risk"], x.get("impact", ""), x.get("likelihood", ""), x.get("mitigation", ""),
             x.get("status", "Open"), "", ""] for x in plan.get("risks", [])]
    h = ["ID", "Risk", "Impact", "Likelihood", "Mitigation", "Status", "Owner", "Notes"]
    write_table(ws, h, rows, widths={"Risk": 50, "Mitigation": 60}, keep=keep.get("Risks"),
                wrap_cols=("Risk", "Mitigation"))
    add_list_validation(ws, "C", RISK_LEVELS, len(rows))
    add_list_validation(ws, "D", RISK_LEVELS, len(rows))
    add_list_validation(ws, "F", ["Open", "Mitigating", "Closed"], len(rows))
    mark_inputs(ws, h, KEEP["Risks"], len(rows))

    # ---------------- Dashboard ----------------
    ws = dash
    ws["A1"] = "Meridian Core — Build Tracker"
    ws["A1"].font = TITLE
    ws["A2"] = (f"Generated {date.today().isoformat()} by tools/generate.py. Yellow cells on Roadmap, Backlog, "
                "Decisions and Risks are yours to edit; they are kept when the tracker is regenerated.")
    ws["A2"].font = SUB
    ws["A4"] = "Progress by phase (Backlog)"
    ws["A4"].font = BOLD
    hdr = ["Phase", "Tasks", "Done", "In progress", "Blocked", "Not started", "% done", "Est. days", "Days done"]
    for i, v in enumerate(hdr, 1):
        c = ws.cell(row=5, column=i, value=v)
        c.font, c.fill, c.border = HDR_FONT, HDR_FILL, BORDER
    phases = sorted({t[1] for t in tasks})
    r = 6
    for p in phases:
        ws.cell(row=r, column=1, value=p)
        ws.cell(row=r, column=2, value=f"=COUNTIF(Backlog!$B:$B,A{r})")
        ws.cell(row=r, column=3, value=f'=COUNTIFS(Backlog!$B:$B,A{r},Backlog!$H:$H,"Done")')
        ws.cell(row=r, column=4, value=f'=COUNTIFS(Backlog!$B:$B,A{r},Backlog!$H:$H,"In progress")')
        ws.cell(row=r, column=5, value=f'=COUNTIFS(Backlog!$B:$B,A{r},Backlog!$H:$H,"Blocked")')
        ws.cell(row=r, column=6, value=f'=COUNTIFS(Backlog!$B:$B,A{r},Backlog!$H:$H,"Not started")')
        ws.cell(row=r, column=7, value=f"=IF(B{r}=0,0,C{r}/B{r})")
        ws.cell(row=r, column=8, value=f"=SUMIF(Backlog!$B:$B,A{r},Backlog!$K:$K)")
        ws.cell(row=r, column=9, value=f'=SUMIFS(Backlog!$K:$K,Backlog!$B:$B,A{r},Backlog!$H:$H,"Done")')
        r += 1
    ws.cell(row=r, column=1, value="Total").font = BOLD
    for col in range(2, 10):
        L = get_column_letter(col)
        if col == 7:
            ws.cell(row=r, column=col, value=f"=IF(B{r}=0,0,C{r}/B{r})")
        else:
            ws.cell(row=r, column=col, value=f"=SUM({L}6:{L}{r - 1})")
        ws.cell(row=r, column=col).font = BOLD
    for rr in range(6, r + 1):
        ws.cell(row=rr, column=7).number_format = "0.0%"
        for cc in range(1, 10):
            ws.cell(row=rr, column=cc).border = BORDER
            if ws.cell(row=rr, column=cc).font != BOLD:
                ws.cell(row=rr, column=cc).font = BODY if rr < r else BOLD
    total_row = r

    r += 2
    ws.cell(row=r, column=1, value="Overall completion (effort-weighted)").font = BOLD
    ws.cell(row=r, column=2, value=f"=IF(H{total_row}=0,0,I{total_row}/H{total_row})").number_format = "0.0%"
    ws.cell(row=r, column=2).font = Font(name=FONT, size=14, bold=True, color="1F3864")
    r += 1
    ws.cell(row=r, column=1, value="Roadmap steps done").font = BOLD
    ws.cell(row=r, column=2, value='=COUNTIF(Roadmap!$F:$F,"Done")&" of "&(COUNTA(Roadmap!$A:$A)-1)').font = BODY

    r += 2
    ws.cell(row=r, column=1, value="Platform inventory (from spec)").font = BOLD
    r += 1
    inv = [("Domains", len(domains)), ("Services (Java / Spring Boot)", "=COUNTA(Services!$A:$A)-1"),
           ("Databases", "=COUNTA(Databases!$A:$A)-1"), ("Tables", "=COUNTA(Tables!$A:$A)-1"),
           ("Columns", "=COUNTA(Columns!$A:$A)-1"), ("API endpoints", "=COUNTA('API Endpoints'!$A:$A)-1"),
           ("Events", "=COUNTA(Events!$A:$A)-1"), ("App screens", "=COUNTA('Apps & Screens'!$A:$A)-1"),
           ("Requirements", "=COUNTA(Requirements!$A:$A)-1"),
           ("Requirements with spec gaps", '=COUNTIF(Requirements!$G:$G,"Gap")'),
           ("Open decisions", '=COUNTIF(Decisions!$E:$E,"Open")'),
           ("Open risks", '=COUNTIF(Risks!$F:$F,"Open")')]
    for label, val in inv:
        ws.cell(row=r, column=1, value=label).font = BODY
        c = ws.cell(row=r, column=2, value=val)
        c.font = Font(name=FONT, size=10, color="000000" if str(val).startswith("=") else "0000FF")
        ws.cell(row=r, column=1).border = ws.cell(row=r, column=2).border = BORDER
        r += 1
    ws.cell(row=r + 1, column=1, value="Legend: yellow = editable input · green row = Done · blue = In progress · "
                                       "red = Blocked · amber = In review").font = SUB
    ws.column_dimensions["A"].width = 38
    for col in "BCDEFGHI":
        ws.column_dimensions[col].width = 14

    wb.move_sheet("Dashboard", offset=-wb.index(wb["Dashboard"]))
    path.parent.mkdir(parents=True, exist_ok=True)
    wb.save(path)
    return (f"tasks={n_tasks} screens={n_screens} requirements={n_reqs} "
            f"tracker_rows(tables={n_tables}, columns={n_cols}, endpoints={n_ep})")
