#!/usr/bin/env python3
"""Meridian Core generator.

Reads spec/ (domains, apps, plan) and generates:
  generated/db/<database>/V001__init.sql      PostgreSQL 18 DDL with RLS + outbox
  generated/openapi/<domain>.openapi.yaml     OpenAPI 3.1 per domain
  generated/openapi/meridian.openapi.yaml     bundled OpenAPI 3.1
  generated/asyncapi/meridian.asyncapi.yaml   AsyncAPI 3.1 event catalogue
  generated/docs/data-dictionary.md           tables, columns, API field mapping
  tracker/Meridian_Tracker.xlsx               project tracker (keeps manual edits)

Usage: python tools/generate.py [--check]
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import copy

import yaml

_Dumper = getattr(yaml, "CSafeDumper", yaml.SafeDumper)
_Loader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


def dump_yaml(data) -> str:
    """YAML text via libyaml when available (an order of magnitude faster than the pure-Python dumper)."""
    return yaml.dump(data, Dumper=_Dumper, sort_keys=False, allow_unicode=True, width=120)


def load_yaml(text: str):
    return yaml.load(text, Loader=_Loader)

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "spec"
OUT = ROOT / "generated"

# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------

TYPE_RE = re.compile(r"^(?P<base>[a-z]+)(?:\((?P<arg>[^)]*)\))?$")
SIMPLE_TYPES = {"uuid", "string", "text", "int", "bigint", "amount", "rate", "bool", "date",
                "ts", "json", "currency", "country", "email", "phone", "bytes", "inet"}
ACTION_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}


class SpecError(Exception):
    pass


@dataclass
class Column:
    name: str
    type: str                      # base type, or "enum"
    arg: str | None = None         # string length / enum name
    nn: bool = False
    pk: bool = False
    uq: bool = False
    idx: bool = False
    fk: str | None = None
    ref: str | None = None
    default: str | None = None
    pii: bool = False
    secret: bool = False
    ro: bool = False
    hide: bool = False
    std: bool = False              # injected standard column
    ext: bool = False              # tenant-defined extension slot (writable standard column)
    description: str = ""

    @property
    def in_api(self) -> bool:
        return not (self.hide or self.secret or self.type == "bytes"
                    or self.name in ("tenant_id",))

    @property
    def writable(self) -> bool:
        return self.in_api and not (self.ro or (self.std and not self.ext) or self.pk)


@dataclass
class Table:
    name: str
    domain: str
    service: str
    database: str
    description: str = ""
    prefix: str = ""
    immutable: bool = False
    tenant: bool = True
    operator_only: bool = False   # SEC-15: platform-global registry readable and writable by platform operators only
    std: bool = True
    fr: list = field(default_factory=list)
    columns: list = field(default_factory=list)
    unique: list = field(default_factory=list)
    indexes: list = field(default_factory=list)
    nested: list = field(default_factory=list)   # [(field, target_table, required, description)]
    response_only: list = field(default_factory=list)  # [Column]: returned by the API, never stored
    request_only: list = field(default_factory=list)   # [Column]: accepted on create, never stored

    def col(self, name):
        for c in self.columns:
            if c.name == name:
                return c
        return None


@dataclass
class Action:
    method: str
    path: str
    summary: str
    body: list            # list[Column]
    response: str | None  # table name, "none", or None (= resource table)
    query: list = field(default_factory=list)  # list[Column] from '?name:type&name:type!' (! = required)


@dataclass
class Api:
    resource: str
    path: str
    table: str
    domain: str
    service: str
    ops: list
    filters: list
    actions: list
    description: str = ""
    fr: list = field(default_factory=list)
    # Per-operation scope override: {"list" | "get" | "create" | "update" | "delete" | "METHOD /action-path":
    # [required scope, accepted alternatives...]} (e.g. the cell router's narrow platform:routing-read, with
    # platform:operator still accepted).
    scopes: dict = field(default_factory=dict)


@dataclass
class Event:
    name: str
    table: str
    description: str
    domain: str
    service: str


@dataclass
class Service:
    name: str
    domain: str
    database: str
    language: str
    description: str
    tables: list
    apis: list
    events: list
    # Service-level channel scope overriding the domain's (e.g. channels:agent for the agent channel BFF in the
    # channels domain, whose other service uses the domain scope channels:customer).
    scope: str | None = None


@dataclass
class Domain:
    id: str
    title: str
    phase: int
    description: str
    enums: dict
    services: list
    # One scope for every operation of the domain instead of <domain>:read / <domain>:write (e.g. channels:customer
    # for the customer channel: the token of a signed-in customer, never granted to staff or integration clients).
    scope: str | None = None


def parse_column(text: str, ctx: str) -> Column:
    if "|" in text:
        left, desc = text.split("|", 1)
    else:
        left, desc = text, ""
    toks = left.split()
    if len(toks) < 2:
        raise SpecError(f"{ctx}: column needs '<name> <type>': {text!r}")
    name, typ = toks[0], toks[1]
    if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
        raise SpecError(f"{ctx}: bad column name {name!r}")
    if typ.startswith("enum:") and not typ.endswith("[]"):
        col = Column(name=name, type="enum", arg=typ[5:])
    elif typ == "json[]":
        col = Column(name=name, type="jsonarray")
    elif typ == "json*":
        col = Column(name=name, type="jsonany")
    elif typ.endswith("[]"):
        item = parse_column(f"{name} {typ[:-2]}", ctx)   # validates the element type
        col = Column(name=name, type="typedarray", arg=f"{item.type}:{item.arg or ''}")
    else:
        m = TYPE_RE.match(typ)
        if not m or m.group("base") not in SIMPLE_TYPES:
            raise SpecError(f"{ctx}: unknown type {typ!r} in {text!r}")
        col = Column(name=name, type=m.group("base"), arg=m.group("arg"))
    for mod in toks[2:]:
        if mod in ("nn", "pk", "uq", "idx", "pii", "secret", "ro", "hide"):
            setattr(col, mod, True)
        elif mod.startswith("fk="):
            col.fk = mod[3:]
        elif mod.startswith("ref="):
            col.ref = mod[4:]
        elif mod.startswith("default="):
            col.default = mod[8:]
        else:
            raise SpecError(f"{ctx}: unknown modifier {mod!r} in {text!r}")
    col.description = desc.strip()
    return col


def std_columns(immutable: bool, tenant: bool) -> list:
    cols = [Column("id", "uuid", pk=True, nn=True, std=True, default="uuidv7",
                   description="Unique id (UUIDv7, time-ordered)")]
    if tenant:
        cols.append(Column("tenant_id", "uuid", nn=True, std=True, hide=True,
                           description="Owning tenant; enforced by row-level security"))
    cols += [Column("created_at", "ts", nn=True, std=True, default="now", description="Creation time"),
             Column("created_by", "string", "64", std=True, description="Principal that created the row")]
    if not immutable:
        cols += [Column("updated_at", "ts", nn=True, std=True, default="now", description="Last update time"),
                 Column("updated_by", "string", "64", std=True, description="Principal that last updated the row"),
                 Column("row_version", "int", nn=True, std=True, default="1",
                        description="Optimistic lock version; send as If-Match")]
    return cols


EXT_SLOTS = 5


def ext_columns() -> list:
    """Spare, API-writable fields on every table; labels/validation per tenant in custom_field_definition."""
    return [Column(f"custom_field_{i}", "string", "500", std=True, ext=True,
                   description=f"Tenant-defined custom field {i}; label, type and validation configured "
                               f"in custom_field_definition") for i in range(1, EXT_SLOTS + 1)]


def parse_action(text: str, ctx: str) -> Action:
    parts = [p.strip() for p in text.split("|")]
    head = parts[0].split()
    if len(head) != 2 or head[0] not in ACTION_METHODS:
        raise SpecError(f"{ctx}: action must start 'METHOD /path': {text!r}")
    summary = parts[1] if len(parts) > 1 else ""
    body = []
    if len(parts) > 2 and parts[2]:
        for f in parts[2].split(","):
            f = f.strip()
            if f:
                body.append(parse_column(f, ctx))
    response = parts[3] if len(parts) > 3 and parts[3] else None
    path, _, qs = head[1].partition("?")
    query = []
    for q in filter(None, qs.split("&")):
        name, _, typ = q.partition(":")
        required = typ.endswith("!")
        query.append(parse_column(f"{name} {typ.rstrip('!') or 'string'}{' nn' if required else ''}", ctx))
    return Action(head[0], path, summary, body, response, query)


_DESC_RE = re.compile(r'^(\s*(?:- )?(?:description|title): )(?![>|"\'])(.+)$', re.M)


def _quote_descriptions(text: str) -> str:
    """Plain one-line description/title values may contain ': '; quote them so YAML parses."""
    return _DESC_RE.sub(lambda m: m.group(1) + '"' + m.group(2).replace("\\", "\\\\").replace('"', '\\"') + '"', text)


def parse_nested(text: str, ctx: str):
    """'<field> list:<table> [nn] | description' -> nested child rows sent with the parent on create."""
    left, _, desc = text.partition("|")
    toks = left.split()
    if len(toks) < 2 or not toks[1].startswith("list:"):
        raise SpecError(f"{ctx}: nested must be '<field> list:<table> [nn] | desc': {text!r}")
    return toks[0], toks[1][5:], "nn" in toks[2:], desc.strip()


def load_domains() -> list:
    domains = []
    for path in sorted((SPEC / "domains").glob("*.yaml")):
        raw = load_yaml(_quote_descriptions(path.read_text(encoding="utf-8")))
        did = raw["domain"]
        enums = raw.get("enums", {}) or {}
        services = []
        for s in raw.get("services", []):
            sname, db = s["name"], s["database"]
            tables, apis, events = [], [], []
            for t in s.get("tables", []) or []:
                ctx = f"{path.name}:{t['name']}"
                imm = bool(t.get("immutable", False))
                ten = bool(t.get("tenant", True))
                std = bool(t.get("std", True))
                cols = std_columns(imm, ten) if std else []
                for c in t.get("columns", []) or []:
                    cols.append(parse_column(c, ctx))
                if t.get("ext", True):
                    cols.extend(ext_columns())
                tables.append(Table(
                    name=t["name"], domain=did, service=sname, database=db,
                    description=t.get("description", ""), prefix=t.get("prefix", ""),
                    immutable=imm, tenant=ten, std=std, fr=t.get("fr", []) or [],
                    operator_only=bool(t.get("operator_only", False)),
                    columns=cols, unique=t.get("unique", []) or [], indexes=t.get("indexes", []) or [],
                    nested=[parse_nested(x, ctx) for x in (t.get("nested", []) or [])],
                    response_only=[parse_column(x, ctx) for x in (t.get("response_only", []) or [])],
                    request_only=[parse_column(x, ctx) for x in (t.get("request_only", []) or [])]))
            for a in s.get("apis", []) or []:
                ctx = f"{path.name}:{a['resource']}"
                apis.append(Api(
                    resource=a["resource"], path=a["path"], table=a["table"], domain=did, service=sname,
                    ops=a.get("ops", []) or [], filters=a.get("filters", []) or [],
                    actions=[parse_action(x, ctx) for x in (a.get("actions", []) or [])],
                    description=a.get("description", ""), fr=a.get("fr", []) or [],
                    scopes={str(k): ([v] if isinstance(v, str) else list(v))
                            for k, v in (a.get("scopes", {}) or {}).items()}))
            for e in s.get("events", []) or []:
                parts = [p.strip() for p in e.split("|")]
                events.append(Event(parts[0], parts[1] if len(parts) > 1 else "",
                                    parts[2] if len(parts) > 2 else "", did, sname))
            sscope = s.get("scope")
            if sscope is not None and not re.fullmatch(rf"{re.escape(did)}:[a-z][a-z0-9_-]*", str(sscope)):
                raise SpecError(f"{path.name}: service {sname} scope must be '{did}:<name>', got {sscope!r}")
            services.append(Service(sname, did, db, s.get("language", "java"),
                                    s.get("description", ""), tables, apis, events, sscope))
        scope = raw.get("scope")
        if scope is not None and not re.fullmatch(rf"{re.escape(did)}:[a-z][a-z0-9_-]*", str(scope)):
            raise SpecError(f"{path.name}: scope must be '{did}:<name>', got {scope!r}")
        domains.append(Domain(did, raw.get("title", did), int(raw.get("phase", 1)),
                              raw.get("description", ""), enums, services, scope))
    return domains


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

def validate(domains, focus: str | None = None, strict: bool = True) -> tuple[dict, dict]:
    """focus: only report errors for this domain id. strict=False: unknown ref= targets are warnings."""
    errors, warnings = [], []
    all_enums: dict = {}
    tables_by_db: dict = defaultdict(dict)
    tables_global: dict = {}
    for d in domains:
        for en, vals in d.enums.items():
            if en in all_enums and all_enums[en] != vals:
                errors.append(f"enum {en} defined twice with different values ({d.id})")
            all_enums[en] = vals
    for d in domains:
        for s in d.services:
            for t in s.tables:
                if t.name in tables_by_db[t.database]:
                    errors.append(f"duplicate table {t.database}.{t.name}")
                tables_by_db[t.database][t.name] = t
                if t.name in tables_global and tables_global[t.name].database != t.database:
                    errors.append(f"table name {t.name} used in two databases "
                                  f"({tables_global[t.name].database}, {t.database}); names must be global")
                tables_global[t.name] = t
                seen = set()
                for c in t.columns:
                    if c.name in seen:
                        errors.append(f"{t.name}.{c.name} duplicated")
                    seen.add(c.name)
                if not any(c.pk for c in t.columns):
                    errors.append(f"{t.name} has no primary key")
    for d in domains:
        for s in d.services:
            for t in s.tables:
                for c in t.columns:
                    if c.type == "typedarray" and c.arg.startswith("enum:") and c.arg[5:] not in all_enums:
                        errors.append(f"{c.name}: unknown enum in array type {c.arg}")
                    if c.type == "enum" and c.arg not in all_enums:
                        errors.append(f"{t.name}.{c.name}: unknown enum {c.arg}")
                    if c.type == "enum" and c.default and c.default not in all_enums.get(c.arg, []):
                        errors.append(f"{t.name}.{c.name}: default {c.default} not in enum {c.arg}")
                    if c.fk and c.fk not in tables_by_db[t.database]:
                        errors.append(f"{t.name}.{c.name}: fk={c.fk} not in database {t.database}"
                                      f" (use ref= for other services)")
                    if c.ref and c.ref not in tables_global:
                        (errors if strict else warnings).append(
                            f"[{d.id}] {t.name}.{c.name}: ref={c.ref} is not a known table")
                for _f, target, _r, _d in t.nested:
                    if target not in tables_by_db[t.database]:
                        errors.append(f"{t.name}: nested list:{target} must be a table in {t.database}")
                for group in t.unique + t.indexes:
                    for cn in group:
                        if not t.col(cn):
                            errors.append(f"{t.name}: unique/index column {cn} missing")
            for a in s.apis:
                t = tables_by_db[s.database].get(a.table)
                if not t:
                    errors.append(f"api {a.resource}: table {a.table} not in {s.database}")
                    continue
                for op in a.ops:
                    if op not in ("list", "get", "create", "update", "delete"):
                        errors.append(f"api {a.resource}: unknown op {op}")
                for f in a.filters:
                    if not t.col(f):
                        errors.append(f"api {a.resource}: filter {f} is not a column of {t.name}")
                op_keys = set(a.ops) | {f"{act.method} {act.path}" for act in a.actions}
                for k, v in a.scopes.items():
                    if k not in op_keys:
                        errors.append(f"api {a.resource}: scopes key {k!r} is not an operation of the resource "
                                      f"(one of {sorted(op_keys)})")
                    if not v or any(not re.fullmatch(r"[a-z][a-z0-9_-]*:[a-z0-9_*-]+", str(x)) for x in v):
                        errors.append(f"api {a.resource}: scopes[{k!r}] must be a list of '<domain>:<name>' scopes")
                for act in a.actions:
                    if act.response and act.response not in ("none", "object") and "/" not in act.response                             and act.response.rstrip("[]") not in tables_global:
                        errors.append(f"api {a.resource}: action response {act.response} unknown")
                    for c in act.body:
                        if c.type == "typedarray" and c.arg.startswith("enum:") and c.arg[5:] not in all_enums:
                            errors.append(f"{c.name}: unknown enum in array type {c.arg}")
                        if c.type == "enum" and c.arg not in all_enums:
                            errors.append(f"api {a.resource}: body field {c.name} unknown enum {c.arg}")
            for e in s.events:
                if e.table not in tables_global:
                    errors.append(f"event {e.name}: table {e.table} unknown")
    # API surface hygiene (errors since all domains were cleaned): unique resource names (they become tags / *Api classes) and
    # no ambiguous paths (a literal segment where another path of the same shape has a variable).
    seen_res = {}
    shapes = []
    for d in domains:
        for sv in d.services:
            for api in sv.apis:
                if api.resource in seen_res and seen_res[api.resource] != d.id:
                    errors.append(f"[{d.id}] resource name '{api.resource}' also used in domain {seen_res[api.resource]}")
                seen_res[api.resource] = d.id
                bases = [api.path, api.path + "/{id}"] + [api.path if act.path == "/" else api.path + act.path
                                                          for act in api.actions]
                shapes += [(d.id, b) for b in bases]
    for i, (da, pa) in enumerate(shapes):
        sa = pa.strip("/").split("/")
        for db_, pb in shapes[i + 1:]:
            sb = pb.strip("/").split("/")
            if pa == pb or len(sa) != len(sb):
                continue
            ok = all(x == y or x.startswith("{") or y.startswith("{") for x, y in zip(sa, sb))
            a_more = any(not x.startswith("{") and y.startswith("{") for x, y in zip(sa, sb))
            b_more = any(x.startswith("{") and not y.startswith("{") for x, y in zip(sa, sb))
            if ok and a_more and b_more:  # crossed specificity: neither path wins for some URL
                errors.append(f"[{da}] ambiguous paths: {pa} vs {pb}")
    if focus:
        errors = [e for e in errors if _belongs(e, focus, domains)]
        warnings = [w for w in warnings if w.startswith(f"[{focus}]")]
    for w in warnings:
        print("warning:", w)
    if errors:
        raise SpecError("Spec validation failed:\n  - " + "\n  - ".join(errors))
    return all_enums, tables_global


def _belongs(msg: str, focus: str, domains) -> bool:
    """True if an error message mentions a table, api, event or database of the focus domain."""
    if msg.startswith(f"[{focus}]") or f"({focus})" in msg:
        return True
    for d in domains:
        if d.id == focus:
            names = ({t.name for s in d.services for t in s.tables} | {a.resource for s in d.services for a in s.apis}
                     | {e.name for s in d.services for e in s.events} | {s.database for s in d.services})
            return any(re.search(rf"(?<![\w-]){re.escape(n)}(?![\w-])", msg) for n in names)
    return False


# ---------------------------------------------------------------------------
# DDL
# ---------------------------------------------------------------------------

def pg_type(c: Column) -> str:
    return {
        "uuid": "uuid", "text": "text", "int": "integer", "bigint": "bigint",
        "amount": "numeric(28,8)", "rate": "numeric(18,10)", "bool": "boolean", "date": "date",
        "ts": "timestamptz", "json": "jsonb", "jsonarray": "jsonb", "jsonany": "jsonb", "typedarray": "jsonb", "currency": "char(3)", "country": "char(2)",
        "email": "varchar(320)", "phone": "varchar(20)", "bytes": "bytea", "inet": "inet",
        "enum": "varchar(40)",
    }.get(c.type) or f"varchar({c.arg or 255})"


def pg_default(c: Column) -> str | None:
    v = c.default
    if v is None:
        return None
    if v == "now":
        return "now()"
    if v == "uuidv7":
        return "uuidv7()"
    if c.type in ("int", "bigint", "amount", "rate"):
        return v
    if c.type == "bool":
        return "true" if v.lower() == "true" else "false"
    if c.type == "json":
        return f"'{v}'::jsonb"
    return "'" + v.replace("'", "''") + "'"


def sql_str(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def gen_ddl(domains, enums) -> int:
    count = 0
    by_db = dict()
    for d in domains:
        for s in d.services:
            by_db.setdefault(s.database, (d, s, []))[2].extend(s.tables)
    for db, (d, s, tables) in by_db.items():
        schema = db[:-3] if db.endswith("_db") else db
        L = [f"-- Generated by tools/generate.py from spec/domains/{d.id}.yaml. DO NOT EDIT.",
             f"-- Database: {db}   Service: {s.name}   Domain: {d.title}",
             "-- Target: PostgreSQL 18+ (uuidv7) or a Postgres-compatible distributed SQL engine.",
             "",
             f"CREATE SCHEMA IF NOT EXISTS {schema};",
             f"SET search_path TO {schema};",
             "",
             "-- Blocks UPDATE/DELETE on append-only (immutable) tables.",
             "CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$",
             "BEGIN",
             "  RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = '55000';",
             "END $$;",
             "",
             "CREATE OR REPLACE FUNCTION touch_row() RETURNS trigger LANGUAGE plpgsql AS $$",
             "BEGIN",
             "  NEW.updated_at := now();",
             "  NEW.row_version := OLD.row_version + 1;",
             "  RETURN NEW;",
             "END $$;",
             ""]
        # order tables so FK targets come first
        ordered, placed = [], set()
        pending = list(tables)
        while pending:
            progressed = False
            for t in list(pending):
                deps = {c.fk for c in t.columns if c.fk and c.fk != t.name}
                if deps <= placed:
                    ordered.append(t)
                    placed.add(t.name)
                    pending.remove(t)
                    progressed = True
            if not progressed:  # cycle: emit remaining, FKs added later would be needed
                ordered.extend(pending)
                break
        for t in ordered:
            L.append(f"-- {t.name}: {t.description}")
            defs = []
            for c in t.columns:
                parts = [f"  {c.name:<28} {pg_type(c)}"]
                if c.pk:
                    parts.append("PRIMARY KEY")
                if c.nn and not c.pk:
                    parts.append("NOT NULL")
                dflt = pg_default(c)
                if dflt:
                    parts.append(f"DEFAULT {dflt}")
                if c.fk:
                    parts.append(f"REFERENCES {c.fk}(id)")
                if c.type == "enum":
                    vals = ", ".join(sql_str(v) for v in enums[c.arg])
                    parts.append(f"CHECK ({c.name} IN ({vals}))")
                defs.append(" ".join(parts))
            for c in t.columns:
                if c.uq:
                    cols = f"tenant_id, {c.name}" if t.tenant else c.name
                    defs.append(f"  CONSTRAINT uq_{t.name}_{c.name} UNIQUE ({cols})")
            for i, grp in enumerate(t.unique):
                defs.append(f"  CONSTRAINT uq_{t.name}_{i + 1} UNIQUE ({', '.join(grp)})")
            L.append(f"CREATE TABLE {t.name} (")
            L.append(",\n".join(defs))
            L.append(");")
            L.append(f"COMMENT ON TABLE {t.name} IS {sql_str(t.description)};")
            for c in t.columns:
                if c.description:
                    tag = " [PII]" if c.pii else (" [SECRET]" if c.secret else "")
                    L.append(f"COMMENT ON COLUMN {t.name}.{c.name} IS {sql_str(c.description + tag)};")
            if t.tenant:
                L.append(f"CREATE INDEX ix_{t.name}_tenant ON {t.name} (tenant_id, created_at);")
            for c in t.columns:
                if (c.idx or c.fk or c.ref) and not c.pk and not c.uq:
                    L.append(f"CREATE INDEX ix_{t.name}_{c.name} ON {t.name} ({c.name});")
            for i, grp in enumerate(t.indexes):
                L.append(f"CREATE INDEX ix_{t.name}_c{i + 1} ON {t.name} ({', '.join(grp)});")
            if t.tenant:
                L.append(f"ALTER TABLE {t.name} ENABLE ROW LEVEL SECURITY;")
                L.append(f"CREATE POLICY tenant_isolation ON {t.name} "
                         f"USING (tenant_id = current_setting('app.tenant_id')::uuid);")
            if t.immutable:
                L.append(f"CREATE TRIGGER trg_{t.name}_append_only BEFORE UPDATE OR DELETE ON {t.name} "
                         f"FOR EACH ROW EXECUTE FUNCTION forbid_mutation();")
            elif t.std:
                L.append(f"CREATE TRIGGER trg_{t.name}_touch BEFORE UPDATE ON {t.name} "
                         f"FOR EACH ROW EXECUTE FUNCTION touch_row();")
            L.append("")
            count += 1
        L += ["-- Transactional outbox: events are written here in the same transaction as the",
              "-- state change and relayed to Kafka by the outbox relay (Debezium).",
              "CREATE TABLE outbox (",
              "  id              uuid PRIMARY KEY DEFAULT uuidv7(),",
              "  tenant_id       uuid NOT NULL,",
              "  aggregate_type  varchar(64) NOT NULL,",
              "  aggregate_id    uuid NOT NULL,",
              "  event_type      varchar(128) NOT NULL,",
              "  payload         jsonb NOT NULL,",
              "  headers         jsonb NOT NULL DEFAULT '{}'::jsonb,",
              "  created_at      timestamptz NOT NULL DEFAULT now()",
              ");",
              "CREATE INDEX ix_outbox_created ON outbox (created_at);",
              "",
              "-- Idempotency keys for POST requests (24h retention).",
              "CREATE TABLE idempotency_key (",
              "  tenant_id       uuid NOT NULL,",
              "  key             varchar(128) NOT NULL,",
              "  request_hash    varchar(64) NOT NULL,",
              "  response_status integer,",
              "  response_body   jsonb,",
              "  created_at      timestamptz NOT NULL DEFAULT now(),",
              "  PRIMARY KEY (tenant_id, key)",
              ");",
              ""]
        L += ["-- Runtime access for the non-superuser application role (RLS does not apply to superusers).",
              "DO $$",
              "BEGIN",
              "  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'meridian_app') THEN",
              f"    GRANT USAGE ON SCHEMA {schema} TO meridian_app;",
              f"    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {schema} TO meridian_app;",
              f"    ALTER DEFAULT PRIVILEGES IN SCHEMA {schema} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO meridian_app;",
              "  END IF;",
              "END $$;",
              ""]
        p = OUT / "db" / db / "V001__init.sql"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("\n".join(L), encoding="utf-8", newline="\n")
    return count


# ---------------------------------------------------------------------------
# OpenAPI
# ---------------------------------------------------------------------------

def pascal(s: str) -> str:
    return "".join(w.capitalize() for w in re.split(r"[_\-\s/]+", s) if w)


def camel(s: str) -> str:
    p = pascal(s)
    return p[:1].lower() + p[1:]


def oa_type(c: Column, enums) -> dict:
    t = c.type
    if t == "uuid":
        s = {"type": "string", "format": "uuid"}
    elif t == "string":
        s = {"type": "string", "maxLength": int(c.arg or 255)}
    elif t == "text":
        s = {"type": "string"}
    elif t == "int":
        s = {"type": "integer", "format": "int32"}
    elif t == "bigint":
        s = {"type": "integer", "format": "int64"}
    elif t in ("amount", "rate"):
        # No regex: models map this to BigDecimal, which enforces numeric syntax on parse.
        s = {"type": "string", "format": "decimal", "examples": ["125.50"]}
    elif t == "bool":
        s = {"type": "boolean"}
    elif t == "date":
        s = {"type": "string", "format": "date"}
    elif t == "ts":
        s = {"type": "string", "format": "date-time"}
    elif t == "json":
        s = {"type": "object", "additionalProperties": True}
    elif t == "jsonarray":
        s = {"type": "array", "items": {}}
    elif t == "jsonany":
        s = {"description": "Any JSON value (string, number, boolean, array or object)"}
    elif t == "typedarray":
        etype, earg = c.arg.split(":", 1)
        s = {"type": "array", "items": oa_type(Column(name=c.name, type=etype, arg=earg or None), enums)}
    elif t == "currency":
        s = {"type": "string", "pattern": "^[A-Z]{3}$", "description": "ISO 4217"}
    elif t == "country":
        s = {"type": "string", "pattern": "^[A-Z]{2}$", "description": "ISO 3166-1 alpha-2"}
    elif t == "email":
        s = {"type": "string", "format": "email", "maxLength": 320}
    elif t == "phone":
        s = {"type": "string", "pattern": r"^\+[1-9]\d{6,14}$", "description": "E.164"}
    elif t == "inet":
        s = {"type": "string", "description": "IPv4 or IPv6 address"}
    elif t == "enum":
        s = {"type": "string", "enum": list(enums[c.arg])}
    else:
        s = {"type": "string"}
    if c.description:
        extra = s.get("description")
        s["description"] = f"{c.description} ({extra})" if extra else c.description
    return s


NESTED_PATH_COLUMNS: dict = {}   # table -> columns supplied by a parent path segment (e.g. customer_id)


def table_schemas(t: Table, enums) -> dict:
    name = pascal(t.name)
    props, req = {}, []
    for c in t.columns:
        if not c.in_api:
            continue
        sch = oa_type(c, enums)
        if not c.writable:
            sch["readOnly"] = True
        if not c.nn:
            sch["type"] = [sch["type"], "null"]
        props[c.name] = sch
        if c.nn:
            req.append(c.name)
    full = {"type": "object", "description": t.description, "properties": props, "required": req}
    cprops, creq = {}, []
    for c in t.columns:
        if c.writable:
            s = oa_type(c, enums)
            cprops[c.name] = s
            if c.nn and c.default is None:
                creq.append(c.name)
    # Columns taken from a parent path segment (/v1/parents/{parent_id}/children) are not required in the body.
    creq = [c for c in creq if c not in NESTED_PATH_COLUMNS.get(t.name, set())]
    create = {"type": "object", "properties": cprops}
    if creq:
        create["required"] = creq
    update = {"type": "object", "properties": dict(
        (c.name, oa_type(c, enums)) for c in t.columns if c.writable and not t.immutable)}
    for c in t.request_only:
        sch = oa_type(c, enums)
        sch["description"] = (c.description + " " if c.description else "") + "(write-only; not stored as a column)"
        sch["writeOnly"] = True
        create["properties"][c.name] = sch
        if c.nn:
            create.setdefault("required", []).append(c.name)
    for c in t.response_only:
        sch = oa_type(c, enums)
        sch["readOnly"] = True
        sch["description"] = (c.description + " " if c.description else "") + "(returned once; never stored)"
        full["properties"][c.name] = sch
    for fname, target, required, desc in t.nested:
        tp = pascal(target)
        full["properties"][fname] = {"type": "array", "items": {"$ref": f"#/components/schemas/{tp}"},
                                     "readOnly": True, "description": desc}
        create["properties"][fname] = {"type": "array", "items": {"$ref": f"#/components/schemas/{tp}Create"},
                                       "minItems": 1, "description": desc}
        if required:
            create.setdefault("required", []).append(fname)
    return {name: full, f"{name}Create": create, f"{name}Update": update}


COMMON_COMPONENTS = {
    "schemas": {
        "Problem": {
            "type": "object",
            "description": "RFC 9457 problem details",
            "properties": {
                "type": {"type": "string", "format": "uri"},
                "title": {"type": "string"},
                "status": {"type": "integer"},
                "code": {"type": "string", "description": "Stable machine-readable error code"},
                "detail": {"type": "string"},
                "instance": {"type": "string"},
                "request_id": {"type": "string"},
                "errors": {"type": "array", "items": {"type": "object", "properties": {
                    "field": {"type": "string"}, "code": {"type": "string"}, "message": {"type": "string"}}}},
            },
            "required": ["type", "title", "status", "code"],
        },
        "Money": {
            "type": "object",
            "properties": {"amount": {"type": "string", "format": "decimal"},
                           "currency": {"type": "string", "pattern": "^[A-Z]{3}$"}},
            "required": ["amount", "currency"],
        },
    },
    "parameters": {
        "IdempotencyKey": {"name": "Idempotency-Key", "in": "header", "required": True,
                           "description": "Unique key per logical request; replays return the original response for 24h",
                           "schema": {"type": "string", "maxLength": 128}},
        "IfMatch": {"name": "If-Match", "in": "header", "required": True,
                    "description": "Current row_version of the resource (optimistic locking)",
                    "schema": {"type": "string"}},
        "Limit": {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 200, "default": 50}},
        "Cursor": {"name": "cursor", "in": "query", "schema": {"type": "string"}},
        "CreatedAfter": {"name": "created_after", "in": "query", "schema": {"type": "string", "format": "date-time"}},
        "CreatedBefore": {"name": "created_before", "in": "query", "schema": {"type": "string", "format": "date-time"}},
        "TenantId": {"name": "X-Tenant-Id", "in": "header", "required": False,
                     "description": "Platform operators only; tenants are resolved from the token",
                     "schema": {"type": "string", "format": "uuid"}},
    },
    "responses": {
        code: {"description": desc, "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}
        for code, desc in [("BadRequest", "Malformed request"), ("Unauthorized", "Missing or invalid credentials"),
                           ("Forbidden", "Not allowed"), ("NotFound", "Resource not found"),
                           ("Conflict", "Version or idempotency conflict"),
                           ("Unprocessable", "Business rule violation"), ("TooManyRequests", "Rate limit exceeded")]
    },
    "securitySchemes": {
        "oauth2": {"type": "oauth2", "flows": {
            "clientCredentials": {"tokenUrl": "https://auth.meridian.example/oauth2/token", "scopes": {}},
            "authorizationCode": {"authorizationUrl": "https://auth.meridian.example/oauth2/authorize",
                                  "tokenUrl": "https://auth.meridian.example/oauth2/token", "scopes": {}}}},
    },
}

ERR = lambda *codes: {c: {"$ref": f"#/components/responses/{n}"} for c, n in codes}
STD_ERRS = ERR(("400", "BadRequest"), ("401", "Unauthorized"), ("403", "Forbidden"), ("429", "TooManyRequests"))


def path_params(path: str, enums=None) -> list:
    return [{"name": p, "in": "path", "required": True,
             "schema": {"type": "string", "format": "uuid"} if p == "id" or p.endswith("_id") else {"type": "string"}}
            for p in re.findall(r"{([^}]+)}", path)]


@dataclass
class Endpoint:
    op_id: str
    method: str
    path: str
    summary: str
    domain: str
    service: str
    resource: str
    table: str
    kind: str
    scope: str
    request_schema: str
    response_schema: str
    fr: list
    public: bool = False


def prune_components(doc: dict) -> None:
    """Remove components nothing references (repeat until stable: schemas can reference schemas)."""
    import json
    ref_re = re.compile(r'"#/components/(schemas|parameters|responses)/([^"]+)"')
    sections = ("schemas", "parameters", "responses")
    edges = {(s, n): set(ref_re.findall(json.dumps(c)))
             for s in sections for n, c in doc["components"].get(s, {}).items()}
    todo = list(ref_re.findall(json.dumps(doc["paths"])))
    seen = set()
    while todo:
        key = todo.pop()
        if key in seen:
            continue
        seen.add(key)
        todo.extend(edges.get(key, ()))
    for s in sections:
        for n in list(doc["components"].get(s, {})):
            if (s, n) not in seen:
                del doc["components"][s][n]


def page_schema(doc: dict, bundle: dict, name: str) -> None:
    """Named cursor-page wrapper <Name>Page = {data: [<Name>], next_cursor, has_more}."""
    sch = {"type": "object", "description": f"One page of {name} results (newest first)",
           "properties": {"data": {"type": "array", "items": {"$ref": f"#/components/schemas/{name}"}},
                          "next_cursor": {"type": ["string", "null"],
                                          "description": "Pass as ?cursor= to fetch the next page"},
                          "has_more": {"type": "boolean"}},
           "required": ["data", "has_more"]}
    doc["components"]["schemas"][f"{name}Page"] = sch
    bundle["components"]["schemas"][f"{name}Page"] = sch


def gen_openapi(domains, enums, tables_global) -> list:
    NESTED_PATH_COLUMNS.clear()
    for d0 in domains:
        for s0 in d0.services:
            for a0 in s0.apis:
                if "create" in a0.ops:
                    tbl = tables_global[a0.table]
                    for pname in re.findall(r"{([^}]+)}", a0.path):
                        if tbl.col(pname):
                            NESTED_PATH_COLUMNS.setdefault(a0.table, set()).add(pname)
    endpoints: list = []
    bundle = {"openapi": "3.1.0",
              "info": {"title": "Meridian Core API", "version": "1.0.0",
                       "description": "Complete API of the Meridian Core banking platform. Generated from spec/.",
                       "contact": {"name": "Meridian Platform Team"}, "license": {"name": "Proprietary", "identifier": "LicenseRef-Proprietary"}},
              "servers": [{"url": "https://api.meridian.example", "description": "Production"},
                          {"url": "https://sandbox.api.meridian.example", "description": "Sandbox"}],
              "security": [{"oauth2": []}], "tags": [], "paths": {},
              "components": copy.deepcopy(COMMON_COMPONENTS)}
    used_ids = set()
    for d in domains:
        doc = {"openapi": "3.1.0",
               "info": {"title": f"Meridian Core — {d.title} API", "version": "1.0.0", "description": d.description, "license": {"name": "Proprietary", "identifier": "LicenseRef-Proprietary"}},
               "servers": bundle["servers"], "security": [{"oauth2": []}], "tags": [], "paths": {},
               "components": copy.deepcopy(COMMON_COMPONENTS)}
        channel_scopes = [x for x in [d.scope] + [sv.scope for sv in d.services] if x]
        scopes = ({sc: f"{d.title} (signed-in {sc.split(':', 1)[1]})" for sc in channel_scopes} if channel_scopes
                  else {f"{d.id}:read": f"Read {d.title}", f"{d.id}:write": f"Change {d.title}"})
        for flow in doc["components"]["securitySchemes"]["oauth2"]["flows"].values():
            flow["scopes"] = dict(scopes)
        for flow in bundle["components"]["securitySchemes"]["oauth2"]["flows"].values():
            flow["scopes"].update(scopes)
        needed_tables = set()
        for s in d.services:
            for a in s.apis:
                t = tables_global[a.table]
                needed_tables.add(a.table)
                needed_tables.update(n[1] for n in t.nested)
                tag = " ".join(w.capitalize() for w in a.resource.split("-"))
                doc["tags"].append({"name": tag, "description": a.description or t.description})
                bundle["tags"].append({"name": tag, "description": a.description or t.description,
                                       "x-domain": d.title})
                S = pascal(t.name)
                ref = lambda n: {"$ref": f"#/components/schemas/{n}"}
                base_params = path_params(a.path)

                def add(method, path, op, kind, summary, request=None, response=None, status="200",
                        params=None, scope="read", extra_errs=None, req_schema="", resp_schema="",
                        content_type="application/json", public=False, op_key=None):
                    oid = op
                    i = 2
                    while oid in used_ids:
                        oid = f"{op}{i}"
                        i += 1
                    used_ids.add(oid)
                    granted = s.scope or d.scope or f"{d.id}:{scope}"
                    o = {"operationId": oid, "summary": summary, "tags": [tag],
                         "security": [{"oauth2": [granted]}],
                         "parameters": (params or []), "responses": {}}
                    if request is not None:
                        o["requestBody"] = {"required": True, "content": {"application/json": {"schema": request}}}
                    if response is None:
                        o["responses"][status] = {"description": "No content"}
                    else:
                        o["responses"][status] = {"description": "OK" if status == "200" else "Created",
                                                  "content": {content_type: {"schema": response}}}
                    o["responses"].update(STD_ERRS)
                    if extra_errs:
                        o["responses"].update(extra_errs)
                    if a.fr or t.fr:
                        o["x-requirements"] = sorted(set(a.fr + t.fr))
                    o["x-service"] = s.name
                    if public:
                        o["security"] = []
                        o["x-public"] = True
                        o["description"] = ("Public endpoint for an external provider callback: no bearer token; the "
                                            "service verifies the provider signature and resolves the tenant from "
                                            "the referenced entity.")
                    else:
                        # Platform-global tables (no tenant column) are shared by every tenant: only platform
                        # operators (platform:operator, SEC-15) may change them; reads use the normal domain scope,
                        # except operator_only tables (tenant registry, cells), which operators alone may read.
                        operator = not (s.scope or d.scope) and ((scope == "write" and not t.tenant) or t.operator_only)
                        need = "platform:operator" if operator else granted
                        accepted = [need]
                        override = a.scopes.get(op_key) if op_key else None
                        if override:
                            # spec `scopes:` override: the first scope is required, the others are accepted instead
                            need, accepted = override[0], list(dict.fromkeys(override))
                        if operator or override:
                            o["security"] = [{"oauth2": [sc]} for sc in accepted]
                            for target in (doc, bundle):
                                for flow in target["components"]["securitySchemes"]["oauth2"]["flows"].values():
                                    for sc in accepted:
                                        flow["scopes"].setdefault(sc, "Platform operator (operator tenant or service only)"
                                                                  if sc == "platform:operator" else f"Narrow scope {sc}")
                        if len(accepted) > 1:
                            alts = ", ".join(f'"{x}"' for x in accepted[1:])
                            o["x-operation-extra-annotation"] = (f'@io.meridian.web.security.RequiredScope(value = '
                                                                 f'"{need}", anyOf = {{{alts}}})')
                            o["x-accepted-scopes"] = accepted
                        else:
                            o["x-operation-extra-annotation"] = f'@io.meridian.web.security.RequiredScope("{need}")'
                        o["x-required-scope"] = need
                    o["x-table"] = t.name
                    if kind in ("list", "get", "create", "update", "delete"):
                        o["x-crud"] = True
                        o[f"x-crud-{kind}"] = True
                    for target in (doc, bundle):
                        target["paths"].setdefault(path, {})[method] = o
                    endpoints.append(Endpoint(oid, method.upper(), path, summary, d.id, s.name, a.resource,
                                              t.name, kind, "public" if public else granted, req_schema,
                                              resp_schema, sorted(set(a.fr + t.fr)), public))

                rn = camel(a.resource)
                noun = a.resource.replace("-", " ")
                if "list" in a.ops:
                    fparams = []
                    for fcol in list(a.filters) + [c.name for c in t.columns if c.ext and c.name not in a.filters]:
                        c = t.col(fcol)
                        sch = oa_type(c, enums)
                        sch.pop("description", None)
                        fparams.append({"name": fcol, "in": "query", "schema": sch})
                    page_schema(doc, bundle, S)
                    lst = ref(f"{S}Page")
                    add("get", a.path, f"{rn}List", "list", f"List {noun}", response=lst, op_key="list",
                        params=base_params + [{"$ref": "#/components/parameters/Limit"},
                                              {"$ref": "#/components/parameters/Cursor"},
                                              {"$ref": "#/components/parameters/CreatedAfter"},
                                              {"$ref": "#/components/parameters/CreatedBefore"}] + fparams,
                        resp_schema=f"{S}[]")
                if "create" in a.ops:
                    add("post", a.path, f"{rn}Create", "create", f"Create {noun[:-1] if noun.endswith('s') else noun}",
                        request=ref(f"{S}Create"), response=ref(S), status="201", op_key="create",
                        params=base_params + [{"$ref": "#/components/parameters/IdempotencyKey"}], scope="write",
                        extra_errs=ERR(("409", "Conflict"), ("422", "Unprocessable")),
                        req_schema=f"{S}Create", resp_schema=S)
                ipath = a.path + "/{id}"
                iparams = path_params(ipath)
                if "get" in a.ops:
                    add("get", ipath, f"{rn}Get", "get", f"Retrieve {noun[:-1] if noun.endswith('s') else noun}",
                        response=ref(S), params=iparams, extra_errs=ERR(("404", "NotFound")), resp_schema=S,
                        op_key="get")
                if "update" in a.ops:
                    add("patch", ipath, f"{rn}Update", "update", f"Update {noun[:-1] if noun.endswith('s') else noun}",
                        request=ref(f"{S}Update"), response=ref(S), op_key="update",
                        params=iparams + [{"$ref": "#/components/parameters/IfMatch"}], scope="write",
                        extra_errs=ERR(("404", "NotFound"), ("409", "Conflict"), ("422", "Unprocessable")),
                        req_schema=f"{S}Update", resp_schema=S)
                if "delete" in a.ops:
                    add("delete", ipath, f"{rn}Delete", "delete", f"Delete {noun[:-1] if noun.endswith('s') else noun}",
                        params=iparams, status="204", scope="write", extra_errs=ERR(("404", "NotFound")),
                        op_key="delete")
                for act in a.actions:
                    # "/" addresses the resource path itself (/v1/me/cards); action paths made only of path
                    # parameters (/{card_id}) are named by verb: <resource>List / Get / Create / Update / Delete.
                    full = a.path if act.path == "/" else a.path + act.path
                    segs = [x for x in act.path.split("/") if x and not x.startswith("{")]
                    if not segs and (act.path == "/" or "{" in act.path):
                        verb = {"GET": "List" if (act.response or "").endswith("[]") else "Get", "POST": "Create",
                                "PUT": "Replace", "PATCH": "Update", "DELETE": "Delete"}[act.method]
                        aname = camel(a.resource) + verb
                    else:
                        aname = camel(a.resource) + pascal("-".join(segs) or act.method.lower())
                        if act.method == "GET":
                            aname += "Get" if not segs else ""
                    if aname in used_ids:  # same segments, different method/params: name the verb
                        verb = {"GET": "Get", "POST": "Add", "PUT": "Replace", "PATCH": "Update",
                                "DELETE": "Remove"}[act.method]
                        tail = "ById" if act.path.rstrip("/").endswith("}") and act.method == "GET" else ""
                        aname = camel(a.resource) + verb + pascal("-".join(segs)) + tail
                    req = None
                    req_name = ""
                    if act.body:
                        # named after the (unique) operation id: actions sharing a last path segment on one resource
                        # (e.g. /{id}/eod and /eod) must not overwrite each other's request schema
                        req_name = aname[0].upper() + aname[1:] + "Request"
                        bprops = dict((c.name, oa_type(c, enums)) for c in act.body)
                        breq = [c.name for c in act.body if c.nn]
                        sch = {"type": "object", "properties": bprops}
                        if breq:
                            sch["required"] = breq
                        for target in (doc, bundle):
                            prev = target["components"]["schemas"].get(req_name)
                            if prev is not None and prev != sch:
                                raise SpecError(f"[{d.id}] request schema {req_name} defined twice with different bodies")
                            target["components"]["schemas"][req_name] = sch
                        req = ref(req_name)
                    resp_tbl = act.response or t.name
                    is_list = resp_tbl.endswith("[]")
                    resp_tbl = resp_tbl.rstrip("[]")
                    content_type = "application/json"
                    if resp_tbl == "none":
                        resp = None
                    elif resp_tbl == "object":
                        resp = {"type": "object", "additionalProperties": True,
                                "description": "Computed result (see operation summary)"}
                    elif "/" in resp_tbl:
                        content_type = resp_tbl
                        textual = resp_tbl.startswith("text/") or resp_tbl.endswith(("json", "xml"))
                        # Binary payloads (PDF, octet-stream, images) generate Spring Resource, not String.
                        resp = {"type": "string"} if textual else {"type": "string", "format": "binary"}
                    elif is_list:
                        page_schema(doc, bundle, pascal(resp_tbl))
                        resp = ref(f"{pascal(resp_tbl)}Page")
                    else:
                        resp = ref(pascal(resp_tbl))
                    if resp_tbl != "none" and resp_tbl != "object" and "/" not in resp_tbl:
                        needed_tables.add(resp_tbl)
                    public = act.summary.startswith("[public]")
                    params = path_params(full)
                    for qc in act.query:
                        qs = oa_type(qc, enums)
                        qs.pop("description", None)
                        params.append({"name": qc.name, "in": "query", "required": qc.nn, "schema": qs})
                    if is_list:
                        params += [{"$ref": "#/components/parameters/Limit"},
                                   {"$ref": "#/components/parameters/Cursor"}]
                    write = act.method != "GET"
                    if write and not public:
                        params = params + [{"$ref": "#/components/parameters/IdempotencyKey"}]
                    add(act.method.lower(), full, aname, "action", act.summary.removeprefix("[public]").strip(),
                        request=req, response=resp, content_type=content_type, public=public,
                        op_key=f"{act.method} {act.path}",
                        status="200" if resp is not None else "204", params=params,
                        scope="write" if write else "read",
                        extra_errs=ERR(("404", "NotFound"), ("409", "Conflict"), ("422", "Unprocessable")) if write else ERR(("404", "NotFound")),
                        req_schema=req_name,
                        resp_schema="" if resp is None else pascal(resp_tbl) + ("[]" if is_list else ""))
        for tn in sorted(needed_tables):
            for k, v in table_schemas(tables_global[tn], enums).items():
                doc["components"]["schemas"][k] = v
                bundle["components"]["schemas"][k] = v
        prune_components(doc)
        p = OUT / "openapi" / f"{d.id}.openapi.yaml"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(dump_yaml(doc), encoding="utf-8", newline="\n")
        # one OpenAPI file per service: only that service's paths (input to Spring interface codegen)
        for s in d.services:
            sdoc = copy.deepcopy(doc)
            sdoc["info"]["title"] = f"Meridian Core — {s.name}"
            sdoc["paths"] = {pth: {m: o for m, o in ops.items() if o.get("x-service") == s.name}
                             for pth, ops in doc["paths"].items()}
            sdoc["paths"] = {pth: ops for pth, ops in sdoc["paths"].items() if ops}
            used_tags = {t for ops in sdoc["paths"].values() for o in ops.values() for t in o["tags"]}
            sdoc["tags"] = [t for t in sdoc["tags"] if t["name"] in used_tags]
            prune_components(sdoc)
            sp = OUT / "openapi" / "services" / f"{s.name}.openapi.yaml"
            sp.parent.mkdir(parents=True, exist_ok=True)
            sp.write_text(dump_yaml(sdoc), encoding="utf-8", newline="\n")
    prune_components(bundle)
    (OUT / "openapi" / "meridian.openapi.yaml").write_text(
        dump_yaml(bundle), encoding="utf-8", newline="\n")
    return endpoints


# ---------------------------------------------------------------------------
# AsyncAPI
# ---------------------------------------------------------------------------

def gen_asyncapi(domains, enums, tables_global) -> list:
    doc = {"asyncapi": "3.1.0",
           "info": {"title": "Meridian Core Events", "version": "1.0.0",
                    "description": "Domain events. CloudEvents 1.0 envelope, Kafka topic per domain, key = aggregate id."},
           "defaultContentType": "application/cloudevents+json",
           "servers": {"kafka": {"host": "kafka.meridian.internal:9093", "protocol": "kafka-secure"}},
           "channels": {}, "operations": {}, "components": {"messages": {}, "schemas": {}}}
    rows = []
    for d in domains:
        topic = f"meridian.{d.id}.v1"
        ch = {"address": topic, "description": f"{d.title} events", "messages": {}}
        for s in d.services:
            for e in s.events:
                mname = pascal(e.name.replace(".", "_"))
                sname = pascal(e.table)
                if sname not in doc["components"]["schemas"]:
                    doc["components"]["schemas"][sname] = table_schemas(tables_global[e.table], enums)[sname]
                doc["components"]["messages"][mname] = {
                    "name": e.name, "title": e.description, "summary": e.description,
                    "headers": {"type": "object", "properties": {
                        "ce_type": {"type": "string", "const": e.name}, "ce_source": {"type": "string"},
                        "ce_id": {"type": "string"}, "ce_time": {"type": "string", "format": "date-time"},
                        "ce_tenantid": {"type": "string", "format": "uuid"}}},
                    "payload": {"$ref": f"#/components/schemas/{sname}"}}
                ch["messages"][mname] = {"$ref": f"#/components/messages/{mname}"}
                rows.append((e.name, topic, d.id, s.name, e.table, e.description))
        if ch["messages"]:
            cid = camel(d.id)
            doc["channels"][cid] = ch
            doc["operations"][f"publish{pascal(d.id)}"] = {
                "action": "send", "channel": {"$ref": f"#/channels/{cid}"}}
    p = OUT / "asyncapi" / "meridian.asyncapi.yaml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(dump_yaml(doc), encoding="utf-8", newline="\n")
    return rows


# ---------------------------------------------------------------------------
# Data dictionary
# ---------------------------------------------------------------------------

def gen_dictionary(domains, api_by_table):
    L = ["# Meridian Core — Data Dictionary", "",
         "Generated from `spec/`. Column → API field mapping: API fields use the column name; "
         "`—` means the column is not exposed.", ""]
    for d in domains:
        L += [f"## {d.title} (`{d.id}`)", "", d.description or "", ""]
        for s in d.services:
            L += [f"### Service `{s.name}` — database `{s.database}`", "", s.description, ""]
            for t in s.tables:
                apis = ", ".join(sorted(api_by_table.get(t.name, []))) or "internal only"
                L += [f"#### `{t.name}`" + (" (append-only)" if t.immutable else ""), "",
                      t.description, "", f"API: {apis}" + (f" · Requirements: {', '.join(t.fr)}" if t.fr else ""), "",
                      "| Column | Type | Null | Key / ref | API field | Description |",
                      "| --- | --- | --- | --- | --- | --- |"]
                for c in t.columns:
                    key = "PK" if c.pk else (f"FK → {c.fk}" if c.fk else (f"ref → {c.ref}" if c.ref else ("UQ" if c.uq else "")))
                    typ = pg_type(c) + (f" ({c.arg})" if c.type == "enum" else "")
                    flags = (" PII" if c.pii else "") + (" secret" if c.secret else "")
                    L.append(f"| {c.name} | {typ} | {'no' if c.nn else 'yes'} | {key} | "
                             f"{c.name if c.in_api else '—'}{' (read-only)' if c.in_api and not c.writable else ''} | "
                             f"{c.description}{flags} |")
                L.append("")
    p = OUT / "docs" / "data-dictionary.md"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("\n".join(L), encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

SERVICE_PORT_BASE = 9101


def gen_manifest(domains):
    """generated/services.json: one entry per service, consumed by tools/scaffold.py and ops tooling.
    Ports are stable: services keep the port recorded in the previous manifest; new ones get the next free."""
    import json
    mp = OUT / "services.json"
    old = {}
    if mp.exists():
        old = {x["name"]: x["port"] for x in json.loads(mp.read_text(encoding="utf-8"))}
    taken = set(old.values())
    nxt = SERVICE_PORT_BASE
    out = []
    for d in domains:
        for s in d.services:
            port = old.get(s.name)
            if port is None:
                while nxt in taken:
                    nxt += 1
                port = nxt
                taken.add(port)
            short = s.name[:-len("-service")] if s.name.endswith("-service") else s.name
            out.append({
                "name": s.name, "domain": d.id, "domain_title": d.title, "phase": d.phase,
                "database": s.database, "schema": s.database[:-3] if s.database.endswith("_db") else s.database,
                "package": "io.meridian." + re.sub(r"[^a-z0-9]", "", short.replace("-", "")),
                "port": port, "description": " ".join(s.description.split()),
                "tags": sorted({" ".join(w.capitalize() for w in a.resource.split("-")) for a in s.apis}),
                "tables": len(s.tables), "events": [e.name for e in s.events],
            })
            if s.scope or d.scope:
                out[-1]["scope"] = s.scope or d.scope
    mp.write_text(json.dumps(out, indent=2), encoding="utf-8", newline="\n")


def gen_routes(endpoints):
    """generated/routes.json: every operation's method + path template -> owning service and port (edge gateway)."""
    import json
    ports = {x["name"]: x["port"] for x in json.loads((OUT / "services.json").read_text(encoding="utf-8"))}
    routes = sorted({(e.method, e.path, e.service, e.public) for e in endpoints}, key=lambda r: (r[1], r[0]))
    (OUT / "routes.json").write_text(json.dumps(
        [{"method": m, "path": pth, "service": svc, "port": ports[svc], "public": pub}
         for m, pth, svc, pub in routes], indent=1),
        encoding="utf-8", newline="\n")


def main():
    domains = load_domains()
    if "--domain" in sys.argv:
        focus = sys.argv[sys.argv.index("--domain") + 1]
        validate(domains, focus=focus, strict=False)
        d = next(x for x in domains if x.id == focus)
        print(f"domain {focus} OK: services={len(d.services)} "
              f"tables={sum(len(s.tables) for s in d.services)} "
              f"columns={sum(len(t.columns) for s in d.services for t in s.tables)} "
              f"apis={sum(len(s.apis) for s in d.services)} events={sum(len(s.events) for s in d.services)}")
        return
    enums, tables_global = validate(domains, strict="--lenient" not in sys.argv)
    if "--check" in sys.argv:
        print("spec OK")
        return
    n_tables = gen_ddl(domains, enums)
    endpoints = gen_openapi(domains, enums, tables_global)
    events = gen_asyncapi(domains, enums, tables_global)
    api_by_table = defaultdict(set)
    for d in domains:
        for s in d.services:
            for a in s.apis:
                api_by_table[a.table].add(a.path)
    gen_dictionary(domains, api_by_table)
    gen_manifest(domains)
    gen_routes(endpoints)
    apps = load_yaml(_quote_descriptions((SPEC / "apps.yaml").read_text(encoding="utf-8"))) \
        if (SPEC / "apps.yaml").exists() else {}
    op_ids = {e.op_id for e in endpoints}
    missing = [f"{a['id']} / {sc['name']}: {op}" for a in (apps or {}).get("apps", [])
               for sc in a.get("screens", []) for op in sc.get("apis", []) if op not in op_ids]
    if missing:
        msg = "Screens reference unknown API operations:\n  - " + "\n  - ".join(missing)
        if "--lenient" in sys.argv:
            print("warning:", msg)
        else:
            raise SpecError(msg)
    plan = load_yaml((SPEC / "plan.yaml").read_text(encoding="utf-8")) if (SPEC / "plan.yaml").exists() else {}
    sys.path.insert(0, str(Path(__file__).parent))
    import tracker
    stats = tracker.build(ROOT / "tracker" / "Meridian_Tracker.xlsx", domains, enums, endpoints, events,
                          apps or {}, plan or {}, api_by_table)
    n_cols = sum(len(t.columns) for t in tables_global.values())
    print(f"domains={len(domains)} services={sum(len(d.services) for d in domains)} "
          f"databases={len({s.database for d in domains for s in d.services})} tables={n_tables} "
          f"columns={n_cols} endpoints={len(endpoints)} events={len(events)} {stats}")


if __name__ == "__main__":
    try:
        main()
    except SpecError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
