package io.meridian.reconciliation.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Each rule type of the engine, ambiguity guards, breaks and explanations. */
class MatchingEngineTest {
    static final LocalDate D = LocalDate.of(2026, 10, 6);
    long ids;

    UUID id() {
        return new UUID(7, ++ids);
    }

    ExternalLine.Builder line(String amount) {
        return ExternalLine.builder(id(), D, Direction.CREDIT, new BigDecimal(amount), "EUR");
    }

    InternalItem.Builder item(String amount) {
        return InternalItem.builder(id(), D, Direction.CREDIT, new BigDecimal(amount), "EUR");
    }

    static MatchingEngine.Result run(List<ExternalLine> e, List<InternalItem> i) {
        return MatchingEngine.run(RuleSet.standard(), e, i, D.minusDays(5), D);
    }

    static MatchingEngine.Result run(MatchRule rule, List<ExternalLine> e, List<InternalItem> i) {
        return MatchingEngine.run(new RuleSet(null, 1, 0.9, 0.5, List.of(rule)), e, i, D.minusDays(5), D);
    }

    @Test
    void exactKeysUetrEndToEndInstructionAndBankReference() {
        ExternalLine u = line("100.00").uetr("3f2504e0-4f89-41d3-9a0c-0305e82c3301").build();
        InternalItem iu = item("100.00").uetr("3F2504E0-4F89-41D3-9A0C-0305E82C3301").build();
        ExternalLine e = line("200.00").endToEndId("E2E-1").build();
        InternalItem ie = item("200.00").reference("E2E-1").build(); // ledger: e2e copied into the journal reference
        ExternalLine in = line("300.00").instructionId("INSTR-9").build();
        InternalItem iin = item("300.00").instructionId("INSTR-9").build();
        ExternalLine b = line("400.00").bankRef("BR-0042").build();
        InternalItem ib = item("400.00").reference("br 0042").build();
        MatchingEngine.Result r = run(List.of(u, e, in, b), List.of(iu, ie, iin, ib));
        assertThat(r.matches()).hasSize(4);
        assertThat(r.breaks()).isEmpty();
        assertThat(rules(r)).containsExactlyInAnyOrder("exact_uetr", "exact_end_to_end", "exact_end_to_end", "exact_bank_ref");
        MatchingEngine.Proposal p = r.matches().stream().filter(m -> m.externalIds().contains(u.id())).findFirst().orElseThrow();
        assertThat(p.internalIds()).containsExactly(iu.id());
        assertThat(p.confidence()).isEqualTo(1.0);
        assertThat(p.explanation()).containsEntry("rule", "exact_uetr").containsKey("components");
        assertThat(p.explanation().get("keys").toString()).contains("uetr");
    }

    @Test
    void ledgerPaymentReferencesFeedTheBankReferenceAndCounterpartyAccount() {
        // RECON-REAL-REFS: the ledger entry's clearing-system and scheme references match the statement's bank ref
        ExternalLine clr = line("510.00").bankRef("SCT000123456789").build();
        InternalItem iclr = item("510.00").reference("E2E-OTHER-1").bankRef("SCT000123456789").build();
        ExternalLine arn = line("520.00").bankRef("74987654321098765432101").build();
        InternalItem iarn = item("520.00").schemeRef("74987654321098765432101").build();
        // same pseudonymised account: identifies the payer although the statement shortens the name beyond fuzzy reach
        ExternalLine acct = line("530.00").counterparty("ACME", "h1:" + "ab".repeat(32)).build();
        InternalItem iacct = item("530.00").counterparty("Northwind Trading GmbH", "h1:" + "ab".repeat(32)).build();
        InternalItem decoy = item("530.00").counterparty("Northwind Trading GmbH", "h1:" + "cd".repeat(32)).build();
        MatchingEngine.Result r = run(List.of(clr, arn, acct), List.of(iclr, iarn, iacct, decoy));
        assertThat(match(r, clr).internalIds()).containsExactly(iclr.id());
        assertThat(match(r, clr).ruleCode()).isEqualTo("exact_bank_ref");
        assertThat(match(r, arn).internalIds()).containsExactly(iarn.id());
        assertThat(match(r, acct).internalIds()).containsExactly(iacct.id());
        assertThat(match(r, acct).explanation().get("components").toString()).contains("counterparty_account=EQUAL");
    }

    @Test
    void feeChargesAndFxDifferencesAreExplained() {
        ExternalLine fee = line("97.50").endToEndId("E2E-F").build();
        InternalItem ifee = item("100.00").reference("E2E-F").build();
        ExternalLine charges = line("4987.50").uetr("U-CHARGES-0001").charges(new BigDecimal("12.50")).build();
        InternalItem icharges = item("5000.00").uetr("U-CHARGES-0001").build();
        ExternalLine usd = ExternalLine.builder(id(), D, Direction.CREDIT, new BigDecimal("1087.30"), "USD")
                .endToEndId("E2E-FX").original(new BigDecimal("1000.00"), "EUR").build();
        InternalItem iusd = item("1000.00").reference("E2E-FX").build();
        ExternalLine fx = line("20300.00").endToEndId("E2E-FX2").build();
        InternalItem ifx = item("20000.00").reference("E2E-FX2").build();
        MatchingEngine.Result r = run(List.of(fee, charges, usd, fx), List.of(ifee, icharges, iusd, ifx));
        assertThat(r.matches()).hasSize(4);
        assertThat(fit(r, fee)).isEqualTo("FEE");
        assertThat(fit(r, charges)).isEqualTo("CHARGES");
        assertThat(fit(r, usd)).isEqualTo("ORIGINAL_AMOUNT");
        assertThat(fit(r, fx)).isEqualTo("FX");
        assertThat(match(r, fee).difference()).isEqualByComparingTo("-2.50");
    }

    @Test
    void keyMatchOutsideToleranceBecomesAnAmountMismatchSuggestion() {
        ExternalLine e = line("150.00").endToEndId("E2E-X").build();
        InternalItem i = item("1000.00").reference("E2E-X").build();
        MatchingEngine.Result r = run(List.of(e), List.of(i));
        assertThat(r.matches()).isEmpty();
        MatchingEngine.Break b = r.breaks().stream().filter(x -> e.id().equals(x.externalId())).findFirst().orElseThrow();
        assertThat(b.reason()).isEqualTo(MatchingEngine.Reason.AMOUNT_MISMATCH);
        assertThat(b.suggestion().internalIds()).containsExactly(i.id());
        assertThat(r.breaks()).as("the suggested item raises no MISSING_EXTERNAL").hasSize(1);
    }

    @Test
    void remittanceReferencesWithStandardAndTenantPatterns() {
        ExternalLine e1 = line("250.00").remittance("Payment for invoice INV 2026/00123, thanks").build();
        InternalItem i1 = item("250.00").reference("INV-2026-00123").build();
        ExternalLine e2 = line("75.00").remittance("Payment RF18 5390 0754 7034").build();
        InternalItem i2 = item("75.00").reference("RF18539007547034").build();
        MatchingEngine.Result r = run(List.of(e1, e2), List.of(i1, i2));
        assertThat(rules(r)).containsExactly("remittance_ref", "remittance_ref");

        // tenant pattern: policy numbers "POL" + 8 digits written with spaces / slashes
        MatchRule custom = MatchRule.builder("policy", MatchRule.Type.REFERENCE_EXTRACT)
                .patterns(List.of("(?i)POL[ /-]?(\\d{4}[ /-]?\\d{4})")).window(5).build();
        ExternalLine e3 = line("80.00").remittance("premium pol 1234/5678").build();
        InternalItem i3 = item("80.00").narrative("Policy premium POL-1234-5678").build();
        MatchingEngine.Result r3 = run(custom, List.of(e3), List.of(i3));
        assertThat(r3.matches()).singleElement().satisfies(p -> assertThat(p.explanation().get("keys").toString()).contains("12345678"));
    }

    @Test
    void fuzzyNameAmountDateWithTyposAndInitials() {
        ExternalLine e1 = line("1234.56").counterparty("ACME TRADNG LIMITED", null).build();
        InternalItem i1 = item("1234.56").counterparty("Acme Trading Ltd", null).build();
        ExternalLine e2 = ExternalLine.builder(id(), D.plusDays(1), Direction.CREDIT, new BigDecimal("88.10"), "EUR")
                .counterparty("J SMITH", null).build();
        InternalItem i2 = item("88.10").counterparty("John Smith", null).build();
        InternalItem decoy = item("88.10").counterparty("Maria Garcia", null).build();
        MatchingEngine.Result r = run(List.of(e1, e2), List.of(i1, i2, decoy));
        assertThat(r.matches()).hasSize(2);
        assertThat(match(r, e2).internalIds()).containsExactly(i2.id());
        Map<?, ?> components = (Map<?, ?>) match(r, e1).explanation().get("components");
        assertThat(components.keySet().stream().map(String::valueOf).toList()).contains("name", "amount", "date");
        assertThat(r.breaks()).singleElement().satisfies(b -> {
            assertThat(b.reason()).isEqualTo(MatchingEngine.Reason.MISSING_EXTERNAL);
            assertThat(b.internalId()).isEqualTo(decoy.id());
        });
    }

    @Test
    void ambiguousTwinsAreSuggestedNeverAutoMatched() {
        ExternalLine e1 = line("500.00").counterparty("Anna Novak", null).build();
        ExternalLine e2 = line("500.00").counterparty("Anna Novak", null).build();
        InternalItem i1 = item("500.00").counterparty("Anna Novak", null).build();
        InternalItem i2 = item("500.00").counterparty("ANNA NOVAK", null).build();
        MatchingEngine.Result r = run(List.of(e1, e2), List.of(i1, i2));
        assertThat(r.matches()).isEmpty();
        assertThat(r.breaks()).filteredOn(b -> b.externalId() != null).hasSize(2)
                .allSatisfy(b -> {
                    assertThat(b.reason()).isEqualTo(MatchingEngine.Reason.AMBIGUOUS);
                    assertThat(b.suggestion().ambiguous()).isTrue();
                });
    }

    @Test
    void oneToManyBatchAndCardSettlementNetOfFees() {
        List<InternalItem> batch = List.of(item("100.00").batchRef("BATCH-7Q2X9").build(),
                item("250.00").batchRef("BATCH-7Q2X9").build(), item("650.00").batchRef("BATCH-7Q2X9").build());
        ExternalLine bulk = line("1000.00").bankRef("BATCH-7Q2X9").build();
        List<InternalItem> sales = new ArrayList<>();
        for (String a : List.of("120.00", "80.00", "300.00", "500.00")) {
            sales.add(InternalItem.builder(id(), D.minusDays(1), Direction.CREDIT, new BigDecimal(a), "EUR")
                    .batchRef("SETTLE-20261006-0042").build());
        }
        ExternalLine net = line("985.00").remittance("VISA NET SETTLEMENT SETTLE-20261006-0042").build();
        List<InternalItem> all = new ArrayList<>(batch);
        all.addAll(sales);
        MatchingEngine.Result r = run(List.of(bulk, net), all);
        assertThat(r.matches()).hasSize(2).allSatisfy(p -> assertThat(p.matchType()).isEqualTo("ONE_TO_MANY"));
        assertThat(match(r, bulk).internalIds()).hasSize(3);
        assertThat(match(r, net).internalIds()).hasSize(4);
        assertThat(fit(r, net)).isEqualTo("NET_OF_FEES");
        assertThat(r.breaks()).isEmpty();
    }

    @Test
    void partialBatchIsFoundBySubsetSumOnlyWhenUnique() {
        // the bank booked 3 of the 4 batch items today; exactly one subset adds up
        List<InternalItem> items = List.of(item("100.00").batchRef("BATCH-PART1").build(),
                item("200.00").batchRef("BATCH-PART1").build(), item("450.00").batchRef("BATCH-PART1").build(),
                item("975.00").batchRef("BATCH-PART1").build());
        ExternalLine partial = line("750.00").bankRef("BATCH-PART1").build();
        MatchingEngine.Result r = run(List.of(partial), items);
        assertThat(match(r, partial).internalIds()).hasSize(3);
        assertThat(((Map<?, ?>) match(r, partial).explanation().get("components")).get("subset_sum")).isEqualTo("UNIQUE");

        // two subsets reach the target: suggestion only
        List<InternalItem> twins = List.of(item("100.00").batchRef("BATCH-PART2").build(),
                item("100.00").batchRef("BATCH-PART2").build(), item("50.00").batchRef("BATCH-PART2").build(),
                item("999.00").batchRef("BATCH-PART2").build());
        ExternalLine amb = line("150.00").bankRef("BATCH-PART2").build();
        MatchingEngine.Result r2 = run(List.of(amb), twins);
        assertThat(r2.matches()).isEmpty();
        assertThat(r2.breaks().stream().filter(b -> amb.id().equals(b.externalId())).findFirst().orElseThrow().reason())
                .isEqualTo(MatchingEngine.Reason.AMBIGUOUS);
    }

    @Test
    void keylessSubsetSumIsASuggestionByDefault() {
        List<InternalItem> items = List.of(item("40.00").counterparty("Fjord Shipping", null).build(),
                item("60.00").counterparty("Fjord Shipping", null).build(),
                item("15.00").counterparty("Fjord Shipping", null).build());
        ExternalLine e = line("100.00").counterparty("FJORD SHIPPING AS", null).build();
        MatchRule keyless = MatchRule.builder("subset", MatchRule.Type.ONE_TO_MANY).window(3).nameThreshold(0.85)
                .group(20, 20, 100_000).build();
        MatchingEngine.Result r = run(keyless, List.of(e), items);
        assertThat(r.matches()).isEmpty();
        MatchingEngine.Break b = r.breaks().stream().filter(x -> e.id().equals(x.externalId())).findFirst().orElseThrow();
        assertThat(b.suggestion().internalIds()).hasSize(2);
        MatchingEngine.Result auto = run(keyless.toBuilder().autoWithoutKey(true).build(), List.of(e), items);
        assertThat(auto.matches()).singleElement().satisfies(p -> assertThat(p.internalIds()).hasSize(2));
    }

    @Test
    void manyToOneBulkCreditSplitByTheBank() {
        InternalItem sweep = item("1500.00").reference("SWEEP-AB12CD34").build();
        ExternalLine p1 = line("1000.00").remittance("CASH CONCENTRATION SWEEP-AB12CD34 PART 1").build();
        ExternalLine p2 = line("500.00").remittance("CASH CONCENTRATION SWEEP-AB12CD34 PART 2").build();
        MatchingEngine.Result r = run(List.of(p1, p2), List.of(sweep));
        assertThat(r.matches()).singleElement().satisfies(p -> {
            assertThat(p.matchType()).isEqualTo("MANY_TO_ONE");
            assertThat(p.externalIds()).containsExactlyInAnyOrder(p1.id(), p2.id());
        });
    }

    @Test
    void reversalPairsOnBothSidesAndDuplicates() {
        ExternalLine out = ExternalLine.builder(id(), D.minusDays(1), Direction.DEBIT, new BigDecimal("75.00"), "EUR")
                .bankRef("BR-RET-1").endToEndId("E2E-RET-1").build();
        ExternalLine back = line("75.00").bankRef("BR-RET-1").endToEndId("E2E-RET-1").reversal(true).build();
        InternalItem posted = InternalItem.builder(id(), D, Direction.DEBIT, new BigDecimal("30.00"), "EUR")
                .reference("E2E-INT-9").build();
        InternalItem reversed = item("30.00").reference("E2E-INT-9").reversal(true).build();
        ExternalLine first = line("10.00").bankRef("BR-DUP").build();
        ExternalLine dup = ExternalLine.builder(id(), D, Direction.CREDIT, new BigDecimal("10.00"), "EUR").bankRef("BR-DUP").build();
        InternalItem paid = item("10.00").reference("BR-DUP").build();
        MatchingEngine.Result r = run(List.of(out, back, first, dup), List.of(posted, reversed, paid));
        assertThat(r.duplicates()).isEqualTo(1);
        assertThat(r.matches()).hasSize(3);
        MatchingEngine.Proposal pair = match(r, back);
        assertThat(pair.externalIds()).containsExactlyInAnyOrder(out.id(), back.id());
        assertThat(pair.internalIds()).isEmpty();
        assertThat(pair.explanation()).containsEntry("reversal_pair", true);
        assertThat(r.matches().stream().anyMatch(p -> p.internalIds().containsAll(List.of(posted.id(), reversed.id()))))
                .isTrue();
        assertThat(r.breaks()).singleElement().satisfies(b -> assertThat(b.reason()).isEqualTo(MatchingEngine.Reason.DUPLICATE));

        // without the reversal flag the opposite line is only suggested
        ExternalLine refund = line("75.00").bankRef("BR-RET-2").build();
        ExternalLine paidOut = ExternalLine.builder(id(), D.minusDays(1), Direction.DEBIT, new BigDecimal("75.00"), "EUR")
                .bankRef("BR-RET-2").build();
        MatchingEngine.Result r2 = run(MatchRule.builder("rev", MatchRule.Type.REVERSAL_PAIR).keys("bank_ref")
                .requireReversalFlag(false).window(5).build(), List.of(paidOut, refund), List.of());
        assertThat(r2.matches()).isEmpty();
        assertThat(r2.breaks()).allSatisfy(b -> assertThat(b.suggestion()).isNotNull());
    }

    @Test
    void laterRulesOnlySeeWhatEarlierRulesLeftAndStatsAreKept() {
        ExternalLine e = line("100.00").endToEndId("E2E-ORDER").counterparty("Acme", null).build();
        InternalItem i = item("100.00").reference("E2E-ORDER").counterparty("Acme", null).build();
        MatchingEngine.Result r = run(List.of(e), List.of(i));
        assertThat(rules(r)).containsExactly("exact_end_to_end");
        assertThat(r.ruleStats().get("exact_end_to_end")).containsEntry("auto_matches", 1L);
        assertThat(r.ruleStats().get("fuzzy_exact_amount")).containsEntry("candidates", 0L);
        assertThat(r.autoMatchedExternal()).isEqualTo(1);
    }

    @Test
    void directionAndCurrencyMustAgree() {
        ExternalLine e = line("100.00").endToEndId("E2E-DIR").build();
        InternalItem wrongDir = InternalItem.builder(id(), D, Direction.DEBIT, new BigDecimal("100.00"), "EUR")
                .reference("E2E-DIR").build();
        InternalItem wrongCcy = InternalItem.builder(id(), D, Direction.CREDIT, new BigDecimal("100.00"), "GBP")
                .reference("E2E-DIR").build();
        MatchingEngine.Result r = run(List.of(e), List.of(wrongDir, wrongCcy));
        assertThat(r.matches()).isEmpty();
        assertThat(r.breaks().stream().filter(b -> e.id().equals(b.externalId())).findFirst().orElseThrow().reason())
                .isEqualTo(MatchingEngine.Reason.CURRENCY_MISMATCH);
    }

    static List<String> rules(MatchingEngine.Result r) {
        return r.matches().stream().map(MatchingEngine.Proposal::ruleCode).toList();
    }

    static MatchingEngine.Proposal match(MatchingEngine.Result r, ExternalLine e) {
        return r.matches().stream().filter(m -> m.externalIds().contains(e.id())).findFirst().orElseThrow();
    }

    static String fit(MatchingEngine.Result r, ExternalLine e) {
        return String.valueOf(((Map<?, ?>) match(r, e).explanation().get("components")).get("amount_fit"));
    }
}
