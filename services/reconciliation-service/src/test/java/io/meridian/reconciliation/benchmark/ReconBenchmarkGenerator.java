package io.meridian.reconciliation.benchmark;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import io.meridian.reconciliation.domain.matching.ExternalLine;
import io.meridian.reconciliation.domain.matching.InternalItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic synthetic reconciliation dataset with known ground truth: a day range of statement lines (bank /
 * scheme view, already in the ledger account's direction) and internal ledger / payment / clearing items, generated
 * from weighted scenarios that mirror production breaks:
 * <ul>
 *   <li>keyed payments (UETR, end-to-end id, instruction id, bank reference), partial / truncated references,
 *       remittance-only invoice and RF creditor references in varied formats;</li>
 *   <li>no reference at all (counterparty name with typos, truncation, initials, legal-form changes, reordering);</li>
 *   <li>fee deductions (charges stated or not), FX differences (same currency, or a statement in another currency
 *       carrying the instructed amount), value-date shifts;</li>
 *   <li>batch payments (one statement debit for 3-12 supplier payments), card scheme settlements net of
 *       interchange (5-30 sales), bulk receipts split by the bank (many lines to one item);</li>
 *   <li>reversals (statement-only pairs, ledger-only pairs, and both sides booked), duplicate statement lines;</li>
 *   <li>genuine breaks: bank-only lines (fees, interest), in-flight internal items, and look-alike decoys / twins
 *       (same amount and day, similar or identical names) that must not be auto-matched.</li>
 * </ul>
 * The same seed always yields the same dataset.
 */
public final class ReconBenchmarkGenerator {

    /** One ground-truth group: these lines belong with exactly these items (either side may be empty). */
    public record Truth(String scenario, Set<UUID> externalIds, Set<UUID> internalIds) {
        /** A line group that a perfect engine would reconcile (it has a counterpart or is a reversal pair). */
        public boolean matchable() {
            return !externalIds.isEmpty() && (!internalIds.isEmpty() || externalIds.size() >= 2);
        }
    }

    public record Dataset(List<ExternalLine> externals, List<InternalItem> internals, List<Truth> truth,
                          LocalDate from, LocalDate to, Map<String, Integer> scenarioCounts) {}

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 6);
    private static final String[] FIRST = {"Acme", "Nordic", "Blue River", "Atlas", "Helios", "Vertex", "Quantum",
            "Silverline", "Greenfield", "Orion", "Pinnacle", "Summit", "Harbor", "Crescent", "Falcon", "Evergreen",
            "Northwind", "Redwood", "Sterling", "Apex", "Brightwater", "Cedar", "Delta", "Echo", "Fusion", "Granite",
            "Horizon", "Iris", "Jadestone", "Keystone", "Lumen", "Maple", "Nova", "Oakridge", "Prime", "Questor",
            "Rapid", "Solaris", "Titan", "Unity", "Vista", "Willow", "Zenith", "Aurora", "Beacon", "Cobalt",
            "Dynamo", "Ember", "Fjord", "Glacier"};
    private static final String[] SECOND = {"Trading", "Logistics", "Systems", "Foods", "Retail", "Consulting",
            "Engineering", "Media", "Health", "Energy", "Partners", "Solutions", "Holdings", "Capital", "Labs",
            "Textiles", "Motors", "Pharma", "Construction", "Software", "Shipping", "Hospitality", "Farms", "Studios"};
    private static final String[] SUFFIX = {"Ltd", "GmbH", "SA", "BV", "Inc", "LLC", "PLC", "", "", ""};
    private static final String[] GIVEN = {"John", "Maria", "Ahmed", "Sofia", "Lukas", "Emma", "Noah", "Olivia",
            "Mateo", "Chloe", "Ivan", "Anna", "Kenji", "Fatima", "Pierre", "Ingrid", "Diego", "Priya", "Tomasz",
            "Elena", "Samuel", "Hannah", "Omar", "Laura", "Felix", "Nadia", "Jonas", "Clara", "Viktor", "Leila"};
    private static final String[] FAMILY = {"Smith", "Garcia", "Muller", "Rossi", "Kowalski", "Johansson", "Dubois",
            "Nakamura", "Okafor", "Petrov", "Silva", "Andersen", "Novak", "Fischer", "Moreau", "Haddad", "Larsen",
            "Costa", "Weber", "Brennan", "Schneider", "Van Dijk", "Lindqvist", "Horvath", "Papadopoulos", "Kim",
            "Nguyen", "Fernandez", "Baker", "Hughes", "Walsh", "Bergstrom", "Kaplan", "Ivanova", "Mendes", "Rahman"};
    private static final int[] ROUND = {50, 100, 150, 200, 250, 300, 500, 750, 1000, 1500, 2000, 2500, 5000};

    private final Random rnd;
    private final long seed;
    private long ids;
    private final List<ExternalLine> ext = new ArrayList<>();
    private final List<InternalItem> in = new ArrayList<>();
    private final List<Truth> truth = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    private ReconBenchmarkGenerator(long seed) {
        this.seed = seed;
        this.rnd = new Random(seed);
    }

    public static Dataset generate(long seed, int minExternalLines) {
        ReconBenchmarkGenerator g = new ReconBenchmarkGenerator(seed);
        String[] scenarios = {"uetr", "end_to_end", "instruction_id", "bank_ref", "remittance", "partial_ref",
                "fuzzy_name", "fee", "fx", "batch", "card_net", "bulk_split", "reversal", "date_shift", "duplicate",
                "bank_only", "in_flight", "twins"};
        double[] weights = {0.28, 0.20, 0.03, 0.06, 0.10, 0.02, 0.08, 0.05, 0.03, 0.025, 0.01, 0.01, 0.02, 0.03,
                0.01, 0.006, 0.006, 0.004};
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        while (g.ext.size() < minExternalLines) {
            double x = g.rnd.nextDouble() * total;
            int k = 0;
            while (x > weights[k] && k < weights.length - 1) {
                x -= weights[k];
                k++;
            }
            g.counts.merge(scenarios[k], 1, Integer::sum);
            switch (scenarios[k]) {
                case "uetr" -> g.keyed("uetr", false);
                case "end_to_end" -> g.keyed("end_to_end", false);
                case "instruction_id" -> g.keyed("instruction_id", false);
                case "bank_ref" -> g.keyed("bank_ref", false);
                case "remittance" -> g.remittance();
                case "partial_ref" -> g.partialRef();
                case "fuzzy_name" -> g.fuzzyName();
                case "fee" -> g.fee();
                case "fx" -> g.fx();
                case "batch" -> g.batch();
                case "card_net" -> g.cardNet();
                case "bulk_split" -> g.bulkSplit();
                case "reversal" -> g.reversal();
                case "date_shift" -> g.dateShift();
                case "duplicate" -> g.keyed("uetr", true);
                case "bank_only" -> g.bankOnly();
                case "in_flight" -> g.inFlight();
                case "twins" -> g.twins();
                default -> throw new IllegalStateException();
            }
        }
        return new Dataset(List.copyOf(g.ext), List.copyOf(g.in), List.copyOf(g.truth), FROM, TO, g.counts);
    }

    // ------------------------------------------------------------------ scenarios

    private void keyed(String key, boolean duplicate) {
        Direction dir = dir();
        String ccy = ccy();
        BigDecimal amount = amount();
        LocalDate d = date();
        String name = name();
        String e2e = e2e();
        String uetr = "uetr".equals(key) ? UUID.nameUUIDFromBytes(longBytes()).toString() : null;
        String instr = "instruction_id".equals(key) ? "INS" + digits(10) : null;
        String bankRef = "BR" + digits(12);
        InternalItem.Builder ib = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(name, iban());
        ExternalLine.Builder eb = ExternalLine.builder(id(), shift(d, 0.1, 1), dir, amount, ccy).bankRef(bankRef)
                .counterparty(typo(name, 0.3), null).remittance(generic());
        switch (key) {
            case "uetr" -> {
                ib.uetr(uetr).endToEndId(e2e).reference(e2e);
                eb.uetr(uetr).endToEndId(rnd.nextDouble() < 0.5 ? e2e : null);
            }
            case "end_to_end" -> {
                ib.endToEndId(e2e).reference(e2e);
                eb.endToEndId(e2e);
            }
            case "instruction_id" -> {
                ib.instructionId(instr).reference("PAY-" + digits(8));
                eb.instructionId(instr);
            }
            default -> ib.reference(bankRef); // our reference echoed back as the bank reference
        }
        InternalItem i = ib.build();
        ExternalLine e = eb.build();
        add(i);
        add(e);
        truth.add(new Truth(duplicate ? "duplicate" : key, Set.of(e.id()), Set.of(i.id())));
        if (duplicate) {
            ExternalLine dup = new ExternalLine(id(), e.valueDate(), e.direction(), e.amount(), e.currency(),
                    e.bankRef(), e.endToEndId(), e.instructionId(), e.uetr(), e.batchRef(), e.counterpartyName(),
                    e.counterpartyAccount(), e.remittance(), false, null, null, null);
            add(dup);
            truth.add(new Truth("duplicate_line", Set.of(dup.id()), Set.of()));
        }
    }

    private void remittance() {
        Direction dir = Direction.CREDIT;
        String ccy = ccy();
        BigDecimal amount = amount();
        LocalDate d = date();
        String name = name();
        String ref;
        String text;
        if (rnd.nextDouble() < 0.2) {
            String rf = "RF" + digits(2) + digits(12);
            ref = rf;
            text = "Payment " + rf.substring(0, 4) + " " + rf.substring(4, 8) + " " + rf.substring(8, 12) + " "
                    + rf.substring(12);
        } else {
            String year = "2026";
            String no = digits(5);
            ref = "INV-" + year + "-" + no;
            text = switch (rnd.nextInt(5)) {
                case 0 -> "INV " + year + "-" + no;
                case 1 -> "Invoice INV" + year + "/" + no + " thank you";
                case 2 -> "payment inv-" + year + "-" + no;
                case 3 -> "RE: INV " + year + " " + no + " " + name.toUpperCase(Locale.ROOT);
                default -> "Rechnung Nr. INV." + year + "." + no;
            };
        }
        InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).reference(ref).counterparty(name, null)
                .narrative("Invoice " + ref).build();
        ExternalLine e = ExternalLine.builder(id(), shift(d, 0.2, 2), dir, amount, ccy).bankRef("BR" + digits(12))
                .counterparty(rnd.nextDouble() < 0.7 ? typo(name, 0.4) : null, null).remittance(text).build();
        pair("remittance", e, i);
    }

    private void partialRef() {
        Direction dir = Direction.CREDIT;
        BigDecimal amount = amount();
        String ccy = ccy();
        LocalDate d = date();
        String name = name();
        String e2e = "E2E-" + name.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT) + "-" + digits(16);
        String invoice = "INV-2026-" + digits(5);
        InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).endToEndId(e2e).reference(invoice)
                .counterparty(name, null).build();
        ExternalLine e = ExternalLine.builder(id(), d, dir, amount, ccy).bankRef("BR" + digits(12))
                .endToEndId(e2e.substring(0, Math.min(16, e2e.length()))) // truncated by the scheme
                .counterparty(typo(name, 0.3), null).remittance("Ref " + invoice.replace("-", " ")).build();
        pair("partial_ref", e, i);
    }

    private void fuzzyName() {
        Direction dir = dir();
        String ccy = ccy();
        BigDecimal amount = amount();
        LocalDate d = date();
        String name = rnd.nextDouble() < 0.5 ? person() : name();
        InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(name, iban())
                .narrative(dir == Direction.CREDIT ? "Customer receipt" : "Supplier payment").build();
        int shift = rnd.nextDouble() < 0.7 ? 0 : rnd.nextDouble() < 0.85 ? 1 : 2;
        ExternalLine e = ExternalLine.builder(id(), d.plusDays(shift), dir, amount, ccy).bankRef("BR" + digits(12))
                .counterparty(typo(name, 0.6), null).remittance(generic()).build();
        pair("fuzzy_name", e, i);
        if (rnd.nextDouble() < 0.15) {
            // decoy: another counterparty with the same amount and day, never sent (stays a break)
            InternalItem decoy = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(otherName(name), null)
                    .narrative("Pending").build();
            add(decoy);
            truth.add(new Truth("decoy", Set.of(), Set.of(decoy.id())));
        }
    }

    private void fee() {
        Direction dir = Direction.CREDIT;
        String ccy = ccy();
        BigDecimal amount = amount().add(BigDecimal.valueOf(20));
        LocalDate d = date();
        String name = name();
        BigDecimal fee = BigDecimal.valueOf(50 + rnd.nextInt(1450), 2); // 0.50 .. 14.99
        double how = rnd.nextDouble();
        String e2e = e2e();
        String invoice = "INV-2026-" + digits(5);
        InternalItem.Builder ib = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(name, null);
        ExternalLine.Builder eb = ExternalLine.builder(id(), d, dir, amount.subtract(fee), ccy).bankRef("BR" + digits(12))
                .counterparty(typo(name, 0.2), null);
        if (rnd.nextDouble() < 0.4) {
            eb.charges(fee);
        }
        if (how < 0.6) {
            ib.endToEndId(e2e).reference(e2e);
            eb.endToEndId(e2e).remittance(generic());
        } else if (how < 0.85) {
            ib.reference(invoice);
            eb.remittance("Invoice " + invoice);
        } else {
            eb.remittance(generic());
            eb.counterparty(name, null);
        }
        pair("fee", eb.build(), ib.build());
    }

    private void fx() {
        Direction dir = dir();
        BigDecimal amount = amount();
        LocalDate d = date();
        String name = name();
        String e2e = e2e();
        InternalItem i = InternalItem.builder(id(), d, dir, amount, "EUR").endToEndId(e2e).reference(e2e)
                .counterparty(name, null).build();
        ExternalLine e;
        if (rnd.nextBoolean()) {
            double pct = 0.0005 + rnd.nextDouble() * 0.0075; // 0.05% .. 0.8%
            BigDecimal shifted = amount.multiply(BigDecimal.valueOf(1 + (rnd.nextBoolean() ? pct : -pct)))
                    .setScale(2, RoundingMode.HALF_EVEN);
            if (shifted.compareTo(amount) == 0) {
                shifted = shifted.add(new BigDecimal("0.01"));
            }
            e = ExternalLine.builder(id(), d, dir, shifted, "EUR").endToEndId(e2e).bankRef("BR" + digits(12))
                    .counterparty(name, null).build();
        } else {
            BigDecimal usd = amount.multiply(new BigDecimal("1.0873")).setScale(2, RoundingMode.HALF_EVEN);
            e = ExternalLine.builder(id(), d, dir, usd, "USD").endToEndId(rnd.nextBoolean() ? e2e : null)
                    .bankRef("BR" + digits(12)).counterparty(typo(name, 0.2), null).original(amount, "EUR").build();
        }
        pair("fx", e, i);
    }

    private void batch() {
        Direction dir = Direction.DEBIT;
        String ccy = "EUR";
        LocalDate d = date();
        String batch = "BATCH-" + alnum(8);
        int n = 3 + rnd.nextInt(10);
        Set<UUID> ids = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < n; k++) {
            BigDecimal a = amount();
            InternalItem i = InternalItem.builder(id(), d, dir, a, ccy).batchRef(batch).endToEndId(e2e())
                    .counterparty(name(), iban()).narrative("Supplier run " + batch).build();
            add(i);
            ids.add(i.id());
            sum = sum.add(a);
        }
        ExternalLine.Builder eb = ExternalLine.builder(id(), shift(d, 0.2, 1), dir, sum, ccy)
                .counterparty(null, null);
        if (rnd.nextBoolean()) {
            eb.bankRef(batch).remittance("SEPA BULK DEBIT");
        } else {
            eb.bankRef("BR" + digits(12)).remittance("BULK PAYMENT " + batch + " " + n + " TXNS");
        }
        ExternalLine e = eb.build();
        add(e);
        truth.add(new Truth("batch", Set.of(e.id()), ids));
    }

    private void cardNet() {
        Direction dir = Direction.CREDIT;
        String ccy = "EUR";
        LocalDate d = date();
        String settle = "SETTLE-" + d.toString().replace("-", "") + "-" + digits(4);
        int n = 5 + rnd.nextInt(26);
        Set<UUID> ids = new LinkedHashSet<>();
        BigDecimal gross = BigDecimal.ZERO;
        for (int k = 0; k < n; k++) {
            BigDecimal a = BigDecimal.valueOf(500 + rnd.nextInt(30000), 2);
            InternalItem i = InternalItem.builder(id(), d.minusDays(1), dir, a, ccy).batchRef(settle)
                    .reference("ARN" + digits(20)).narrative("Card sale").build();
            add(i);
            ids.add(i.id());
            gross = gross.add(a);
        }
        double feePct = 0.003 + rnd.nextDouble() * 0.015;
        BigDecimal fee = gross.multiply(BigDecimal.valueOf(feePct)).setScale(2, RoundingMode.HALF_EVEN);
        ExternalLine.Builder eb = ExternalLine.builder(id(), d, dir, gross.subtract(fee), ccy).bankRef("BR" + digits(12))
                .counterparty("VISA EUROPE", null).remittance("VISA NET SETTLEMENT " + settle);
        if (rnd.nextBoolean()) {
            eb.charges(fee);
        }
        ExternalLine e = eb.build();
        add(e);
        truth.add(new Truth("card_net", Set.of(e.id()), ids));
    }

    private void bulkSplit() {
        Direction dir = Direction.CREDIT;
        String ccy = "EUR";
        LocalDate d = date();
        String sweep = "SWEEP-" + alnum(8);
        int n = 2 + rnd.nextInt(3);
        Set<UUID> ids = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < n; k++) {
            BigDecimal a = amount();
            ExternalLine e = ExternalLine.builder(id(), d, dir, a, ccy).bankRef("BR" + digits(12))
                    .counterparty("Treasury Pool", null).remittance("CASH CONCENTRATION " + sweep + " PART " + (k + 1))
                    .build();
            add(e);
            ids.add(e.id());
            sum = sum.add(a);
        }
        InternalItem i = InternalItem.builder(id(), d, dir, sum, ccy).reference(sweep).narrative("Cash sweep").build();
        add(i);
        truth.add(new Truth("bulk_split", ids, Set.of(i.id())));
    }

    private void reversal() {
        String ccy = ccy();
        BigDecimal amount = amount();
        LocalDate d = date();
        String e2e = e2e();
        String bankRef = "BR" + digits(12);
        double kind = rnd.nextDouble();
        if (kind < 0.5) {
            // returned by the bank before we booked it: statement-only pair
            ExternalLine o = ExternalLine.builder(id(), d, Direction.DEBIT, amount, ccy).bankRef(bankRef).endToEndId(e2e)
                    .counterparty(name(), null).remittance(generic()).build();
            ExternalLine r = ExternalLine.builder(id(), shift(d, 0.5, 2), Direction.CREDIT, amount, ccy).bankRef(bankRef)
                    .endToEndId(e2e).reversal(rnd.nextDouble() < 0.9).remittance("RETURN AC04 " + bankRef).build();
            add(o);
            add(r);
            truth.add(new Truth("reversal_statement", Set.of(o.id(), r.id()), Set.of()));
        } else if (kind < 0.75) {
            // posted and reversed internally the same day, never sent
            InternalItem o = InternalItem.builder(id(), d, Direction.DEBIT, amount, ccy).endToEndId(e2e).reference(e2e).build();
            InternalItem r = InternalItem.builder(id(), d, Direction.CREDIT, amount, ccy).endToEndId(e2e).reference(e2e)
                    .reversal(true).build();
            add(o);
            add(r);
            truth.add(new Truth("reversal_ledger", Set.of(), Set.of(o.id(), r.id())));
        } else {
            // both sides booked the payment and its return
            InternalItem io = InternalItem.builder(id(), d, Direction.DEBIT, amount, ccy).endToEndId(e2e).reference(e2e).build();
            InternalItem ir = InternalItem.builder(id(), d.plusDays(1), Direction.CREDIT, amount, ccy).endToEndId(e2e)
                    .reference(e2e).reversal(true).build();
            ExternalLine eo = ExternalLine.builder(id(), d, Direction.DEBIT, amount, ccy).bankRef(bankRef).endToEndId(e2e).build();
            ExternalLine er = ExternalLine.builder(id(), d.plusDays(1), Direction.CREDIT, amount, ccy).bankRef(bankRef)
                    .endToEndId(e2e).reversal(true).build();
            add(io);
            add(ir);
            add(eo);
            add(er);
            truth.add(new Truth("reversal_both", Set.of(eo.id()), Set.of(io.id())));
            truth.add(new Truth("reversal_both", Set.of(er.id()), Set.of(ir.id())));
        }
    }

    private void dateShift() {
        Direction dir = dir();
        String ccy = ccy();
        BigDecimal amount = amount();
        LocalDate d = date();
        String name = name();
        if (rnd.nextBoolean()) {
            String e2e = e2e();
            InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).endToEndId(e2e).reference(e2e)
                    .counterparty(name, null).build();
            ExternalLine e = ExternalLine.builder(id(), d.plusDays(3 + rnd.nextInt(3)), dir, amount, ccy).endToEndId(e2e)
                    .bankRef("BR" + digits(12)).counterparty(name, null).build();
            pair("date_shift", e, i);
        } else {
            InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(name, null).build();
            ExternalLine e = ExternalLine.builder(id(), d.plusDays(2), dir, amount, ccy).bankRef("BR" + digits(12))
                    .counterparty(name, null).build();
            pair("date_shift", e, i);
        }
    }

    private void bankOnly() {
        LocalDate d = date();
        ExternalLine e = ExternalLine.builder(id(), d, Direction.DEBIT, BigDecimal.valueOf(100 + rnd.nextInt(5000), 2),
                ccy()).bankRef("BR" + digits(12)).remittance(rnd.nextBoolean() ? "ACCOUNT MAINTENANCE FEE" : "DEBIT INTEREST")
                .build();
        add(e);
        truth.add(new Truth("bank_only", Set.of(e.id()), Set.of()));
    }

    private void inFlight() {
        String e2e = e2e();
        InternalItem i = InternalItem.builder(id(), TO, dir(), amount(), ccy()).endToEndId(e2e).reference(e2e)
                .counterparty(name(), null).build();
        add(i);
        truth.add(new Truth("in_flight", Set.of(), Set.of(i.id())));
    }

    private void twins() {
        Direction dir = Direction.CREDIT;
        String ccy = ccy();
        BigDecimal amount = BigDecimal.valueOf(ROUND[rnd.nextInt(ROUND.length)]);
        LocalDate d = date();
        String name = person();
        for (int k = 0; k < 2; k++) {
            InternalItem i = InternalItem.builder(id(), d, dir, amount, ccy).counterparty(name, null).build();
            ExternalLine e = ExternalLine.builder(id(), d, dir, amount, ccy).bankRef("BR" + digits(12))
                    .counterparty(name, null).remittance("RENT").build();
            pair("twins", e, i);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void pair(String scenario, ExternalLine e, InternalItem i) {
        add(e);
        add(i);
        truth.add(new Truth(scenario, Set.of(e.id()), Set.of(i.id())));
    }

    private void add(ExternalLine e) {
        ext.add(e);
    }

    private void add(InternalItem i) {
        in.add(i);
    }

    private UUID id() {
        return new UUID(seed, ++ids);
    }

    private byte[] longBytes() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return b;
    }

    private Direction dir() {
        return rnd.nextDouble() < 0.55 ? Direction.CREDIT : Direction.DEBIT;
    }

    private String ccy() {
        double x = rnd.nextDouble();
        return x < 0.85 ? "EUR" : x < 0.95 ? "USD" : "GBP";
    }

    private LocalDate date() {
        return FROM.plusDays(rnd.nextInt(6));
    }

    private LocalDate shift(LocalDate d, double p, int maxDays) {
        return rnd.nextDouble() < p ? d.plusDays(1 + rnd.nextInt(maxDays)) : d;
    }

    private BigDecimal amount() {
        if (rnd.nextDouble() < 0.15) {
            return BigDecimal.valueOf(ROUND[rnd.nextInt(ROUND.length)]).setScale(2, RoundingMode.UNNECESSARY);
        }
        double v = Math.exp(Math.log(5) + rnd.nextDouble() * (Math.log(50_000) - Math.log(5)));
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    private String name() {
        if (rnd.nextDouble() < 0.35) {
            return person();
        }
        String suffix = SUFFIX[rnd.nextInt(SUFFIX.length)];
        return FIRST[rnd.nextInt(FIRST.length)] + " " + SECOND[rnd.nextInt(SECOND.length)]
                + (suffix.isEmpty() ? "" : " " + suffix);
    }

    private String person() {
        return GIVEN[rnd.nextInt(GIVEN.length)] + " " + FAMILY[rnd.nextInt(FAMILY.length)];
    }

    /** A different, plausible counterparty (shares the family / first word sometimes). */
    private String otherName(String name) {
        String n;
        do {
            n = rnd.nextBoolean() ? person() : name();
        } while (n.equalsIgnoreCase(name));
        return n;
    }

    /** Bank-side rendering of a name: upper case, legal form changes, a typo, truncation, initials, reordering. */
    private String typo(String name, double p) {
        if (rnd.nextDouble() >= p) {
            return rnd.nextBoolean() ? name.toUpperCase(Locale.ROOT) : name;
        }
        String[] parts = name.split(" ");
        return switch (rnd.nextInt(6)) {
            case 0 -> editToken(name);
            case 1 -> name.replace(" Ltd", " Limited").replace(" GmbH", "").replace(" Inc", " Incorporated")
                    .toUpperCase(Locale.ROOT);
            case 2 -> name.length() > 14 ? name.substring(0, 14) : editToken(name);
            case 3 -> parts.length == 2 && Character.isUpperCase(parts[0].charAt(0)) && isPerson(name)
                    ? parts[0].charAt(0) + " " + parts[1] : editToken(name);
            case 4 -> parts.length >= 2 && isPerson(name) ? (parts[1] + " " + parts[0]).toUpperCase(Locale.ROOT)
                    : name.toLowerCase(Locale.ROOT);
            default -> editToken(name).toUpperCase(Locale.ROOT);
        };
    }

    private boolean isPerson(String name) {
        for (String g : GIVEN) {
            if (name.startsWith(g + " ")) {
                return true;
            }
        }
        return false;
    }

    private String editToken(String name) {
        String[] parts = name.split(" ");
        int idx = -1;
        for (int k = 0; k < parts.length; k++) {
            if (parts[k].length() >= 5) {
                idx = k;
                break;
            }
        }
        if (idx < 0) {
            return name;
        }
        char[] c = parts[idx].toCharArray();
        int pos = 1 + rnd.nextInt(c.length - 2);
        switch (rnd.nextInt(3)) {
            case 0 -> {
                char t = c[pos];
                c[pos] = c[pos + 1];
                c[pos + 1] = t;
            }
            case 1 -> c[pos] = (char) ('a' + rnd.nextInt(26));
            default -> {
                String s = new String(c);
                parts[idx] = s.substring(0, pos) + s.substring(pos + 1);
                return String.join(" ", parts);
            }
        }
        parts[idx] = new String(c);
        return String.join(" ", parts);
    }

    private String generic() {
        String[] g = {null, "PAYMENT", "TRANSFER", "SEPA CREDIT TRANSFER", "SALARY", "SERVICES", "MONTHLY FEE",
                "CONSULTING SERVICES", "ORDER CONFIRMED"};
        return g[rnd.nextInt(g.length)];
    }

    private String e2e() {
        return "E2E" + alnum(12);
    }

    private String iban() {
        return "DE" + digits(2) + digits(18);
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            sb.append((char) ('0' + rnd.nextInt(10)));
        }
        return sb.toString();
    }

    private String alnum(int n) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            sb.append(a.charAt(rnd.nextInt(a.length())));
        }
        return sb.toString();
    }
}
