package io.meridian.gl.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A product / account-type / entry-type to GL account mapping rule. Null criteria match anything. */
public record MappingRule(UUID id, UUID legalEntityId, int priority, UUID productId, String ledgerAccountType,
                          String ownerType, String entryType, String balanceSign, UUID glAccountId,
                          LocalDate validFrom, LocalDate validTo) {

    /** What a posting looks like when it is mapped. A null legal entity matches rules of any entity. */
    public record Criteria(UUID legalEntityId, UUID productId, String ledgerAccountType, String ownerType,
                           String entryType, String balanceSign, LocalDate asOf) {}

    public boolean matches(Criteria c) {
        String sign = c.balanceSign() == null ? "POSITIVE" : c.balanceSign();
        return (c.legalEntityId() == null || legalEntityId.equals(c.legalEntityId()))
                && (productId == null || productId.equals(c.productId()))
                && (ledgerAccountType == null || ledgerAccountType.equals(c.ledgerAccountType()))
                && (ownerType == null || ownerType.equals(c.ownerType()))
                && (entryType == null || entryType.equals(c.entryType()))
                && (balanceSign == null || balanceSign.equals(sign))
                && effectiveOn(c.asOf());
    }

    public boolean effectiveOn(LocalDate d) {
        return !d.isBefore(validFrom) && (validTo == null || !d.isAfter(validTo));
    }

    /** Number of non-wildcard criteria: more specific rules win ties on priority. */
    public int specificity() {
        int s = 0;
        for (Object o : new Object[] {productId, ledgerAccountType, ownerType, entryType, balanceSign}) {
            if (o != null) {
                s++;
            }
        }
        return s;
    }

    public boolean sameCriteria(MappingRule o) {
        return legalEntityId.equals(o.legalEntityId) && Objects.equals(productId, o.productId)
                && Objects.equals(ledgerAccountType, o.ledgerAccountType) && Objects.equals(ownerType, o.ownerType)
                && Objects.equals(entryType, o.entryType) && Objects.equals(balanceSign, o.balanceSign);
    }

    public boolean overlaps(MappingRule o) {
        LocalDate end = validTo == null ? LocalDate.MAX : validTo;
        LocalDate oEnd = o.validTo == null ? LocalDate.MAX : o.validTo;
        return !validFrom.isAfter(oEnd) && !o.validFrom.isAfter(end);
    }
}
