package io.meridian.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * RECON-REAL-REFS end to end: a week of nostro activity posted the way payment-service and card-clearing post it
 * (outbound settlements captured from holds, inbound credits, batch-booked bulk payments, returns, scheme net
 * settlements, a cancelled credit reversed in the ledger, payments still in flight), each posting carrying
 * {@code reference} + {@code external_refs}, stored by a stateful ledger stand-in exactly as ledger-service stores them
 * (refs copied to every line, counterparty account pseudonymised). The correspondent bank's camt.053 for the week is
 * built from the same payments the way banks report them: some lines with every reference, some with only the UETR,
 * only the clearing reference, only the end-to-end id, only remittance text, or (NOTPROVIDED) only the payer's name and
 * account; batch bookings as one line; bank charges with no internal counterpart.
 *
 * <p>The standard rule set must auto-match at least 98% of the matchable statement lines, with no false match, and use
 * the strong rules (UETR, end-to-end id, bank reference, batch) the ledger's references now feed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
class ReconRealRefsIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e7";
    static final String MAKER = "recon-analyst";
    static final String CHECKER = "recon-head";
    static final LocalDate MON = LocalDate.of(2026, 3, 2);
    static final LocalDate FRI = MON.plusDays(4);
    static final String CCY = "EUR";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient services;

    final HttpClient http = HttpClient.newHttpClient();
    final Random rnd = new Random(20260302L);
    final UUID nostro = UUID.randomUUID();
    final UUID fees = UUID.randomUUID();
    final List<UUID> customers = new ArrayList<>();
    PaymentLedgerFake ledger;

    /** One statement entry and the nostro ledger lines it must be matched with (empty = bank-only line). */
    record Line(LocalDate booked, LocalDate value, String dir, BigDecimal amount, String xml, Set<UUID> expected,
                String kind) {}

    final List<Line> statement = new ArrayList<>();
    final Set<UUID> inFlight = new HashSet<>();
    final Set<UUID> reversedPair = new HashSet<>();

    @Test
    void paymentPostingsWithReferencesReconcileAgainstARealisticCamt053() throws Exception {
        ledger = new PaymentLedgerFake(json, UUID.fromString(TENANT));
        when(services.get(eq("ledger-service"), anyString())).thenAnswer(inv -> ledger.get(inv.getArgument(1)));
        for (int i = 0; i < 40; i++) {
            customers.add(UUID.randomUUID());
        }
        buildWeek();

        String source = post(MAKER, "/v1/reconciliation/sources", """
                {"name":"Nostro EUR at correspondent","source_type":"BANK_STATEMENT","file_format":"CAMT_053",
                 "currency":"EUR","ledger_account_id":"%s","parser_config":{"invert_direction":true}}""".formatted(nostro),
                201).get("id").asString();
        String ruleSet = post(MAKER, "/v1/reconciliation/rule-sets",
                "{\"recon_source_id\":\"" + source + "\",\"name\":\"Nostro standard\"}", 201).get("id").asString();
        post(MAKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/submit", null, 200);
        post(CHECKER, "/v1/reconciliation/rule-sets/" + ruleSet + "/approve", null, 200);

        JsonNode file = post(MAKER, "/v1/reconciliation/files/import", """
                {"recon_source_id":"%s","file_name":"camt053-nostro-eur-w10.xml","content":%s}"""
                .formatted(source, json.writeValueAsString(camt053())), 200);
        assertThat(file.get("status").asString()).as(file.toString()).isEqualTo("PARSED");
        assertThat(file.get("record_count").asInt()).isEqualTo(statement.size());
        Map<UUID, Integer> lineOfRecord = new HashMap<>();
        for (JsonNode rec : all("/v1/reconciliation/files/" + file.get("id").asString() + "/records")) {
            lineOfRecord.put(UUID.fromString(rec.get("id").asString()), rec.get("line_no").asInt());
        }

        JsonNode run = post(MAKER, "/v1/reconciliation/runs", """
                {"recon_source_id":"%s","from_date":"%s","to_date":"%s"}""".formatted(source, MON, FRI), 201);
        assertThat(run.get("status").asString()).as(run.toString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("external_count").asInt()).isEqualTo(statement.size());

        // every auto-match must link the statement line with exactly the ledger lines it came from
        int correct = 0;
        List<String> wrong = new ArrayList<>();
        Map<String, Integer> byRule = new TreeMap<>();
        Set<Integer> matchedLines = new HashSet<>();
        for (JsonNode m : all("/v1/reconciliation/matches?recon_run_id=" + run.get("id").asString())) {
            byRule.merge(m.get("rule_code").asString(), 1, Integer::sum);
            Set<UUID> internal = new HashSet<>();
            m.get("internal_entry_ids").forEach(x -> internal.add(UUID.fromString(x.asString())));
            for (JsonNode x : m.get("external_record_ids")) {
                int lineNo = lineOfRecord.get(UUID.fromString(x.asString()));
                Line expected = statement.get(lineNo - 1);
                matchedLines.add(lineNo);
                if (m.get("external_record_ids").size() == 1 && expected.expected().equals(internal)) {
                    correct++;
                } else {
                    wrong.add(expected.kind() + " line " + lineNo + " via " + m.get("rule_code").asString());
                }
            }
        }
        long matchable = statement.stream().filter(l -> !l.expected().isEmpty()).count();
        double rate = (double) correct / matchable;
        List<String> missed = new ArrayList<>();
        for (int n = 1; n <= statement.size(); n++) {
            if (!statement.get(n - 1).expected().isEmpty() && !matchedLines.contains(n)) {
                missed.add(statement.get(n - 1).kind() + " line " + n);
            }
        }
        System.out.printf("RECON-REAL-REFS: %d statement lines, %d matchable, %d auto-matched correctly (%.2f%%), "
                        + "%d false; by rule %s; missed %s%n", statement.size(), matchable, correct, 100 * rate,
                wrong.size(), byRule, missed);
        assertThat(wrong).as("false auto-matches").isEmpty();
        assertThat(rate).as("auto-match rate over matchable lines; missed " + missed).isGreaterThanOrEqualTo(0.98);
        assertThat(byRule).containsKeys("exact_uetr", "exact_end_to_end", "exact_bank_ref", "batch_keyed");
        assertThat(byRule.keySet()).as("the payer's account identifies NOTPROVIDED credits")
                .anyMatch(r -> r.startsWith("fuzzy"));

        // breaks: the bank's own charges have no internal side; payments still in flight have no statement line;
        // the credit cancelled in the ledger (original + reversal) is paired, not raised
        Map<String, Integer> reasons = new HashMap<>();
        Set<UUID> missingExternal = new HashSet<>();
        for (JsonNode e : all("/v1/reconciliation/exceptions?recon_run_id=" + run.get("id").asString())) {
            reasons.merge(e.get("reason").asString(), 1, Integer::sum);
            if ("MISSING_EXTERNAL".equals(e.get("reason").asString())) {
                missingExternal.add(UUID.fromString(e.get("internal_entry_id").asString()));
            }
        }
        assertThat(reasons.get("MISSING_INTERNAL")).as(reasons.toString())
                .isGreaterThanOrEqualTo((int) (statement.size() - matchable));
        assertThat(missingExternal).containsAll(inFlight).doesNotContainAnyElementsOf(reversedPair);
    }

    // ------------------------------------------------------------------ the week

    private void buildWeek() {
        for (int day = 0; day < 5; day++) {
            LocalDate d = MON.plusDays(day);
            for (int i = 0; i < 48; i++) {
                outbound(d);
            }
            for (int i = 0; i < 36; i++) {
                inbound(d);
            }
            if (day < 3) {
                bulk(d, 12);
            }
            if (day == 1 || day == 3) {
                cardSettlement(d);
            }
            if (day == 2) {
                cancelledCredit(d);
            }
            bankCharge(d);
        }
        for (int i = 0; i < 8; i++) {
            returnOfOutbound();
        }
        for (int i = 0; i < 3; i++) {
            inFlight.add(PaymentLedgerFake.lineOn(capture(payment(FRI, true)), nostro));
        }
        statement.sort((a, b) -> a.booked().compareTo(b.booked()));
    }

    /** payment-service row fields used by LedgerClient.refs (outbound: creditor = counterparty). */
    private ObjectNode payment(LocalDate d, boolean outbound) {
        ObjectNode p = json.createObjectNode();
        p.put("id", UUID.randomUUID().toString());
        p.put("date", d.toString());
        p.put("amount", amount().toPlainString());
        p.put("end_to_end_id", "E2E" + alnum(12));
        p.put("uetr", UUID.randomUUID().toString());
        p.put("instruction_id", "INS" + alnum(10));
        p.put("clearing_system_ref", "SCT" + digits(12));
        p.put("transaction_id", "TX" + digits(14));
        String party = outbound ? "creditor" : "debtor";
        p.put(party + "_name", name());
        p.put(party + "_account_number", iban());
        p.put("customer_ledger_account", customers.get(rnd.nextInt(customers.size())).toString());
        return p;
    }

    /** LedgerClient.refs(payment, counterpartyIsCreditor). */
    private ObjectNode refs(ObjectNode p, boolean creditor) {
        ObjectNode r = json.createObjectNode();
        r.put("uetr", p.get("uetr").asString());
        r.put("end_to_end_id", p.get("end_to_end_id").asString());
        r.put("instruction_id", p.get("instruction_id").asString());
        r.put("bank_reference", p.get("clearing_system_ref").asString());
        r.put("scheme_reference", p.get("transaction_id").asString());
        if (p.hasNonNull("bulk_payment_id")) {
            r.put("batch_id", p.get("bulk_payment_id").asString());
        }
        String party = creditor ? "creditor" : "debtor";
        r.put("counterparty_name", p.get(party + "_name").asString());
        r.put("counterparty_account", p.get(party + "_account_number").asString());
        return r;
    }

    /** Settlement of an outbound payment: capture of its hold to the nostro (PaymentProcessor.captureFunds). */
    private ObjectNode capture(ObjectNode p) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", p.get("amount").asString());
        body.put("counter_account_id", nostro.toString());
        body.put("description", "Settlement " + p.get("end_to_end_id").asString() + " via SCT");
        body.put("release_remainder", true);
        body.put("reference", p.get("end_to_end_id").asString());
        body.set("external_refs", refs(p, true));
        return ledger.capture(UUID.fromString(p.get("customer_ledger_account").asString()), CCY, body,
                LocalDate.parse(p.get("date").asString()));
    }

    private final List<ObjectNode> settledOutbound = new ArrayList<>();

    private void outbound(LocalDate d) {
        ObjectNode p = payment(d, true);
        UUID line = PaymentLedgerFake.lineOn(capture(p), nostro);
        settledOutbound.add(p);
        double v = rnd.nextDouble();
        String e2e = p.get("end_to_end_id").asString();
        String kind;
        Tx tx = new Tx();
        tx.party = p.get("creditor_name").asString();
        tx.account = p.get("creditor_account_number").asString();
        if (v < 0.45) {
            kind = "outbound/all-refs";
            tx.e2e = e2e;
            tx.instr = p.get("instruction_id").asString();
            tx.uetr = p.get("uetr").asString();
            tx.bankRef = p.get("clearing_system_ref").asString();
        } else if (v < 0.65) {
            kind = "outbound/uetr-only";
            tx.uetr = p.get("uetr").asString();
            tx.bankRef = "BK" + digits(14);
        } else if (v < 0.80) {
            kind = "outbound/clearing-ref-only";
            tx.bankRef = p.get("clearing_system_ref").asString();
        } else if (v < 0.95) {
            kind = "outbound/e2e-only";
            tx.e2e = e2e;
            tx.bankRef = "BK" + digits(14);
        } else {
            kind = "outbound/remittance-only";
            tx.bankRef = "BK" + digits(14);
            tx.remittance = "SEPA CT " + e2e + " " + tx.party;
        }
        LocalDate value = d.equals(FRI) ? d : d.plusDays(rnd.nextInt(2));
        add(d, value, "DBIT", new BigDecimal(p.get("amount").asString()), tx, Set.of(line), kind);
    }

    private void inbound(LocalDate d) {
        ObjectNode p = payment(d, false);
        ObjectNode body = json.createObjectNode(); // PaymentProcessor.creditInbound
        body.put("ledger_id", UUID.randomUUID().toString());
        body.put("entry_type", "TRANSFER");
        body.put("description", "Incoming payment " + p.get("end_to_end_id").asString());
        body.put("source_type", "PAYMENT");
        body.put("source_id", p.get("id").asString());
        body.put("reference", p.get("end_to_end_id").asString());
        body.set("external_refs", refs(p, false));
        body.putArray("entries")
                .add(leg(nostro, "DEBIT", p.get("amount").asString()))
                .add(leg(UUID.fromString(p.get("customer_ledger_account").asString()), "CREDIT", p.get("amount").asString()));
        UUID line = PaymentLedgerFake.lineOn(ledger.post(body, d), nostro);
        Tx tx = new Tx();
        tx.party = p.get("debtor_name").asString();
        tx.account = p.get("debtor_account_number").asString();
        tx.bankRef = "BK" + digits(14);
        double v = rnd.nextDouble();
        String kind;
        if (v < 0.5) {
            kind = "inbound/e2e+uetr";
            tx.e2e = p.get("end_to_end_id").asString();
            tx.uetr = p.get("uetr").asString();
        } else if (v < 0.85) {
            kind = "inbound/e2e";
            tx.e2e = p.get("end_to_end_id").asString();
        } else {
            kind = "inbound/notprovided";   // only the payer's (abbreviated) name and account identify it
            tx.e2e = "NOTPROVIDED";
            tx.party = tx.party.toUpperCase();
            tx.remittance = "INVOICE PAYMENT";
        }
        add(d, d, "CRDT", new BigDecimal(p.get("amount").asString()), tx, Set.of(line), kind);
    }

    /** A batch-booked bulk payment: one capture per item with the bulk id, one debit line on the statement. */
    private void bulk(LocalDate d, int items) {
        String bulkId = UUID.randomUUID().toString();
        Set<UUID> lines = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < items; i++) {
            ObjectNode p = payment(d, true);
            p.put("bulk_payment_id", bulkId);
            lines.add(PaymentLedgerFake.lineOn(capture(p), nostro));
            total = total.add(new BigDecimal(p.get("amount").asString()));
        }
        Tx tx = new Tx();
        tx.bankRef = "BK" + digits(14);
        tx.batchRef = bulkId;
        tx.batchCount = items;
        tx.info = "SEPA BULK DEBIT";
        add(d, d, "DBIT", total, tx, lines, "bulk/batch-booking");
    }

    /** card-clearing SettlementService.confirm: the scheme's net settlement against the nostro. */
    private void cardSettlement(LocalDate d) {
        String stmtRef = "STMT-" + d + "-" + digits(3);
        String amount = amount().multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        ObjectNode body = json.createObjectNode();
        body.put("entry_type", "CARD_SETTLEMENT");
        body.put("description", "VISA settlement " + d);
        body.put("reference", stmtRef);
        ObjectNode refs = body.putObject("external_refs");
        refs.put("scheme_reference", "VISA " + d);
        refs.put("batch_id", UUID.randomUUID().toString());
        refs.put("counterparty_name", "VISA");
        refs.put("bank_reference", stmtRef);
        body.putArray("entries").add(leg(UUID.randomUUID(), "DEBIT", amount)).add(leg(nostro, "CREDIT", amount));
        UUID line = PaymentLedgerFake.lineOn(ledger.post(body, d), nostro);
        Tx tx = new Tx();
        tx.bankRef = stmtRef;
        tx.info = "VISA NET SETTLEMENT";
        add(d, d, "DBIT", new BigDecimal(amount), tx, Set.of(line), "card/net-settlement");
    }

    /** pacs.004 for one of our outbound payments: ReturnService.creditBack (original references, return id). */
    private void returnOfOutbound() {
        ObjectNode p = settledOutbound.remove(rnd.nextInt(settledOutbound.size() / 2)); // each returned once
        LocalDate d = LocalDate.parse(p.get("date").asString()).plusDays(2);
        ObjectNode body = json.createObjectNode();
        body.put("entry_type", "TRANSFER");
        body.put("description", "Returned payment " + p.get("end_to_end_id").asString());
        body.put("reference", p.get("end_to_end_id").asString());
        ObjectNode refs = refs(p, true);
        refs.put("scheme_reference", "RTR" + digits(10));
        body.set("external_refs", refs);
        body.putArray("entries").add(leg(nostro, "DEBIT", p.get("amount").asString()))
                .add(leg(UUID.fromString(p.get("customer_ledger_account").asString()), "CREDIT", p.get("amount").asString()));
        UUID line = PaymentLedgerFake.lineOn(ledger.post(body, d), nostro);
        Tx tx = new Tx();
        tx.e2e = p.get("end_to_end_id").asString();
        tx.bankRef = "BK" + digits(14);
        tx.party = p.get("creditor_name").asString();
        tx.remittance = "RETURN AC04 CLOSED ACCOUNT";
        add(d, d, "CRDT", new BigDecimal(p.get("amount").asString()), tx, Set.of(line), "return/pacs004");
    }

    /** An inbound credit cancelled in the ledger before it reached the statement: original + reversal pair. */
    private void cancelledCredit(LocalDate d) {
        ObjectNode p = payment(d, false);
        ObjectNode body = json.createObjectNode();
        body.put("entry_type", "TRANSFER");
        body.put("reference", p.get("end_to_end_id").asString());
        body.set("external_refs", refs(p, false));
        body.putArray("entries").add(leg(nostro, "DEBIT", p.get("amount").asString()))
                .add(leg(UUID.fromString(p.get("customer_ledger_account").asString()), "CREDIT", p.get("amount").asString()));
        ObjectNode original = ledger.post(body, d);
        reversedPair.add(PaymentLedgerFake.lineOn(original, nostro));
        reversedPair.add(PaymentLedgerFake.lineOn(ledger.reverse(UUID.fromString(original.get("id").asString()), d), nostro));
    }

    private void bankCharge(LocalDate d) {
        Tx tx = new Tx();
        tx.bankRef = "CHG" + digits(12);
        tx.info = "ACCOUNT MAINTENANCE FEE";
        add(d, d, "DBIT", new BigDecimal("12.50"), tx, Set.of(), "bank-only/charge");
    }

    private ObjectNode leg(UUID account, String direction, String amount) {
        ObjectNode l = json.createObjectNode();
        l.put("ledger_account_id", account.toString());
        l.put("direction", direction);
        l.put("amount", amount);
        l.put("currency", CCY);
        return l;
    }

    // ------------------------------------------------------------------ camt.053

    static final class Tx {
        String bankRef;
        String e2e;
        String instr;
        String uetr;
        String party;
        String account;
        String remittance;
        String batchRef;
        Integer batchCount;
        String info;
    }

    private void add(LocalDate booked, LocalDate value, String dir, BigDecimal amount, Tx t, Set<UUID> expected,
                     String kind) {
        StringBuilder x = new StringBuilder();
        x.append("<Ntry><Amt Ccy=\"").append(CCY).append("\">").append(amount.toPlainString()).append("</Amt>")
                .append("<CdtDbtInd>").append(dir).append("</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts>")
                .append("<BookgDt><Dt>").append(booked).append("</Dt></BookgDt><ValDt><Dt>").append(value)
                .append("</Dt></ValDt>");
        if (t.bankRef != null) {
            x.append("<AcctSvcrRef>").append(t.bankRef).append("</AcctSvcrRef>");
        }
        x.append("<BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>").append("CRDT".equals(dir) ? "RCDT" : "ICDT")
                .append("</Cd><SubFmlyCd>ESCT</SubFmlyCd></Fmly></Domn></BkTxCd>");
        if (t.batchRef != null) {
            x.append("<NtryDtls><Btch><PmtInfId>").append(t.batchRef).append("</PmtInfId><NbOfTxs>")
                    .append(t.batchCount).append("</NbOfTxs></Btch></NtryDtls>");
        } else if (t.e2e != null || t.uetr != null || t.party != null || t.remittance != null || t.instr != null) {
            x.append("<NtryDtls><TxDtls><Refs>");
            if (t.instr != null) {
                x.append("<InstrId>").append(t.instr).append("</InstrId>");
            }
            x.append("<EndToEndId>").append(t.e2e == null ? "NOTPROVIDED" : t.e2e).append("</EndToEndId>");
            if (t.uetr != null) {
                x.append("<UETR>").append(t.uetr).append("</UETR>");
            }
            x.append("</Refs>");
            if (t.party != null) {
                String role = "CRDT".equals(dir) ? "Dbtr" : "Cdtr";
                x.append("<RltdPties><").append(role).append("><Pty><Nm>").append(t.party).append("</Nm></Pty></")
                        .append(role).append(">");
                if (t.account != null) {
                    x.append("<").append(role).append("Acct><Id><IBAN>").append(t.account).append("</IBAN></Id></")
                            .append(role).append("Acct>");
                }
                x.append("</RltdPties>");
            }
            if (t.remittance != null) {
                x.append("<RmtInf><Ustrd>").append(t.remittance).append("</Ustrd></RmtInf>");
            }
            x.append("</TxDtls></NtryDtls>");
        }
        if (t.info != null) {
            x.append("<AddtlNtryInf>").append(t.info).append("</AddtlNtryInf>");
        }
        x.append("</Ntry>");
        statement.add(new Line(booked, value, dir, amount, x.toString(), expected, kind));
    }

    private String camt053() {
        BigDecimal opening = new BigDecimal("2500000.00");
        BigDecimal closing = opening;
        StringBuilder entries = new StringBuilder();
        for (Line l : statement) {
            closing = "CRDT".equals(l.dir()) ? closing.add(l.amount()) : closing.subtract(l.amount());
            entries.append(l.xml()).append('\n');
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt>
                <GrpHdr><MsgId>CAMT053-W10-%s</MsgId><CreDtTm>2026-03-06T22:00:00Z</CreDtTm></GrpHdr>
                <Stmt><Id>NOSTRO-EUR-2026-W10</Id><ElctrncSeqNb>10</ElctrncSeqNb>
                <Acct><Id><IBAN>DE44500105175407324931</IBAN></Id><Ccy>EUR</Ccy></Acct>
                <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">%s</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>%s</Dt></Dt></Bal>
                <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">%s</Amt><CdtDbtInd>%s</CdtDbtInd><Dt><Dt>%s</Dt></Dt></Bal>
                %s</Stmt></BkToCstmrStmt></Document>
                """.formatted(digits(6), opening.toPlainString(), MON, closing.abs().toPlainString(),
                closing.signum() < 0 ? "DBIT" : "CRDT", FRI, entries);
    }

    // ------------------------------------------------------------------ data

    private static final String[] FIRST = {"Acme", "Blue River", "Northwind", "Contoso", "Fabrikam", "Globex",
            "Initech", "Umbrella", "Stark", "Wayne", "Hooli", "Vandelay", "Soylent", "Tyrell", "Cyberdyne", "Wonka"};
    private static final String[] LAST = {"Trading", "Logistics", "Foods", "Software", "Textiles", "Energy",
            "Consulting", "Holdings", "Retail", "Pharma"};
    private static final String[] FORM = {"GmbH", "Ltd", "SA", "BV", "SAS", "AG", "Oy", "SpA"};

    private String name() {
        return FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)] + " "
                + FORM[rnd.nextInt(FORM.length)];
    }

    private BigDecimal amount() {
        return BigDecimal.valueOf(1_000 + rnd.nextInt(900_000), 2);
    }

    private String iban() {
        return "DE" + digits(2) + digits(18);
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private String alnum(int n) {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(abc.charAt(rnd.nextInt(abc.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ http

    private List<JsonNode> all(String path) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            String sep = path.contains("?") ? "&" : "?";
            JsonNode page = get(path + sep + "limit=200"
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            page.get("data").forEach(out::add);
            cursor = page.path("has_more").asBoolean(false) ? page.path("next_cursor").asString(null) : null;
        } while (cursor != null);
        return out;
    }

    private JsonNode post(String user, String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", user, path, body);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", MAKER, path, null);
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String user, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
