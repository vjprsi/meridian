package io.meridian.customerchannel;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * customer-channel-service over HTTP (dev security mode: X-Tenant-Id, X-Customer-Id, X-Auth-Time) against
 * PostgreSQL 18, with the domain services replaced by a stateful {@link FakeBank} behind the mocked
 * {@link ServiceClient}. Two customers of one tenant, A and B, each own one object of every family; A also holds B's
 * joint account with view rights only.
 *
 * <p>Proves: every family's happy path; A can neither read nor act on any of B's objects (404, nothing written
 * downstream); bodies are rebuilt server-side (client-supplied customer, sender type, owner, status... ignored);
 * money movement and security changes need a recent authentication; non-customers are refused; the audit trail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.channels.step-up-max-age=PT5M"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerChannelFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c501";
    static final String A = "0199a000-0000-7000-8000-0000000000a1";
    static final String B = "0199a000-0000-7000-8000-0000000000b1";

    static final String DEP = "deposit-service";
    static final String PAY = "payment-service";
    static final String CARD = "card-service";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    FakeBank bank;
    final HttpClient http = HttpClient.newHttpClient();
    /** Objects of each customer: [A, B]. */
    String[] account = new String[2];
    String[] account2 = new String[2];
    String[] card = new String[2];
    String[] loan = new String[2];
    String[] wallet = new String[2];
    String[] payee = new String[2];
    String[] payment = new String[2];
    String[] thread = new String[2];
    String[] notification = new String[2];
    String[] document = new String[2];
    String[] budget = new String[2];
    String[] insight = new String[2];
    String[] points = new String[2];
    String[] standingOrder = new String[2];
    String[] contact = new String[2];
    String[] device = new String[2];
    String[] statement = new String[2];
    String joint;

    @BeforeAll
    void seed() {
        bank = new FakeBank(json);
        String[] who = {A, B};
        for (int i = 0; i < 2; i++) {
            String c = who[i];
            bank.add("customer-service", "/v1/customers", """
                    {"id":"%s","customer_number":"C%d","display_name":"Customer %d","customer_type":"INDIVIDUAL",
                     "status":"ACTIVE","preferred_language":"en"}""".formatted(c, i, i));
            contact[i] = id(bank.add("customer-service", "/v1/customers/" + c + "/contacts",
                    "{\"customer_id\":\"%s\",\"contact_type\":\"MOBILE\",\"phone\":\"+25470000000%d\",\"is_verified\":false}"
                            .formatted(c, i)));
            device[i] = id(bank.add("customer-service", "/v1/customers/" + c + "/devices",
                    "{\"customer_id\":\"%s\",\"status\":\"TRUSTED\",\"platform\":\"WEB\"}".formatted(c)));
            bank.add("customer-service", "/v1/customers/" + c + "/addresses",
                    "{\"customer_id\":\"%s\",\"address_type\":\"RESIDENTIAL\",\"country\":\"KE\"}".formatted(c));
            account[i] = id(bank.add(DEP, "/v1/deposit-accounts",
                    "{\"customer_id\":\"%s\",\"account_number\":\"10%d\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}"
                            .formatted(c, i)));
            account2[i] = id(bank.add(DEP, "/v1/deposit-accounts",
                    "{\"customer_id\":\"%s\",\"account_number\":\"20%d\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}"
                            .formatted(c, i)));
            statement[i] = id(bank.add("statement-service", "/v1/statements",
                    "{\"deposit_account_id\":\"%s\",\"status\":\"READY\",\"format\":\"PDF\"}".formatted(account[i])));
            standingOrder[i] = id(bank.add(DEP, "/v1/standing-orders",
                    "{\"deposit_account_id\":\"%s\",\"amount\":\"5.00\",\"currency\":\"KES\",\"frequency\":\"MONTHLY\","
                            .formatted(account[i]) + "\"status\":\"ACTIVE\"}"));
            payee[i] = id(bank.add(PAY, "/v1/payees", """
                    {"customer_id":"%s","name":"Payee %d","account_id_type":"ACCOUNT_NUMBER","account_number":"999%d",
                     "country":"KE","status":"ACTIVE","agent_clearing_id":"01"}""".formatted(c, i, i)));
            payment[i] = id(bank.add(PAY, "/v1/payments",
                    "{\"customer_id\":\"%s\",\"debtor_account_id\":\"%s\",\"amount\":\"1.00\",\"currency\":\"KES\",\"status\":\"SETTLED\"}"
                            .formatted(c, account[i])));
            card[i] = id(bank.add(CARD, "/v1/cards",
                    "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\",\"last4\":\"000%d\",\"form_factor\":\"VIRTUAL\"}"
                            .formatted(c, i)));
            bank.add(CARD, "/v1/card-controls",
                    "{\"card_id\":\"%s\",\"control_type\":\"CHANNEL_DENY\",\"channel\":\"ATM\",\"is_active\":true}"
                            .formatted(card[i]));
            bank.add(CARD, "/v1/card-spend-limits",
                    "{\"card_id\":\"%s\",\"period\":\"DAILY\",\"channel\":\"ANY\",\"max_amount\":\"100.00\",\"currency\":\"KES\"}"
                            .formatted(card[i]));
            loan[i] = id(bank.add("loan-service", "/v1/loans",
                    "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\",\"currency\":\"KES\"}".formatted(c)));
            wallet[i] = id(bank.add("wallet-service", "/v1/wallets",
                    "{\"customer_id\":\"%s\",\"wallet_type\":\"CUSTOMER\",\"status\":\"ACTIVE\",\"currency\":\"KES\"}"
                            .formatted(c)));
            budget[i] = id(bank.add("insights-service", "/v1/insights/budgets",
                    "{\"customer_id\":\"%s\",\"name\":\"Food\",\"amount\":\"100.00\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}"
                            .formatted(c)));
            insight[i] = id(bank.add("insights-service", "/v1/insights/cards",
                    "{\"customer_id\":\"%s\",\"delivery_status\":\"DELIVERED\"}".formatted(c)));
            points[i] = id(bank.add("rewards-service", "/v1/rewards/points-accounts",
                    "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\",\"available_points\":10}".formatted(c)));
            bank.add("rewards-service", "/v1/rewards/cashback",
                    "{\"customer_id\":\"%s\",\"status\":\"PAID\",\"amount\":\"1.00\"}".formatted(c));
            thread[i] = id(bank.add("inbox-service", "/v1/inbox/threads",
                    "{\"customer_id\":\"%s\",\"subject\":\"Hi\",\"status\":\"OPEN\"}".formatted(c)));
            bank.add("inbox-service", "/v1/inbox/messages",
                    "{\"secure_message_thread_id\":\"%s\",\"body\":\"hello\",\"sender_type\":\"CUSTOMER\",\"is_internal_note\":false}"
                            .formatted(thread[i]));
            bank.add("inbox-service", "/v1/inbox/messages",
                    "{\"secure_message_thread_id\":\"%s\",\"body\":\"staff only\",\"sender_type\":\"STAFF\",\"is_internal_note\":true}"
                            .formatted(thread[i]));
            notification[i] = id(bank.add("notification-service", "/v1/notifications",
                    "{\"recipient_type\":\"CUSTOMER\",\"recipient_id\":\"%s\",\"channel\":\"IN_APP\",\"status\":\"SENT\"}"
                            .formatted(c)));
            bank.add("notification-service", "/v1/notification-preferences",
                    "{\"recipient_type\":\"CUSTOMER\",\"recipient_id\":\"%s\",\"category\":\"MARKETING\",\"channel\":\"SMS\",\"is_enabled\":true}"
                            .formatted(c));
            document[i] = id(bank.add("document-service", "/v1/documents",
                    "{\"owner_type\":\"CUSTOMER\",\"owner_id\":\"%s\",\"document_type\":\"ID_CARD\",\"status\":\"AVAILABLE\"}"
                            .formatted(c)));
            bank.add("kyc-service", "/v1/kyc/profiles", "{\"customer_id\":\"%s\",\"status\":\"VERIFIED\"}".formatted(c));
            bank.add("kyc-service", "/v1/kyc/documents",
                    "{\"customer_id\":\"%s\",\"document_type\":\"NATIONAL_ID\",\"status\":\"ACCEPTED\"}".formatted(c));
        }
        bank.add("insights-service", "/v1/insights/categories", "{\"code\":\"FOOD\",\"name\":\"Food\",\"is_active\":true}");
        // B's joint account, A holds it with view rights only
        joint = id(bank.add(DEP, "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"account_number\":\"300\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(B)));
        bank.add(DEP, "/v1/account-holders", """
                {"deposit_account_id":"%s","customer_id":"%s","role":"JOINT","status":"ACTIVE","can_view":true,
                 "can_transact":false}""".formatted(joint, A));
    }

    @BeforeEach
    void wire() {
        bank.wire(serviceClient);
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    @Order(1)
    void readsEveryFamilyOfTheSignedInCustomer() {
        assertThat(get(A, "/v1/me/profile").path("id").asString()).isEqualTo(A);
        assertThat(ids(get(A, "/v1/me/contacts"))).containsExactly(contact[0]);
        assertThat(ids(get(A, "/v1/me/addresses"))).hasSize(1);
        assertThat(ids(get(A, "/v1/me/devices"))).containsExactly(device[0]);
        assertThat(get(A, "/v1/me/notification-preferences").path("data")).hasSize(1);

        assertThat(ids(get(A, "/v1/me/accounts"))).containsExactlyInAnyOrder(account[0], account2[0], joint)
                .doesNotContain(account[1]);
        assertThat(get(A, "/v1/me/accounts/holdings").path("data").get(0).path("can_transact").asBoolean()).isFalse();
        assertThat(get(A, "/v1/me/accounts/" + account[0]).path("account_number").asString()).isEqualTo("100");
        assertThat(get(A, "/v1/me/accounts/" + joint).path("id").asString()).isEqualTo(joint);   // joint holder
        assertThat(get(A, "/v1/me/accounts/" + account[0] + "/balance").path("currency").asString()).isEqualTo("KES");
        assertThat(get(A, "/v1/me/accounts/" + account[0] + "/transactions?from=2026-01-01T00:00:00Z").path("data")).hasSize(1);
        assertThat(ids(get(A, "/v1/me/accounts/" + account[0] + "/statements"))).containsExactly(statement[0]);
        HttpResponse<String> pdf = call(A, now(), "GET",
                "/v1/me/accounts/" + account[0] + "/statements/" + statement[0] + "/download", null);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type")).hasValue("application/pdf");
        HttpResponse<String> letter = call(A, now(), "GET", "/v1/me/accounts/" + account[0] + "/confirmation-letter", null);
        assertThat(letter.statusCode()).isEqualTo(200);
        assertThat(letter.body()).contains("Confirmation for " + account[0]);

        assertThat(ids(get(A, "/v1/me/standing-orders"))).containsExactly(standingOrder[0]);
        assertThat(ids(get(A, "/v1/me/standing-orders?account_id=" + account[0]))).containsExactly(standingOrder[0]);
        assertThat(ids(get(A, "/v1/me/payees"))).containsExactly(payee[0]);
        assertThat(ids(get(A, "/v1/me/payments"))).containsExactly(payment[0]);
        assertThat(get(A, "/v1/me/payments/" + payment[0]).path("customer_id").asString()).isEqualTo(A);
        assertThat(call(A, now(), "GET", "/v1/me/payments/" + payment[0] + "/receipt", null).statusCode()).isEqualTo(200);

        assertThat(ids(get(A, "/v1/me/cards"))).containsExactly(card[0]);
        assertThat(get(A, "/v1/me/cards/" + card[0]).path("last4").asString()).isEqualTo("0000");
        assertThat(get(A, "/v1/me/cards/" + card[0] + "/controls").path("data")).hasSize(1);
        assertThat(get(A, "/v1/me/cards/" + card[0] + "/limits").path("data")).hasSize(1);

        assertThat(ids(get(A, "/v1/me/loans"))).containsExactly(loan[0]);
        assertThat(get(A, "/v1/me/loans/" + loan[0]).path("id").asString()).isEqualTo(loan[0]);
        assertThat(get(A, "/v1/me/loans/" + loan[0] + "/schedule").path("data")).hasSize(1);
        assertThat(get(A, "/v1/me/loans/" + loan[0] + "/repayments").path("data")).hasSize(1);

        assertThat(ids(get(A, "/v1/me/wallets"))).containsExactly(wallet[0]);
        assertThat(get(A, "/v1/me/wallets/" + wallet[0]).path("id").asString()).isEqualTo(wallet[0]);
        assertThat(get(A, "/v1/me/wallets/" + wallet[0] + "/balance").path("currency").asString()).isEqualTo("KES");
        assertThat(get(A, "/v1/me/wallets/" + wallet[0] + "/transactions").path("data")).hasSize(1);

        assertThat(get(A, "/v1/me/insights/spending?from=2026-09-01&to=2026-09-30").path("customer_id").asString())
                .isEqualTo(A);
        assertThat(get(A, "/v1/me/insights/categories").path("data")).hasSize(1);
        assertThat(ids(get(A, "/v1/me/insights/cards"))).containsExactly(insight[0]);
        assertThat(ids(get(A, "/v1/me/budgets"))).containsExactly(budget[0]);
        assertThat(get(A, "/v1/me/budgets/" + budget[0] + "/current-period").path("budget_id").asString()).isEqualTo(budget[0]);
        assertThat(ids(get(A, "/v1/me/rewards/points-accounts"))).containsExactly(points[0]);
        assertThat(get(A, "/v1/me/rewards/points-accounts/" + points[0] + "/entries").path("data")).hasSize(1);
        assertThat(get(A, "/v1/me/rewards/cashback").path("data")).hasSize(1);

        assertThat(ids(get(A, "/v1/me/inbox/threads"))).containsExactly(thread[0]);
        assertThat(get(A, "/v1/me/inbox/threads/" + thread[0]).path("id").asString()).isEqualTo(thread[0]);
        JsonNode messages = get(A, "/v1/me/inbox/threads/" + thread[0] + "/messages");
        assertThat(messages.path("data")).hasSize(1);                               // the staff-only note is never shown
        assertThat(messages.path("data").get(0).path("body").asString()).isEqualTo("hello");
        assertThat(bank.calls.stream().noneMatch(c -> c.path().contains("include_internal"))).isTrue();
        assertThat(get(A, "/v1/me/announcements?app=CUSTOMER_WEB&placement=HOME_BANNER").path("data")).hasSize(1);
        assertThat(ids(get(A, "/v1/me/notifications"))).containsExactly(notification[0]);
        assertThat(get(A, "/v1/me/kyc/profile").path("customer_id").asString()).isEqualTo(A);
        assertThat(get(A, "/v1/me/kyc/documents").path("data")).hasSize(1);
        assertThat(ids(get(A, "/v1/me/documents"))).containsExactly(document[0]);
        // reads write nothing downstream
        assertThat(bank.writes()).isEmpty();
    }

    @Test
    @Order(2)
    void actsOnEveryFamilyWithServerSideBodies() {
        // cards
        assertThat(post(A, "/v1/me/cards/" + card[0] + "/freeze", null).path("status").asString()).isEqualTo("FROZEN");
        assertThat(post(A, "/v1/me/cards/" + card[0] + "/unfreeze", null).path("status").asString()).isEqualTo("ACTIVE");
        JsonNode control = send(A, "PUT", "/v1/me/cards/" + card[0] + "/channels/ECOM", "{\"blocked\":true}");
        assertThat(control.path("control_type").asString()).isEqualTo("CHANNEL_DENY");
        assertThat(control.path("card_id").asString()).isEqualTo(card[0]);
        JsonNode unblockedAtm = send(A, "PUT", "/v1/me/cards/" + card[0] + "/channels/ATM", "{\"blocked\":false}");
        assertThat(unblockedAtm.path("is_active").asBoolean()).isFalse();         // existing control updated
        assertThat(bank.lastWrite(CARD, "/v1/card-controls/").method()).isEqualTo("PATCH");
        JsonNode limit = send(A, "PUT", "/v1/me/cards/" + card[0] + "/limits",
                "{\"period\":\"DAILY\",\"channel\":\"ANY\",\"max_amount\":\"250.00\"}");
        assertThat(limit.path("max_amount").asString()).isEqualTo("250.00");
        JsonNode lost = post(A, "/v1/me/cards/" + card[0] + "/report-lost", "{\"reason\":\"STOLEN\",\"replace\":true}");
        assertThat(lost.path("status").asString()).isEqualTo("STOLEN");
        assertThat(bank.lastWrite(CARD, "/v1/cards/" + card[0] + "/replace").body().path("reason").asString())
                .isEqualTo("STOLEN");

        // payees
        JsonNode newPayee = post(A, "/v1/me/payees", """
                {"name":"Shop","account_id_type":"ACCOUNT_NUMBER","account_number":"12 34","country":"KE",
                 "customer_id":"%s","trusted":true}""".formatted(B));
        assertThat(newPayee.path("customer_id").asString()).isEqualTo(A);           // not the client's customer_id
        assertThat(newPayee.path("trusted").asBoolean()).isFalse();
        assertThat(newPayee.path("account_number").asString()).isEqualTo("1234");
        String p2 = newPayee.path("id").asString();
        assertThat(post(A, "/v1/me/payees/" + p2 + "/verify", null).path("result").asString()).isEqualTo("MATCH");
        assertThat(post(A, "/v1/me/payees/" + p2 + "/trust", null).path("trusted").asBoolean()).isTrue();
        assertThat(call(A, now(), "DELETE", "/v1/me/payees/" + p2, null).statusCode()).isEqualTo(204);
        assertThat(get(A, "/v1/me/payees").path("data")).hasSize(1);

        // payments: own transfer and to a payee; the client's customer, channel, type and currency are ignored
        JsonNode validation = post(A, "/v1/me/payments/validate", """
                {"kind":"OWN_TRANSFER","from_account_id":"%s","to_account_id":"%s","amount":"10.00"}"""
                .formatted(account[0], account2[0]));
        assertThat(validation.path("valid").asBoolean()).isTrue();
        assertThat(validation.path("echo").path("payment").path("customer_id").asString()).isEqualTo(A);
        JsonNode transfer = post(A, "/v1/me/payments", """
                {"kind":"OWN_TRANSFER","from_account_id":"%s","to_account_id":"%s","amount":"10.00","reference":"rent",
                 "customer_id":"%s","channel":"BRANCH","payment_type":"CROSS_BORDER","currency":"USD"}"""
                .formatted(account[0], account2[0], B));
        assertThat(transfer.path("customer_id").asString()).isEqualTo(A);
        assertThat(transfer.path("channel").asString()).isEqualTo("WEB");
        assertThat(transfer.path("payment_type").asString()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(transfer.path("currency").asString()).isEqualTo("KES");
        assertThat(transfer.path("debtor_name").asString()).isEqualTo("Customer 0");
        JsonNode toPayee = post(A, "/v1/me/payments", """
                {"kind":"PAYEE","from_account_id":"%s","payee_id":"%s","amount":"7.50","channel":"MOBILE",
                 "creditor_account_number":"666"}""".formatted(account[0], payee[0]));
        assertThat(toPayee.path("creditor_account_number").asString()).isEqualTo("9990");   // from the payee record
        assertThat(toPayee.path("creditor_name").asString()).isEqualTo("Payee 0");
        assertThat(toPayee.path("channel").asString()).isEqualTo("MOBILE");
        assertThat(get(A, "/v1/me/payments/" + toPayee.path("id").asString()).path("amount").asString()).isEqualTo("7.50");

        // standing orders
        JsonNode so = post(A, "/v1/me/standing-orders", """
                {"account_id":"%s","payee_id":"%s","amount":"20.00","frequency":"MONTHLY","start_date":"2026-11-01"}"""
                .formatted(account[0], payee[0]));
        assertThat(so.path("deposit_account_id").asString()).isEqualTo(account[0]);
        assertThat(so.path("currency").asString()).isEqualTo("KES");
        String soId = so.path("id").asString();
        assertThat(post(A, "/v1/me/standing-orders/" + soId + "/pause", null).path("status").asString()).isEqualTo("PAUSED");
        assertThat(post(A, "/v1/me/standing-orders/" + soId + "/resume", null).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(post(A, "/v1/me/standing-orders/" + soId + "/cancel", null).path("status").asString()).isEqualTo("CANCELLED");

        // loans
        JsonNode repay = post(A, "/v1/me/loans/" + loan[0] + "/repay",
                "{\"amount\":\"50.00\",\"source_account_id\":\"%s\",\"source\":\"RECOVERY\"}".formatted(account[0]));
        assertThat(repay.path("amount").asString()).isEqualTo("50.00");
        assertThat(bank.lastWrite("loan-service", "/v1/loans/" + loan[0] + "/repay").body().path("source").asString())
                .isEqualTo("MANUAL");

        // statements
        JsonNode st = post(A, "/v1/me/accounts/" + account[0] + "/statements",
                "{\"period_start\":\"2026-09-01\",\"period_end\":\"2026-09-30\"}");
        assertThat(st.path("deposit_account_id").asString()).isEqualTo(account[0]);
        assertThat(st.path("is_on_demand").asBoolean()).isTrue();

        // insights and budgets
        JsonNode newBudget = post(A, "/v1/me/budgets",
                "{\"name\":\"Fun\",\"amount\":\"30.00\",\"currency\":\"KES\",\"customer_id\":\"%s\"}".formatted(B));
        assertThat(newBudget.path("customer_id").asString()).isEqualTo(A);
        assertThat(post(A, "/v1/me/budgets/" + newBudget.path("id").asString() + "/archive", null).path("status")
                .asString()).isEqualTo("ARCHIVED");
        assertThat(post(A, "/v1/me/insights/cards/" + insight[0] + "/dismiss", null).path("delivery_status").asString())
                .isEqualTo("DISMISSED");

        // inbox: the customer always writes as CUSTOMER, never as staff and never an internal note
        JsonNode opened = post(A, "/v1/me/inbox/threads", """
                {"subject":"Card","initial_message":"help","related_type":"CARD","related_id":"%s",
                 "attachment_document_ids":["%s"],"customer_id":"%s","opened_by":"STAFF","work_queue_id":"%s"}"""
                .formatted(card[0], document[0], B, UUID.randomUUID()));
        assertThat(opened.path("customer_id").asString()).isEqualTo(A);
        assertThat(opened.has("opened_by")).isFalse();                    // inbox-service derives the opener
        assertThat(opened.has("work_queue_id")).isFalse();
        JsonNode msg = post(A, "/v1/me/inbox/threads/" + thread[0] + "/messages",
                "{\"body\":\"thanks\",\"sender_type\":\"STAFF\",\"is_internal_note\":true}");
        assertThat(msg.path("sender_type").asString()).isEqualTo("CUSTOMER");
        assertThat(msg.path("is_internal_note").asBoolean()).isFalse();
        post(A, "/v1/me/inbox/threads/" + thread[0] + "/read", "{\"reader_type\":\"STAFF\"}");
        assertThat(bank.lastWrite("inbox-service", "/v1/inbox/threads/" + thread[0] + "/read").body()
                .path("reader_type").asString()).isEqualTo("CUSTOMER");
        assertThat(post(A, "/v1/me/notifications/" + notification[0] + "/mark-read", null).path("status").asString())
                .isEqualTo("READ");

        // profile, contacts, devices, preferences
        assertThat(send(A, "PATCH", "/v1/me/profile", "{\"preferred_language\":\"sw\",\"status\":\"CLOSED\"}")
                .path("preferred_language").asString()).isEqualTo("sw");
        assertThat(bank.lastWrite("customer-service", "/v1/customers/" + A).body().size()).isEqualTo(1);
        JsonNode newContact = post(A, "/v1/me/contacts", "{\"contact_type\":\"EMAIL\",\"email\":\"a@x.test\",\"is_primary\":true}");
        assertThat(newContact.path("is_primary").asBoolean()).isFalse();
        post(A, "/v1/me/contacts/" + contact[0] + "/send-verification", null);
        assertThat(post(A, "/v1/me/contacts/" + contact[0] + "/verify", "{\"code\":\"123456\"}").path("is_verified")
                .asBoolean()).isTrue();
        assertThat(post(A, "/v1/me/devices/" + device[0] + "/revoke", "{}").path("status").asString()).isEqualTo("REVOKED");
        JsonNode pref = send(A, "PUT", "/v1/me/notification-preferences",
                "{\"category\":\"MARKETING\",\"channel\":\"SMS\",\"is_enabled\":false,\"recipient_id\":\"%s\"}".formatted(B));
        assertThat(pref.path("recipient_id").asString()).isEqualTo(A);
        assertThat(pref.path("is_enabled").asBoolean()).isFalse();

        // documents and KYC
        JsonNode doc = post(A, "/v1/me/documents", """
                {"document_type":"ID_CARD","file_name":"id.png","content_type":"image/png","owner_id":"%s",
                 "owner_type":"MERCHANT"}""".formatted(B));
        assertThat(doc.path("owner_id").asString()).isEqualTo(A);
        assertThat(doc.path("owner_type").asString()).isEqualTo("CUSTOMER");
        String docId = doc.path("id").asString();
        assertThat(post(A, "/v1/me/documents/" + docId + "/upload-url", null).path("url").asString()).contains(docId);
        post(A, "/v1/me/documents/" + docId + "/complete-upload", "{\"size_bytes\":10,\"checksum\":\"%s\"}"
                .formatted("a".repeat(64)));
        JsonNode kycDoc = post(A, "/v1/me/kyc/documents", """
                {"document_type":"NATIONAL_ID","document_id":"%s","customer_id":"%s","status":"ACCEPTED"}"""
                .formatted(docId, B));
        assertThat(kycDoc.path("customer_id").asString()).isEqualTo(A);
        assertThat(kycDoc.path("status").asString()).isNotEqualTo("ACCEPTED");
        assertThat(kycDoc.path("captured_channel").asString()).isEqualTo("WEB");

        // downstream Idempotency-Keys are derived per customer: the same client key never collides across customers
        String key = "same-key-" + UUID.randomUUID();
        callWithKey(A, "POST", "/v1/me/cards/" + card[0] + "/freeze", key);
        String keyA = bank.lastWrite(CARD, "/v1/cards/" + card[0] + "/freeze").key();
        callWithKey(B, "POST", "/v1/me/cards/" + card[1] + "/freeze", key);
        String keyB = bank.lastWrite(CARD, "/v1/cards/" + card[1] + "/freeze").key();
        assertThat(keyA).startsWith("me-").isNotEqualTo(keyB).doesNotContain(key);
    }

    // ------------------------------------------------------------------ object-level authorization

    @Test
    @Order(3)
    void customerCannotReadOrActOnAnotherCustomersObjects() {
        int writesBefore = bank.writes().size();
        List<String[]> denied = new ArrayList<>();
        // reads
        for (String p : List.of(
                "/v1/me/accounts/" + account[1], "/v1/me/accounts/" + account[1] + "/balance",
                "/v1/me/accounts/" + account[1] + "/transactions", "/v1/me/accounts/" + account[1] + "/statements",
                "/v1/me/accounts/" + account[1] + "/confirmation-letter",
                "/v1/me/accounts/" + account[1] + "/statements/" + statement[1] + "/download",
                "/v1/me/accounts/" + account[0] + "/statements/" + statement[1] + "/download",  // B's statement via A's account
                "/v1/me/standing-orders?account_id=" + account[1],
                "/v1/me/payments?account_id=" + account[1],
                "/v1/me/payments/" + payment[1], "/v1/me/payments/" + payment[1] + "/receipt",
                "/v1/me/cards/" + card[1], "/v1/me/cards/" + card[1] + "/controls", "/v1/me/cards/" + card[1] + "/limits",
                "/v1/me/loans/" + loan[1], "/v1/me/loans/" + loan[1] + "/schedule", "/v1/me/loans/" + loan[1] + "/repayments",
                "/v1/me/wallets/" + wallet[1], "/v1/me/wallets/" + wallet[1] + "/balance",
                "/v1/me/wallets/" + wallet[1] + "/transactions",
                "/v1/me/budgets/" + budget[1] + "/current-period",
                "/v1/me/rewards/points-accounts/" + points[1] + "/entries",
                "/v1/me/inbox/threads/" + thread[1], "/v1/me/inbox/threads/" + thread[1] + "/messages")) {
            denied.add(new String[] {"GET", p, null});
        }
        // actions on B's objects
        String fresh = null;
        for (String[] w : new String[][] {
                {"POST", "/v1/me/cards/" + card[1] + "/freeze", fresh},
                {"POST", "/v1/me/cards/" + card[1] + "/unfreeze", fresh},
                {"POST", "/v1/me/cards/" + card[1] + "/report-lost", "{\"reason\":\"LOST\",\"replace\":true}"},
                {"PUT", "/v1/me/cards/" + card[1] + "/channels/ECOM", "{\"blocked\":true}"},
                {"PUT", "/v1/me/cards/" + card[1] + "/limits", "{\"period\":\"DAILY\",\"max_amount\":\"1.00\",\"currency\":\"KES\"}"},
                {"POST", "/v1/me/payees/" + payee[1] + "/verify", fresh},
                {"POST", "/v1/me/payees/" + payee[1] + "/trust", fresh},
                {"DELETE", "/v1/me/payees/" + payee[1], fresh},
                {"POST", "/v1/me/payments", "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"1\"}"
                        .formatted(account[1], account[0])},
                {"POST", "/v1/me/payments", "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"1\"}"
                        .formatted(account[0], account[1])},
                {"POST", "/v1/me/payments", "{\"kind\":\"PAYEE\",\"from_account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"1\"}"
                        .formatted(account[0], payee[1])},
                {"POST", "/v1/me/payments/validate", "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"1\"}"
                        .formatted(account[1], account[0])},
                {"POST", "/v1/me/standing-orders", "{\"account_id\":\"%s\",\"beneficiary_account_id\":\"%s\",\"amount\":\"1\",\"frequency\":\"MONTHLY\",\"start_date\":\"2026-11-01\"}"
                        .formatted(account[1], account[0])},
                {"POST", "/v1/me/standing-orders", "{\"account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"1\",\"frequency\":\"MONTHLY\",\"start_date\":\"2026-11-01\"}"
                        .formatted(account[0], payee[1])},
                {"POST", "/v1/me/standing-orders/" + standingOrder[1] + "/pause", fresh},
                {"POST", "/v1/me/standing-orders/" + standingOrder[1] + "/resume", fresh},
                {"POST", "/v1/me/standing-orders/" + standingOrder[1] + "/cancel", fresh},
                {"POST", "/v1/me/loans/" + loan[1] + "/repay", "{\"amount\":\"1\",\"source_account_id\":\"%s\"}".formatted(account[0])},
                {"POST", "/v1/me/loans/" + loan[0] + "/repay", "{\"amount\":\"1\",\"source_account_id\":\"%s\"}".formatted(account[1])},
                {"POST", "/v1/me/accounts/" + account[1] + "/statements", "{\"period_start\":\"2026-09-01\",\"period_end\":\"2026-09-30\"}"},
                {"POST", "/v1/me/budgets/" + budget[1] + "/archive", fresh},
                {"POST", "/v1/me/insights/cards/" + insight[1] + "/dismiss", fresh},
                {"POST", "/v1/me/inbox/threads/" + thread[1] + "/messages", "{\"body\":\"x\"}"},
                {"POST", "/v1/me/inbox/threads/" + thread[1] + "/read", fresh},
                {"POST", "/v1/me/inbox/threads", "{\"subject\":\"x\",\"initial_message\":\"x\",\"related_type\":\"CARD\",\"related_id\":\"%s\"}"
                        .formatted(card[1])},
                {"POST", "/v1/me/inbox/threads", "{\"subject\":\"x\",\"initial_message\":\"x\",\"attachment_document_ids\":[\"%s\"]}"
                        .formatted(document[1])},
                {"POST", "/v1/me/notifications/" + notification[1] + "/mark-read", fresh},
                {"POST", "/v1/me/contacts/" + contact[1] + "/send-verification", fresh},
                {"POST", "/v1/me/contacts/" + contact[1] + "/verify", "{\"code\":\"1\"}"},
                {"POST", "/v1/me/devices/" + device[1] + "/revoke", "{}"},
                {"POST", "/v1/me/documents/" + document[1] + "/upload-url", fresh},
                {"POST", "/v1/me/documents/" + document[1] + "/complete-upload", "{\"size_bytes\":1,\"checksum\":\"%s\"}".formatted("b".repeat(64))},
                {"POST", "/v1/me/kyc/documents", "{\"document_type\":\"PASSPORT\",\"document_id\":\"%s\"}".formatted(document[1])}}) {
            denied.add(w);
        }
        for (String[] d : denied) {
            HttpResponse<String> r = call(A, now(), d[0], d[1], d[2]);
            assertThat(r.statusCode()).as(d[0] + " " + d[1] + " " + d[2]).isEqualTo(404);
            assertThat(json.readTree(r.body()).path("code").asString()).as(d[1]).isEqualTo("not_found");
        }
        // nothing reached a domain service as a write
        assertThat(bank.writes()).hasSize(writesBefore);
        // B's objects are untouched
        assertThat(bank.row(CARD, "/v1/cards", card[1]).path("status").asString()).isIn("ACTIVE", "FROZEN");
        assertThat(bank.row(PAY, "/v1/payees", payee[1]).path("status").asString()).isEqualTo("ACTIVE");
        // lists never contain B's rows
        assertThat(ids(get(A, "/v1/me/cards"))).doesNotContain(card[1]);
        assertThat(ids(get(A, "/v1/me/payments"))).doesNotContain(payment[1]);
        assertThat(ids(get(A, "/v1/me/inbox/threads"))).doesNotContain(thread[1]);
    }

    @Test
    @Order(4)
    void jointViewerCanSeeButNotPayFromTheAccount() {
        assertThat(get(A, "/v1/me/accounts/" + joint + "/balance").path("currency").asString()).isEqualTo("KES");
        int writes = bank.writes().size();
        HttpResponse<String> r = call(A, now(), "POST", "/v1/me/payments", """
                {"kind":"OWN_TRANSFER","from_account_id":"%s","to_account_id":"%s","amount":"1.00"}"""
                .formatted(joint, account[0]));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("account_not_transactable");
        // ...but may receive into it (own transfer target needs view rights only)
        JsonNode in = post(A, "/v1/me/payments", """
                {"kind":"OWN_TRANSFER","from_account_id":"%s","to_account_id":"%s","amount":"1.00"}"""
                .formatted(account[0], joint));
        assertThat(in.path("creditor_account_id").asString()).isEqualTo(joint);
        assertThat(bank.writes()).hasSize(writes + 1);
    }

    // ------------------------------------------------------------------ step-up

    @Test
    @Order(5)
    void moneyMovementAndSecurityChangesNeedARecentAuthentication() {
        String transfer = """
                {"kind":"OWN_TRANSFER","from_account_id":"%s","to_account_id":"%s","amount":"3.00"}"""
                .formatted(account[0], account2[0]);
        int writes = bank.writes().size();
        for (Long authTime : new Long[] {null, Instant.now().minusSeconds(600).getEpochSecond()}) {
            for (String[] op : new String[][] {
                    {"POST", "/v1/me/payments", transfer},
                    {"POST", "/v1/me/loans/" + loan[0] + "/repay", "{\"amount\":\"1\",\"source_account_id\":\"%s\"}".formatted(account[0])},
                    {"POST", "/v1/me/standing-orders", "{\"account_id\":\"%s\",\"payee_id\":\"%s\",\"amount\":\"1\",\"frequency\":\"MONTHLY\",\"start_date\":\"2026-11-01\"}"
                            .formatted(account[0], payee[0])},
                    {"POST", "/v1/me/standing-orders/" + standingOrder[0] + "/resume", null},
                    {"POST", "/v1/me/payees", "{\"name\":\"X\",\"account_id_type\":\"IBAN\",\"account_number\":\"DE1\",\"country\":\"DE\"}"},
                    {"POST", "/v1/me/payees/" + payee[0] + "/trust", null},
                    {"POST", "/v1/me/cards/" + card[1 - 1] + "/unfreeze", null},
                    {"PUT", "/v1/me/cards/" + card[0] + "/limits", "{\"period\":\"DAILY\",\"max_amount\":\"9.00\"}"},
                    {"POST", "/v1/me/contacts", "{\"contact_type\":\"MOBILE\",\"phone\":\"+254711111111\"}"},
                    {"POST", "/v1/me/devices/" + device[0] + "/revoke", "{}"}}) {
                HttpResponse<String> r = call(A, authTime, op[0], op[1], op[2]);
                assertThat(r.statusCode()).as(op[1] + " auth_time=" + authTime).isEqualTo(401);
                assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
                assertThat(r.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                        h -> assertThat(h).contains("insufficient_user_authentication").contains("max_age=300"));
            }
        }
        assertThat(bank.writes()).hasSize(writes);
        // validation needs no step-up; a fresh authentication passes
        assertThat(call(A, null, "POST", "/v1/me/payments/validate", transfer).statusCode()).isEqualTo(200);
        assertThat(call(A, Instant.now().minusSeconds(120).getEpochSecond(), "POST", "/v1/me/payments", transfer)
                .statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ callers and audit

    @Test
    @Order(6)
    void onlySignedInCustomersOfATenantGetIn() throws Exception {
        HttpResponse<String> staff = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/me/cards"))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops.anna").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(staff.statusCode()).isEqualTo(403);
        assertThat(json.readTree(staff.body()).path("code").asString()).isEqualTo("customer_identity_required");
        HttpResponse<String> noTenant = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/me/cards"))
                .header("X-Customer-Id", A).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noTenant.statusCode()).isEqualTo(401);
        HttpResponse<String> badKind = call(A, now(), "POST", "/v1/me/payments",
                "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"-5\"}"
                        .formatted(account[0], account2[0]));
        assertThat(badKind.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(7)
    void activityIsTheCustomersOwnAndDenialsAreEvents() throws Exception {
        JsonNode mine = get(A, "/v1/me/activity?limit=200");
        List<String> outcomes = new ArrayList<>();
        mine.path("data").forEach(r -> {
            assertThat(r.path("customer_id").asString()).isEqualTo(A);
            outcomes.add(r.path("outcome").asString());
        });
        assertThat(outcomes).contains("SUCCEEDED", "DENIED", "STEP_UP_REQUIRED");
        JsonNode denial = null;
        for (JsonNode r : mine.path("data")) {
            if (r.path("outcome").asString().equals("DENIED") && card[1].equals(r.path("object_id").asString())) {
                denial = r;
            }
        }
        assertThat(denial).isNotNull();
        assertThat(denial.path("object_type").asString()).isEqualTo("CARD");
        assertThat(denial.path("error_code").asString()).isEqualTo("not_owned");
        for (JsonNode r : get(B, "/v1/me/activity?limit=200").path("data")) {
            assertThat(r.path("customer_id").asString()).isEqualTo(B);
        }
        long events = TenantContext.callAs(UUID.fromString(TENANT), "test", () -> jdbc.sql(
                "select count(*) from outbox where event_type = 'channels.customer_action.denied'")
                .query(Long.class).single());
        assertThat(events).isGreaterThanOrEqualTo(30);
    }

    // ------------------------------------------------------------------ helpers

    private static String id(JsonNode n) {
        return n.path("id").asString();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("data").forEach(r -> out.add(r.path("id").asString()));
        return out;
    }

    private static Long now() {
        return Instant.now().getEpochSecond();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode get(String customer, String path) {
        return ok(call(customer, now(), "GET", path, null), "GET " + path);
    }

    private JsonNode post(String customer, String path, String body) {
        return ok(call(customer, now(), "POST", path, body), "POST " + path);
    }

    private JsonNode send(String customer, String method, String path, String body) {
        return ok(call(customer, now(), method, path, body), method + " " + path);
    }

    private JsonNode ok(HttpResponse<String> r, String what) {
        assertThat(r.statusCode()).as(what + " -> " + r.body()).isBetween(200, 299);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private HttpResponse<String> callWithKey(String customer, String method, String path, String key) {
        return send(customer, now(), method, path, null, key);
    }

    HttpResponse<String> call(String customer, Long authTime, String method, String path, String body) {
        return send(customer, authTime, method, path, body, UUID.randomUUID().toString());
    }

    private HttpResponse<String> send(String customer, Long authTime, String method, String path, String body,
                                      String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-Tenant-Id", TENANT).header("X-Customer-Id", customer)
                .header("Content-Type", "application/json");
        if (authTime != null) {
            b.header("X-Auth-Time", String.valueOf(authTime));
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", key);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
