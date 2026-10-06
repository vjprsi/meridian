package io.meridian.carddispute.domain;

import io.meridian.common.error.ApiException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Scheme dispute lifecycle. Stages: INQUIRY -> (RETRIEVAL) -> FIRST_CHARGEBACK -> REPRESENTMENT -> PRE_ARBITRATION
 * -> ARBITRATION -> CLOSED. The status always agrees with the stage (see {@link #consistent}); CLOSED is terminal.
 */
public final class DisputeStateMachine {
    private DisputeStateMachine() {}

    public enum Stage { INQUIRY, RETRIEVAL, FIRST_CHARGEBACK, REPRESENTMENT, PRE_ARBITRATION, ARBITRATION, CLOSED }

    public enum Status { OPEN, AWAITING_CUSTOMER, AWAITING_MERCHANT, SUBMITTED, WON, LOST, WITHDRAWN, EXPIRED }

    public enum Outcome { WON, LOST, SPLIT, WITHDRAWN, EXPIRED }

    public enum Action {
        REQUEST_RETRIEVAL, SUBMIT_CHARGEBACK, RECORD_REPRESENTMENT, ESCALATE_PRE_ARBITRATION, ESCALATE_ARBITRATION,
        ACCEPT_REPRESENTMENT, CLOSE_WON, CLOSE_LOST, CLOSE_SPLIT, CLOSE_WITHDRAWN, EXPIRE
    }

    public record State(Stage stage, Status status) {
        public boolean closed() {
            return stage == Stage.CLOSED;
        }
    }

    /** Statuses allowed in each stage. */
    static final Map<Stage, Set<Status>> ALLOWED = Map.of(
            Stage.INQUIRY, EnumSet.of(Status.OPEN, Status.AWAITING_CUSTOMER),
            Stage.RETRIEVAL, EnumSet.of(Status.AWAITING_MERCHANT),
            Stage.FIRST_CHARGEBACK, EnumSet.of(Status.SUBMITTED),
            Stage.REPRESENTMENT, EnumSet.of(Status.AWAITING_CUSTOMER),
            Stage.PRE_ARBITRATION, EnumSet.of(Status.SUBMITTED),
            Stage.ARBITRATION, EnumSet.of(Status.SUBMITTED),
            Stage.CLOSED, EnumSet.of(Status.WON, Status.LOST, Status.WITHDRAWN, Status.EXPIRED));

    private static final Set<Stage> CHARGED_BACK =
            EnumSet.of(Stage.FIRST_CHARGEBACK, Stage.REPRESENTMENT, Stage.PRE_ARBITRATION, Stage.ARBITRATION);

    public static boolean consistent(State s) {
        return ALLOWED.get(s.stage()).contains(s.status());
    }

    /** The state after {@code action}, or 409 invalid_transition. */
    public static State next(State s, Action action) {
        if (s.closed()) {
            throw illegal(s, action);
        }
        State n = switch (action) {
            case REQUEST_RETRIEVAL -> s.stage() == Stage.INQUIRY ? new State(Stage.RETRIEVAL, Status.AWAITING_MERCHANT) : null;
            case SUBMIT_CHARGEBACK -> s.stage() == Stage.INQUIRY || s.stage() == Stage.RETRIEVAL
                    ? new State(Stage.FIRST_CHARGEBACK, Status.SUBMITTED) : null;
            case RECORD_REPRESENTMENT -> s.stage() == Stage.FIRST_CHARGEBACK
                    ? new State(Stage.REPRESENTMENT, Status.AWAITING_CUSTOMER) : null;
            case ESCALATE_PRE_ARBITRATION -> s.stage() == Stage.REPRESENTMENT
                    ? new State(Stage.PRE_ARBITRATION, Status.SUBMITTED) : null;
            case ESCALATE_ARBITRATION -> s.stage() == Stage.PRE_ARBITRATION
                    ? new State(Stage.ARBITRATION, Status.SUBMITTED) : null;
            case ACCEPT_REPRESENTMENT -> s.stage() == Stage.REPRESENTMENT || s.stage() == Stage.PRE_ARBITRATION
                    ? new State(Stage.CLOSED, Status.LOST) : null;
            // Money can only be recovered once a chargeback was raised.
            case CLOSE_WON, CLOSE_SPLIT -> CHARGED_BACK.contains(s.stage()) ? new State(Stage.CLOSED, Status.WON) : null;
            case CLOSE_LOST -> new State(Stage.CLOSED, Status.LOST);
            case CLOSE_WITHDRAWN -> new State(Stage.CLOSED, Status.WITHDRAWN);
            case EXPIRE -> s.status() == Status.OPEN || s.status() == Status.AWAITING_CUSTOMER
                    || s.status() == Status.AWAITING_MERCHANT ? new State(Stage.CLOSED, Status.EXPIRED) : null;
        };
        if (n == null) {
            throw illegal(s, action);
        }
        return n;
    }

    /** Close action for an outcome given by the client (EXPIRED is reserved for the deadline scan). */
    public static Action closeAction(Outcome o) {
        return switch (o) {
            case WON -> Action.CLOSE_WON;
            case LOST -> Action.CLOSE_LOST;
            case SPLIT -> Action.CLOSE_SPLIT;
            case WITHDRAWN -> Action.CLOSE_WITHDRAWN;
            case EXPIRED -> throw ApiException.unprocessable("invalid_outcome",
                    "EXPIRED is set by the deadline scan, not by close");
        };
    }

    /** Escalation target: only PRE_ARBITRATION or ARBITRATION (422 invalid_stage otherwise). */
    public static Action escalation(Stage target) {
        return switch (target) {
            case PRE_ARBITRATION -> Action.ESCALATE_PRE_ARBITRATION;
            case ARBITRATION -> Action.ESCALATE_ARBITRATION;
            default -> throw ApiException.unprocessable("invalid_stage",
                    "escalate targets PRE_ARBITRATION or ARBITRATION, not " + target);
        };
    }

    public static boolean submitted(Stage stage) {
        return CHARGED_BACK.contains(stage) || stage == Stage.CLOSED;
    }

    private static ApiException illegal(State s, Action a) {
        return ApiException.conflict("invalid_transition",
                "cannot " + a.name().toLowerCase().replace('_', ' ') + " a dispute in stage " + s.stage()
                        + " / status " + s.status());
    }
}
