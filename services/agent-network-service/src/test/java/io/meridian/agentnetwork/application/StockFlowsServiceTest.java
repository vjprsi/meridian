package io.meridian.agentnetwork.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.agentnetwork.config.AgentNetworkProperties;
import io.meridian.agentnetwork.config.AgentNetworkProperties.Gl;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-FLOAT-1 posting-direction and flow tests (no Spring / no DB): the finance-critical DEBIT/CREDIT legs, ALLOCATE
 * source resolution + network boundary, DEALLOCATE targeting trust, and idempotent 5xx retry.
 */
class StockFlowsServiceTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("0199c000-0000-7000-8000-000000000010");

    static final UUID CAPITAL = UUID.fromString("0199c000-0000-7000-8000-0000000000c1");
    static final UUID BANK_CLEARING = UUID.fromString("0199c000-0000-7000-8000-000000001150");
    static final UUID TRUST = UUID.fromString("0199c000-0000-7000-8000-000000002700");
    static final UUID TREASURY = CAPITAL;

    Rows rows;
    Downstream downstream;
    Events events;
    Approvals approvals;
    TransactionTemplate tx;
    AgentNetworkProperties props;

    @BeforeEach
    void setup() {
        rows = mock(Rows.class);
        downstream = mock(Downstream.class);
        events = mock(Events.class);
        approvals = mock(Approvals.class);
        tx = mock(TransactionTemplate.class);
        props = new AgentNetworkProperties(TREASURY, CAPITAL, null,
                new Gl(TRUST, null, null, BANK_CLEARING, null, null), null, null);
        lenient().when(rows.object()).thenAnswer(i -> JSON.createObjectNode());
        lenient().when(tx.execute(any())).thenAnswer(i -> i.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
        TenantContext.set(TENANT, "checker");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ObjectNode node() {
        return JSON.createObjectNode();
    }

    // ------------------------------------------------------------------ stock operation posting directions

    @Test
    void stockCreationCreditsCapitalDebitsBankClearing() {
        StockOperationService svc = new StockOperationService(rows, downstream, events, props, tx, approvals);
        UUID id = UUID.randomUUID();
        ObjectNode op = node();
        op.put("id", id.toString());
        op.put("operation_type", "STOCK_CREATION");
        op.put("amount", "500.00");
        op.put("currency", "USD");
        op.put("status", "REQUESTED");
        op.put("created_by", "maker");
        when(rows.get(eq("stock_operation"), eq(id))).thenReturn(op);
        when(rows.lock(eq("stock_operation"), eq(id))).thenReturn(op);
        when(rows.update(eq("stock_operation"), eq(id), any())).thenReturn(op);
        when(downstream.walletToGl(anyString(), anyString(), any(), anyBoolean(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(txnWithId());

        svc.approve(id, "ok");

        ArgumentCaptor<Boolean> payer = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<UUID> wallet = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> gl = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(downstream).walletToGl(anyString(), type.capture(), wallet.capture(), payer.capture(), gl.capture(),
                any(), eq("USD"), any(), anyString());
        assertThat(type.getValue()).isEqualTo("BANK_TO_WALLET");
        assertThat(wallet.getValue()).isEqualTo(CAPITAL);     // capital wallet is credited (payee)
        assertThat(payer.getValue()).isFalse();
        assertThat(gl.getValue()).isEqualTo(BANK_CLEARING);   // 1150 is debited (counterparty)
        verify(events).publish(eq("wallets.stock_operation.completed"), eq("stock_operation"), any());
    }

    @Test
    void reimbursementMovesEntityToCapitalThenCapitalToBankClearing() {
        StockOperationService svc = new StockOperationService(rows, downstream, events, props, tx, approvals);
        UUID id = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        ObjectNode op = node();
        op.put("id", id.toString());
        op.put("operation_type", "REIMBURSEMENT");
        op.put("entity_wallet_id", entity.toString());
        op.put("amount", "200.00");
        op.put("currency", "USD");
        op.put("status", "REQUESTED");
        op.put("created_by", "maker");
        when(rows.get(eq("stock_operation"), eq(id))).thenReturn(op);
        when(rows.lock(eq("stock_operation"), eq(id))).thenReturn(op);
        when(rows.update(eq("stock_operation"), eq(id), any())).thenReturn(op);
        when(downstream.floatTransfer(anyString(), eq(entity), eq(CAPITAL), any(), eq("USD"), any(), anyString()))
                .thenReturn(txnWithId());
        when(downstream.walletToGl(anyString(), eq("WALLET_TO_BANK"), eq(CAPITAL), eq(true), eq(BANK_CLEARING), any(),
                eq("USD"), any(), anyString())).thenReturn(txnWithId());

        svc.approve(id, null);

        verify(downstream).floatTransfer(anyString(), eq(entity), eq(CAPITAL), any(), eq("USD"), any(), anyString());
        verify(downstream).walletToGl(anyString(), eq("WALLET_TO_BANK"), eq(CAPITAL), eq(true), eq(BANK_CLEARING), any(),
                eq("USD"), any(), anyString());
    }

    @Test
    void unknownWalletOutcomeKeepsOperationRequestedForRetry() {
        StockOperationService svc = new StockOperationService(rows, downstream, events, props, tx, approvals);
        UUID id = UUID.randomUUID();
        ObjectNode op = node();
        op.put("id", id.toString());
        op.put("operation_type", "STOCK_CREATION");
        op.put("amount", "500.00");
        op.put("currency", "USD");
        op.put("status", "REQUESTED");
        op.put("created_by", "maker");
        when(rows.get(eq("stock_operation"), eq(id))).thenReturn(op);
        when(downstream.walletToGl(anyString(), anyString(), any(), anyBoolean(), any(), any(), anyString(), any(), anyString()))
                .thenThrow(new ApiException(503, "upstream_unavailable", "wallet-service down"));

        assertThatThrownBy(() -> svc.approve(id, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(503);
        verify(rows, never()).update(eq("stock_operation"), eq(id), any()); // not marked FAILED on a 5xx
    }

    @Test
    void fourEyesBlocksTheMaker() {
        StockOperationService svc = new StockOperationService(rows, downstream, events, props, tx, approvals);
        UUID id = UUID.randomUUID();
        ObjectNode op = node();
        op.put("id", id.toString());
        op.put("operation_type", "STOCK_CREATION");
        op.put("amount", "500.00");
        op.put("currency", "USD");
        op.put("status", "REQUESTED");
        op.put("created_by", "checker"); // same as the approving principal
        when(rows.get(eq("stock_operation"), eq(id))).thenReturn(op);
        assertThatThrownBy(() -> svc.approve(id, null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("four_eyes_violation");
    }

    // ------------------------------------------------------------------ ALLOCATE / DEALLOCATE

    @Test
    void allocateO2cSourcesTheCapitalWallet() {
        FloatService svc = floatService();
        UUID agentId = UUID.randomUUID();
        UUID floatWallet = UUID.randomUUID();
        ObjectNode cols = node();
        cols.put("request_type", "ALLOCATE");
        cols.put("requesting_agent_id", agentId.toString());
        cols.put("target_wallet_id", floatWallet.toString());
        cols.put("amount", "100.00");
        cols.put("currency", "USD");
        when(rows.columns(eq("agent_float_request"), any())).thenReturn(cols);
        when(rows.find(eq("agent"), eq(agentId))).thenReturn(Optional.of(agent(agentId, floatWallet, null)));
        when(rows.insert(eq("agent_float_request"), any())).thenAnswer(i -> i.getArgument(1));

        ObjectNode row = svc.createRequest(node());
        assertThat(Rows.str(row, "source_wallet_id")).isEqualTo(CAPITAL.toString());
        assertThat(Rows.str(row, "funding_method")).isEqualTo("CAPITAL");
    }

    @Test
    void allocateFromAnotherNetworkIsRejected() {
        FloatService svc = floatService();
        UUID agentId = UUID.randomUUID();
        UUID floatWallet = UUID.randomUUID();
        UUID sourceAgentId = UUID.randomUUID();
        ObjectNode cols = node();
        cols.put("request_type", "ALLOCATE");
        cols.put("requesting_agent_id", agentId.toString());
        cols.put("source_agent_id", sourceAgentId.toString());
        cols.put("target_wallet_id", floatWallet.toString());
        cols.put("amount", "100.00");
        cols.put("currency", "USD");
        when(rows.columns(eq("agent_float_request"), any())).thenReturn(cols);
        when(rows.find(eq("agent"), eq(agentId))).thenReturn(Optional.of(agent(agentId, floatWallet, UUID.randomUUID())));
        ObjectNode source = agent(sourceAgentId, UUID.randomUUID(), UUID.randomUUID()); // different super-agent root
        when(rows.find(eq("agent"), eq(sourceAgentId))).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> svc.createRequest(node()))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("different_hierarchy");
    }

    @Test
    void deallocateTargetsTheTrustAccount() {
        FloatService svc = floatService();
        UUID agentId = UUID.randomUUID();
        UUID floatWallet = UUID.randomUUID();
        ObjectNode cols = node();
        cols.put("request_type", "DEALLOCATE");
        cols.put("requesting_agent_id", agentId.toString());
        cols.put("amount", "40.00");
        cols.put("currency", "USD");
        when(rows.columns(eq("agent_float_request"), any())).thenReturn(cols);
        when(rows.find(eq("agent"), eq(agentId))).thenReturn(Optional.of(agent(agentId, floatWallet, null)));
        when(rows.insert(eq("agent_float_request"), any())).thenAnswer(i -> i.getArgument(1));

        ObjectNode row = svc.createRequest(node());
        assertThat(Rows.str(row, "target_wallet_id")).isEqualTo(TRUST.toString());
        assertThat(Rows.str(row, "source_wallet_id")).isEqualTo(floatWallet.toString());
    }

    @Test
    void deallocateApprovePostsFloatToTrustAsWalletToGl() {
        FloatService svc = floatService();
        UUID id = UUID.randomUUID();
        UUID floatWallet = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ObjectNode r = node();
        r.put("id", id.toString());
        r.put("request_type", "DEALLOCATE");
        r.put("status", "REQUESTED");
        r.put("source_wallet_id", floatWallet.toString());
        r.put("target_wallet_id", TRUST.toString());
        r.put("requesting_agent_id", agentId.toString());
        r.put("amount", "40.00");
        r.put("currency", "USD");
        r.put("created_by", "maker");
        when(rows.get(eq("agent_float_request"), eq(id))).thenReturn(r);
        when(rows.lock(eq("agent_float_request"), eq(id))).thenReturn(r);
        when(rows.update(eq("agent_float_request"), eq(id), any())).thenReturn(r);
        when(downstream.walletToGl(anyString(), eq("ADJUSTMENT"), eq(floatWallet), eq(true), eq(TRUST), any(), eq("USD"),
                eq(agentId), anyString())).thenReturn(txnWithId());

        svc.approve(id, "ok");

        verify(downstream).walletToGl(anyString(), eq("ADJUSTMENT"), eq(floatWallet), eq(true), eq(TRUST), any(),
                eq("USD"), eq(agentId), anyString());
        verify(downstream, never()).floatTransfer(anyString(), any(), any(), any(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    private FloatService floatService() {
        return new FloatService(rows, downstream, events, props, tx, approvals);
    }

    private ObjectNode agent(UUID id, UUID floatWallet, UUID superAgentId) {
        ObjectNode a = node();
        a.put("id", id.toString());
        a.put("status", "ACTIVE");
        a.put("currency", "USD");
        a.put("float_wallet_id", floatWallet.toString());
        if (superAgentId != null) {
            a.put("super_agent_id", superAgentId.toString());
        }
        return a;
    }

    private ObjectNode txnWithId() {
        ObjectNode t = node();
        t.put("id", UUID.randomUUID().toString());
        t.put("status", "COMPLETED");
        return t;
    }
}
