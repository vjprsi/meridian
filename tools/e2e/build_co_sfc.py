#!/usr/bin/env python3
"""Builds regulatory-packs/co-sfc.yaml (CO_SFC) from ke-cbk.yaml: the prudential / conduct / e-money reports rebased
to COP and the SFC, plus Colombian financial-crime and tax returns (UIAF RTE cash report with the monthly
multiple-transactions rule, UIAF ROS + 'ausencia de ROS', weekly GMF return). Run from the packs directory."""
import io, re

src = io.open("ke-cbk.yaml", encoding="utf-8").read()
lines = src.split("\n")

def block(start_marker, end_marker):
    a = src.index(start_marker)
    b = src.index(end_marker, a)
    return a, b

HEADER = '''# Meridian regulatory pack CO_SFC: Colombia (Superintendencia Financiera de Colombia prudential and conduct returns,
# UIAF financial-crime reports, DIAN GMF return).
#
# ILLUSTRATIVE TEMPLATES. Cell codes, row/column layouts, thresholds and deadlines below are realistic samples in the
# shape of Colombian returns (SFC formats on the CUIF chart, UIAF SIREL reports under the SARLAFT, the DIAN weekly
# GMF declaration). They are NOT copies of SFC, UIAF or DIAN forms and are not legal advice: validate every report
# against the current Circular Basica Juridica / Contable y Financiera, the UIAF technical annexes (formats, thresholds,
# SIREL deadlines) and the DIAN calendar - and map the bank's own CUIF chart - before using it for a filing.
#
# UIAF cash report (RTE): individual cash transactions at or above COP 10,000,000 and, per customer, cash
# transactions in the month adding up to COP 50,000,000 or more ("transacciones multiples"); an empty month is
# reported as "ausencia". ROS (reporte de operaciones sospechosas) are filed case by case from case-management-service;
# the monthly return counts them and flags the "ausencia de ROS". No narratives here (reserva / tipping-off).
#
# Structure (see docs/SERVICE_IMPLEMENTATION_GUIDE.md "Regulatory packs"):
#   calendar             business-day convention and the Colombian festivos (Ley Emiliani; verify yearly)
#   gl_mapping_tables    GL account code ranges -> report lines; a tenant overrides them in config-service namespace
#                        regulatory_reporting, key gl_mapping.<table> (map the bank's CUIF chart there)
#   source_templates     data sources reused by reports
#   reports              one report definition + version each
#   sample_data          sample source rows; each report's sample.expect lists the cells / findings it must produce
'''

FESTIVOS = ('["2026-01-01", "2026-01-12", "2026-03-23", "2026-04-02", "2026-04-03", "2026-05-01", "2026-05-18", "2026-06-08",\n'
            '             "2026-06-15", "2026-06-29", "2026-07-20", "2026-08-07", "2026-08-17", "2026-10-12", "2026-11-02", "2026-11-16",\n'
            '             "2026-12-08", "2026-12-25",\n'
            '             "2027-01-01", "2027-01-11", "2027-03-22", "2027-03-25", "2027-03-26", "2027-05-01", "2027-05-10", "2027-05-31",\n'
            '             "2027-06-07", "2027-07-05", "2027-07-20", "2027-08-07", "2027-08-16", "2027-10-18", "2027-11-01", "2027-11-15",\n'
            '             "2027-12-08", "2027-12-25"]')

body = src[src.index("pack_code: KE_CBK"):]
# --- header fields
body = body.replace("pack_code: KE_CBK", "pack_code: CO_SFC", 1)
body = body.replace("country: KE", "country: CO", 1)
body = body.replace("regulator: Central Bank of Kenya", "regulator: Superintendencia Financiera de Colombia", 1)
body = re.sub(r"^title: .*$", "title: Colombia prudential, conduct, electronic-deposit, UIAF financial-crime and GMF returns (illustrative)",
              body, count=1, flags=re.M)
# notice (folded block) -> replace up to the calendar
a = body.index("notice: >"); b = body.index("calendar:")
body = body[:a] + '''notice: >
  ILLUSTRATIVE SFC / UIAF / DIAN-style layouts in the shape of Colombian returns, not the official formats. Validate
  against the current SFC formats (CUIF), the UIAF SIREL technical annexes and thresholds, and the DIAN calendar before
  use; map the bank's CUIF chart through the GL mapping override.

''' + body[b:]
# calendar holidays
a = body.index("  # Kenyan public holidays"); b = body.index("\n\n", a)
body = body[:a] + "  # Colombian festivos 2026-2027 (Ley 51 de 1983 'Emiliani' + Easter feasts; verify every year)\n  holidays: " + FESTIVOS + body[b:]
# currency everywhere (filters and sample rows move together, so the sample expectations stay valid)
body = body.replace("currency: KES", "currency: COP").replace("'KES'", "'COP'").replace("(KES)", "(COP)").replace("KES ", "COP ")
body = body.replace("KE_CBK_GL", "CO_SFC_GL")
# report codes and regulator names
body = body.replace("KE-CBK-", "CO-SFC-").replace("(illustrative CBK-style)", "(illustrative SFC-style)")
body = body.replace("regulator: Central Bank of Kenya", "regulator: Superintendencia Financiera de Colombia")
body = body.replace("CBK", "SFC")

# --- payments source template (GMF return)
body = body.replace("  wallet_summary:", '''  settled_payments: {service: payment-service, path: "/v1/payments?status=SETTLED&created_after={period_start_ts}&created_before={period_end_ts}&limit=200"}
  wallet_summary:''', 1)

# --- replace the KE FRC AML report with the UIAF reports + GMF return
a = body.index("  # ------------------------------------------------------------------------------------------ AML: CTR and STR")
b = body.index("  # ------------------------------------------------------------------------------------------ complaints")
UIAF = '''  # ------------------------------------------------------------------------------------------ UIAF: cash (RTE)
  - code: CO-UIAF-RTE1
    name: Reporte de transacciones en efectivo - individual and multiple cash transactions (illustrative UIAF-style)
    regulator: Unidad de Informacion y Analisis Financiero
    report_family: AML_CTR
    frequency: MONTHLY
    format: CSV
    deadline_rule: PERIOD_END+10D
    owner: COMPLIANCE
    description: >
      Cash transactions of the month from transaction-monitoring-service: individual transactions at or above the
      individual threshold (run parameter rte_individual_threshold, illustrative default COP 10,000,000) and the
      customers whose cash transactions add up to the multiple threshold or more in the month (rte_multiple_threshold,
      illustrative default COP 50,000,000). A month with neither is reported as 'ausencia'. ILLUSTRATIVE layout.
    version:
      effective_from: "2026-01-01"
      change_note: Initial illustrative version
      data_sources:
        - {name: txns, use: monitored_txns, id_field: transaction_id}
        - name: cash_by_customer
          type: COMBINE
          key_field: customer
          from:
            - {source: txns, key: customer_id, filter: "is_cash = TRUE AND currency = 'COP'", sum: {cash_total: amount}}
      field_mappings:
        - {cell_ref: RTE1_R010, label: "Individual threshold applied (COP)", expression: "COALESCE(param_rte_individual_threshold, 10000000)"}
        - {cell_ref: RTE1_R015, label: "Multiple-transactions threshold applied (COP, per customer and month)", expression: "COALESCE(param_rte_multiple_threshold, 50000000)"}
        - {cell_ref: RTE1_R020, label: "Individual cash transactions at or above the threshold", source: txns, aggregate: COUNT, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000)"}
        - {cell_ref: RTE1_R030, label: "Value of those transactions", source: txns, aggregate: SUM, value: amount, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000)"}
        - {cell_ref: RTE1_R040, label: "Customers involved", source: txns, aggregate: COUNT_DISTINCT, value: customer_id, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000)"}
        - {cell_ref: RTE1_R050, label: "of which cash deposits (consignaciones)", source: txns, aggregate: COUNT, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000) AND direction = 'CREDIT'"}
        - {cell_ref: RTE1_R060, label: "of which cash withdrawals (retiros)", source: txns, aggregate: COUNT, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000) AND direction = 'DEBIT'"}
        - {cell_ref: RTE1_R070, label: "at corresponsales bancarios (agents)", source: txns, aggregate: COUNT, filter: "is_cash = TRUE AND currency = 'COP' AND amount >= COALESCE(param_rte_individual_threshold, 10000000) AND channel = 'AGENT'"}
        - {cell_ref: RTE1_R100, label: "Customers with multiple cash transactions at or above the monthly threshold", source: cash_by_customer, aggregate: COUNT, filter: "cash_total >= COALESCE(param_rte_multiple_threshold, 50000000)"}
        - {cell_ref: RTE1_R110, label: "Value of their cash transactions in the month", source: cash_by_customer, aggregate: SUM, value: cash_total, filter: "cash_total >= COALESCE(param_rte_multiple_threshold, 50000000)"}
        - {cell_ref: RTE1_R200, label: "Ausencia de transacciones a reportar (1 = nothing to report)", expression: "IF(RTE1_R020 + RTE1_R100 = 0, 1, 0)"}
      validation_rules:
        - {code: RTE1_DIRECTION_SPLIT, type: EQUALS, left: "RTE1_R020", right: "RTE1_R050 + RTE1_R060", message: "Deposits plus withdrawals must equal the qualifying cash transactions"}
        - {code: RTE1_CUSTOMERS, type: EXPRESSION, expression: "RTE1_R040 <= RTE1_R020", message: "More customers than transactions"}
        - {code: RTE1_NON_NEGATIVE, type: RANGE, cell: RTE1_R030, min: 0, message: "Transaction value cannot be negative"}
        - {code: RTE1_MULTIPLE_PRESENT, type: RANGE, cell: RTE1_R100, max: 0, severity: INFO, message: "Customers reached the multiple cash transactions threshold: report each one's transactions in the RTE detail"}
    sample:
      expect:
        cells: {RTE1_R010: "10000000.00", RTE1_R015: "50000000.00", RTE1_R020: "2", RTE1_R030: "27000000.00", RTE1_R040: "2",
                RTE1_R050: "1", RTE1_R060: "1", RTE1_R070: "1", RTE1_R100: "1", RTE1_R110: "57899999.00", RTE1_R200: "0.00"}
        findings: [RTE1_MULTIPLE_PRESENT]

  # ------------------------------------------------------------------------------------------ UIAF: ROS
  - code: CO-UIAF-ROS1
    name: Reportes de operaciones sospechosas and ausencia de ROS - monthly statistics (illustrative UIAF-style)
    regulator: Unidad de Informacion y Analisis Financiero
    report_family: AML_SAR
    frequency: MONTHLY
    format: CSV
    deadline_rule: PERIOD_END+10D
    owner: COMPLIANCE
    description: >
      Suspicious operation reports (ROS) filed through case-management-service in the month, those still pending,
      and the 'ausencia de ROS' flag when none was filed. Each ROS itself is filed immediately ('de forma inmediata')
      from the case; this return only counts them. Counts and amounts only (reserva legal). ILLUSTRATIVE layout.
    version:
      effective_from: "2026-01-01"
      change_note: Initial illustrative version
      data_sources:
        - {name: filings, use: regulatory_reports, id_field: id}
      field_mappings:
        - {cell_ref: ROS1_R010, label: "ROS filed in the period", source: filings, aggregate: COUNT, filter: "report_type IN ('STR', 'SAR') AND status IN ('SUBMITTED', 'ACKNOWLEDGED')"}
        - {cell_ref: ROS1_R020, label: "ROS pending approval or filing", source: filings, aggregate: COUNT, filter: "report_type IN ('STR', 'SAR') AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')"}
        - {cell_ref: ROS1_R030, label: "Amount reported in filed ROS (COP)", source: filings, aggregate: SUM, value: "COALESCE(suspicious_amount, 0)", filter: "report_type IN ('STR', 'SAR') AND status IN ('SUBMITTED', 'ACKNOWLEDGED')"}
        - {cell_ref: ROS1_R040, label: "ROS returned by the UIAF", source: filings, aggregate: COUNT, filter: "report_type IN ('STR', 'SAR') AND status = 'REJECTED'"}
        - {cell_ref: ROS1_R050, label: "Ausencia de ROS (1 = no ROS filed in the period)", expression: "IF(ROS1_R010 = 0, 1, 0)"}
      validation_rules:
        - {code: ROS1_NON_NEGATIVE, type: RANGE, cell: ROS1_R030, min: 0, message: "Reported amount cannot be negative"}
        - {code: ROS1_BACKLOG, type: RANGE, cell: ROS1_R020, max: 0, severity: INFO, message: "ROS still pending at period end: they must be filed immediately"}
        - {code: ROS1_RETURNED, type: RANGE, cell: ROS1_R040, max: 0, severity: WARNING, message: "ROS returned by the UIAF: correct and refile"}
    sample:
      expect:
        cells: {ROS1_R010: "2", ROS1_R020: "1", ROS1_R030: "7500000.00", ROS1_R040: "1", ROS1_R050: "0.00"}
        findings: [ROS1_BACKLOG, ROS1_RETURNED]

  # ------------------------------------------------------------------------------------------ DIAN: GMF
  - code: CO-DIAN-GMF1
    name: Gravamen a los movimientos financieros - weekly withholding return (illustrative DIAN-style)
    regulator: Direccion de Impuestos y Aduanas Nacionales
    report_family: TAX_GMF
    frequency: WEEKLY
    format: CSV
    deadline_rule: PERIOD_END+2BD
    owner: FINANCE
    description: >
      GMF (4 x 1000) withheld on the week's settled debits by payment-service (payment tax_amount; same-holder transfers
      and the exempt account's monthly allowance show as untaxed debits). Supports the weekly declaration; the official
      form, calendar and payment come from the DIAN. ILLUSTRATIVE layout.
    version:
      effective_from: "2026-01-01"
      change_note: Initial illustrative version
      data_sources:
        - {name: payments, use: settled_payments, id_field: id}
      field_mappings:
        - {cell_ref: GMF1_R010, label: "Taxed debits (number)", source: payments, aggregate: COUNT, filter: "currency = 'COP' AND COALESCE(tax_amount, 0) > 0"}
        - {cell_ref: GMF1_R020, label: "Taxed debits (value, COP)", source: payments, aggregate: SUM, value: amount, filter: "currency = 'COP' AND COALESCE(tax_amount, 0) > 0"}
        - {cell_ref: GMF1_R030, label: "Transaction taxes withheld (COP)", source: payments, aggregate: SUM, value: "COALESCE(tax_amount, 0)", filter: "currency = 'COP'"}
        - {cell_ref: GMF1_R040, label: "Exempt or untaxed debits (number)", source: payments, aggregate: COUNT, filter: "currency = 'COP' AND direction IN ('OUTBOUND', 'INTERNAL') AND COALESCE(tax_amount, 0) = 0"}
      validation_rules:
        - {code: GMF1_NON_NEGATIVE, type: RANGE, cell: GMF1_R030, min: 0, message: "Taxes withheld cannot be negative"}
        - {code: GMF1_RATE_BOUND, type: EXPRESSION, expression: "GMF1_R030 <= GMF1_R020 * 0.05", severity: WARNING, message: "Taxes above 5% of the taxed debits: check fee IVA or the tax settings"}
    sample:
      expect:
        cells: {GMF1_R010: "2", GMF1_R020: "400000.00", GMF1_R030: "1600.00", GMF1_R040: "1"}
        findings: []

'''
body = body[:a] + UIAF + body[b:]

# --- sample data: replace txns + filings, add payments
a = body.index("  txns:\n"); b = body.index("  complaints:\n", a)
SAMPLE = '''  txns:
    - {id: m1, transaction_id: t1, customer_id: c1, is_cash: true, direction: CREDIT, channel: BRANCH, currency: COP, amount: "15000000.00"}
    - {id: m2, transaction_id: t2, customer_id: c2, is_cash: true, direction: DEBIT, channel: AGENT, currency: COP, amount: "12000000.00"}
    - {id: m3, transaction_id: t3, customer_id: c3, is_cash: true, direction: CREDIT, channel: BRANCH, currency: COP, amount: "9999999.00"}
    - {id: m4, transaction_id: t4, customer_id: c3, is_cash: false, direction: CREDIT, channel: WEB, currency: COP, amount: "60000000.00"}
    - {id: m5, transaction_id: t5, customer_id: c3, is_cash: true, direction: CREDIT, channel: AGENT, currency: COP, amount: "9000000.00"}
    - {id: m6, transaction_id: t6, customer_id: c3, is_cash: true, direction: CREDIT, channel: AGENT, currency: COP, amount: "9500000.00"}
    - {id: m7, transaction_id: t7, customer_id: c3, is_cash: true, direction: CREDIT, channel: BRANCH, currency: COP, amount: "9900000.00"}
    - {id: m8, transaction_id: t8, customer_id: c3, is_cash: true, direction: DEBIT, channel: BRANCH, currency: COP, amount: "9800000.00"}
    - {id: m9, transaction_id: t9, customer_id: c3, is_cash: true, direction: DEBIT, channel: AGENT, currency: COP, amount: "9700000.00"}
    - {id: m10, transaction_id: t10, customer_id: c1, is_cash: true, direction: DEBIT, channel: BRANCH, currency: COP, amount: "1000000.00"}
  filings:
    - {id: f1, report_type: CTR, status: SUBMITTED}
    - {id: f3, report_type: STR, status: SUBMITTED, suspicious_amount: "5000000.00"}
    - {id: f4, report_type: STR, status: ACKNOWLEDGED, suspicious_amount: "2500000.00"}
    - {id: f5, report_type: STR, status: PENDING_APPROVAL, suspicious_amount: "900000.00"}
    - {id: f6, report_type: STR, status: REJECTED, suspicious_amount: "300000.00"}
  payments:
    - {id: p1, direction: OUTBOUND, currency: COP, amount: "150000.00", tax_amount: "600.00"}
    - {id: p2, direction: INTERNAL, currency: COP, amount: "250000.00", tax_amount: "1000.00"}
    - {id: p3, direction: INTERNAL, currency: COP, amount: "500000.00", tax_amount: "0.00"}
    - {id: p4, direction: INBOUND, currency: COP, amount: "900000.00"}
'''
body = body[:a] + SAMPLE + body[b:]
io.open("co-sfc.yaml", "w", encoding="utf-8").write(HEADER + body)
print("co-sfc.yaml written,", len(HEADER + body), "bytes")
