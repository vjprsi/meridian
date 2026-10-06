package io.meridian.carddispute.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.carddispute.domain.DisputeStateMachine.Action;
import io.meridian.carddispute.domain.DisputeStateMachine.Outcome;
import io.meridian.carddispute.domain.DisputeStateMachine.Stage;
import io.meridian.carddispute.domain.DisputeStateMachine.State;
import io.meridian.carddispute.domain.DisputeStateMachine.Status;
import io.meridian.common.error.ApiException;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class DisputeStateMachineTest {
    static final State NEW = new State(Stage.INQUIRY, Status.OPEN);

    @Test
    void fullChainToArbitrationAndWin() {
        State s = DisputeStateMachine.next(NEW, Action.REQUEST_RETRIEVAL);
        assertThat(s).isEqualTo(new State(Stage.RETRIEVAL, Status.AWAITING_MERCHANT));
        s = DisputeStateMachine.next(s, Action.SUBMIT_CHARGEBACK);
        assertThat(s).isEqualTo(new State(Stage.FIRST_CHARGEBACK, Status.SUBMITTED));
        s = DisputeStateMachine.next(s, Action.RECORD_REPRESENTMENT);
        assertThat(s).isEqualTo(new State(Stage.REPRESENTMENT, Status.AWAITING_CUSTOMER));
        s = DisputeStateMachine.next(s, Action.ESCALATE_PRE_ARBITRATION);
        s = DisputeStateMachine.next(s, Action.ESCALATE_ARBITRATION);
        assertThat(s).isEqualTo(new State(Stage.ARBITRATION, Status.SUBMITTED));
        assertThat(DisputeStateMachine.next(s, Action.CLOSE_WON)).isEqualTo(new State(Stage.CLOSED, Status.WON));
    }

    @Test
    void inquiryMayGoStraightToChargebackButCannotBeWon() {
        assertThat(DisputeStateMachine.next(NEW, Action.SUBMIT_CHARGEBACK).stage()).isEqualTo(Stage.FIRST_CHARGEBACK);
        assertThatThrownBy(() -> DisputeStateMachine.next(NEW, Action.CLOSE_WON))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_transition"));
        assertThatThrownBy(() -> DisputeStateMachine.next(NEW, Action.ACCEPT_REPRESENTMENT))
                .isInstanceOf(ApiException.class);
        assertThat(DisputeStateMachine.next(NEW, Action.CLOSE_WITHDRAWN).status()).isEqualTo(Status.WITHDRAWN);
    }

    @Test
    void escalationTargetsAndCloseOutcomes() {
        assertThatThrownBy(() -> DisputeStateMachine.escalation(Stage.RETRIEVAL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_stage"));
        assertThatThrownBy(() -> DisputeStateMachine.closeAction(Outcome.EXPIRED))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_outcome"));
        State rep = new State(Stage.REPRESENTMENT, Status.AWAITING_CUSTOMER);
        assertThatThrownBy(() -> DisputeStateMachine.next(rep, Action.ESCALATE_ARBITRATION)).isInstanceOf(ApiException.class);
        assertThat(DisputeStateMachine.next(rep, Action.ACCEPT_REPRESENTMENT).status()).isEqualTo(Status.LOST);
        assertThat(DisputeStateMachine.next(rep, Action.CLOSE_SPLIT).status()).isEqualTo(Status.WON);
    }

    @Test
    void expiryOnlyWhileTheIssuerHasToAct() {
        assertThat(DisputeStateMachine.next(NEW, Action.EXPIRE).status()).isEqualTo(Status.EXPIRED);
        State submitted = new State(Stage.FIRST_CHARGEBACK, Status.SUBMITTED);
        assertThatThrownBy(() -> DisputeStateMachine.next(submitted, Action.EXPIRE)).isInstanceOf(ApiException.class);
    }

    @Property
    void noTransitionOutOfClosed(@ForAll Action action, @ForAll("closedStatuses") Status status) {
        State closed = new State(Stage.CLOSED, status);
        assertThatThrownBy(() -> DisputeStateMachine.next(closed, action)).isInstanceOf(ApiException.class);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<Status> closedStatuses() {
        return net.jqwik.api.Arbitraries.of(Status.WON, Status.LOST, Status.WITHDRAWN, Status.EXPIRED);
    }

    @Property
    void anyActionSequenceKeepsStageAndStatusConsistent(@ForAll @Size(max = 25) List<Action> actions) {
        State s = NEW;
        for (Action a : actions) {
            try {
                State n = DisputeStateMachine.next(s, a);
                assertThat(DisputeStateMachine.consistent(n)).as("%s -> %s via %s", s, n, a).isTrue();
                assertThat(n.stage().ordinal()).as("stages only move forward").isGreaterThanOrEqualTo(s.stage().ordinal());
                s = n;
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("invalid_transition");
            }
        }
        assertThat(DisputeStateMachine.consistent(s)).isTrue();
    }
}
