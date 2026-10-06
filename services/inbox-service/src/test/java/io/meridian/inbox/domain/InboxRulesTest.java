package io.meridian.inbox.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;

class InboxRulesTest {
    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 6, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void statusFollowsWhoSpokeLast() {
        assertThat(ThreadRules.statusAfter("OPEN", "CUSTOMER", false)).isEqualTo("AWAITING_BANK");
        assertThat(ThreadRules.statusAfter("AWAITING_BANK", "STAFF", false)).isEqualTo("AWAITING_CUSTOMER");
        assertThat(ThreadRules.statusAfter("AWAITING_BANK", "BOT", false)).isEqualTo("AWAITING_CUSTOMER");
        assertThat(ThreadRules.statusAfter("AWAITING_BANK", "STAFF", true)).isEqualTo("AWAITING_BANK");
    }

    @Test
    void sendingRules() {
        assertThat(ThreadRules.canSend("CLOSED", "CUSTOMER", false).error()).isEqualTo("thread_closed");
        assertThat(ThreadRules.canSend("OPEN", "CUSTOMER", true).error()).isEqualTo("internal_note_not_allowed");
        assertThat(ThreadRules.canSend("OPEN", "ROBOT", false).error()).isEqualTo("invalid_sender");
        assertThat(ThreadRules.canSend("OPEN", "STAFF", true).allowed()).isTrue();
    }

    @Property
    void internalNotesAreNeverUnreadNorMarked(@ForAll("senders") String sender, @ForAll("readers") String reader) {
        assertThat(ThreadRules.unreadForCustomer(sender, true)).isNull();
        assertThat(ThreadRules.marksAsRead(reader, sender, true)).isFalse();
    }

    @Property
    void aReaderNeverMarksTheirOwnSideAndUnreadMatchesReading(@ForAll("senders") String sender) {
        Boolean forCustomer = ThreadRules.unreadForCustomer(sender, false);
        assertThat(ThreadRules.marksAsRead("CUSTOMER", sender, false)).isEqualTo(forCustomer);
        assertThat(ThreadRules.marksAsRead("STAFF", sender, false)).isEqualTo(!forCustomer);
    }

    @Provide
    Arbitrary<String> senders() {
        return Arbitraries.of("CUSTOMER", "STAFF", "SYSTEM", "BOT");
    }

    @Provide
    Arbitrary<String> readers() {
        return Arbitraries.of("CUSTOMER", "STAFF");
    }

    @Test
    void reopenWindow() {
        assertThat(ThreadRules.canReopen("OPEN", null, NOW, 30).error()).isEqualTo("invalid_transition");
        assertThat(ThreadRules.canReopen("CLOSED", NOW.minusDays(29), NOW, 30).allowed()).isTrue();
        assertThat(ThreadRules.canReopen("CLOSED", NOW.minusDays(30), NOW, 30).error()).isEqualTo("reopen_window_passed");
        assertThat(ThreadRules.canReopen("CLOSED", NOW.minusDays(300), NOW, 0).allowed()).isTrue();
    }

    @Test
    void publishAndSchedule() {
        assertThat(AnnouncementRules.statusOnPublish(NOW.plusHours(1), null, NOW)).isEqualTo("SCHEDULED");
        assertThat(AnnouncementRules.statusOnPublish(NOW.minusHours(1), NOW.plusDays(1), NOW)).isEqualTo("LIVE");
        assertThat(AnnouncementRules.statusOnPublish(NOW.minusDays(2), NOW.minusDays(1), NOW)).isNull();
        assertThat(AnnouncementRules.scheduledStatus("SCHEDULED", NOW.minusMinutes(1), null, NOW)).isEqualTo("LIVE");
        assertThat(AnnouncementRules.scheduledStatus("LIVE", NOW.minusDays(1), NOW, NOW)).isEqualTo("EXPIRED");
        assertThat(AnnouncementRules.scheduledStatus("WITHDRAWN", NOW.minusDays(1), null, NOW)).isEqualTo("WITHDRAWN");
        assertThat(AnnouncementRules.allowed("publish", "DRAFT")).isTrue();
        assertThat(AnnouncementRules.allowed("withdraw", "DRAFT")).isFalse();
        assertThat(AnnouncementRules.allowed("withdraw", "LIVE")).isTrue();
    }

    @Test
    void targeting() {
        OffsetDateTime start = NOW.minusHours(1);
        assertThat(AnnouncementRules.visible("LIVE", start, null, NOW, List.of(), "CUSTOMER_WEB", List.of(), null)).isTrue();
        assertThat(AnnouncementRules.visible("LIVE", start, null, NOW, List.of("CUSTOMER_MOBILE"), "CUSTOMER_WEB", List.of(), null))
                .isFalse();
        assertThat(AnnouncementRules.visible("LIVE", start, null, NOW, List.of(), "CUSTOMER_WEB", List.of("s1"), Set.of("s2")))
                .isFalse();
        assertThat(AnnouncementRules.visible("LIVE", start, null, NOW, List.of(), "CUSTOMER_WEB", List.of("s1"), Set.of("s1", "s2")))
                .isTrue();
        assertThat(AnnouncementRules.visible("LIVE", start, null, NOW, List.of(), "CUSTOMER_WEB", List.of("s1"), null))
                .as("segment lookup failed: fail closed").isFalse();
        assertThat(AnnouncementRules.visible("SCHEDULED", NOW.plusHours(1), null, NOW, List.of(), "CUSTOMER_WEB", List.of(), null))
                .isFalse();
        assertThat(AnnouncementRules.visible("SCHEDULED", start, null, NOW, List.of(), "CUSTOMER_WEB", List.of(), null))
                .as("due but not yet refreshed").isTrue();
    }
}
