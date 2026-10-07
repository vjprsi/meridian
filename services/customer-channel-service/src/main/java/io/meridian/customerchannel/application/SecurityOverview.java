package io.meridian.customerchannel.application;

import io.meridian.customerchannel.application.IdentityProviderSessions.Factor;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.customerchannel.config.ChannelProperties.Policy;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GET /v1/me/security: how the customer is signed in, which second factors they have and what each sensitive
 * operation needs. Factors are enrolled and removed at the identity provider (application-initiated actions, see
 * {@code enrolment}); the channel only reads them.
 */
@Component
public class SecurityOverview {
    /** Operations that call {@link StepUp#require} (money movement and security changes). */
    public static final List<String> STEP_UP_OPERATIONS = List.of("myPaymentsCreate", "myStandingOrdersCreate",
            "myStandingOrdersResume", "myLoansRepay", "myPayeesCreate", "myPayeesTrust", "myCardsUnfreeze",
            "myCardsReplaceLimits", "myContactsCreate", "myDevicesRevoke");

    private final ChannelProperties props;
    private final StepUp stepUp;
    private final IdentityProviderSessions idp;
    private final JsonMapper json;

    public SecurityOverview(ChannelProperties props, StepUp stepUp, IdentityProviderSessions idp, JsonMapper json) {
        this.props = props;
        this.stepUp = stepUp;
        this.idp = idp;
        this.json = json;
    }

    public JsonNode get() {
        CustomerPrincipal p = CustomerContext.require();
        ObjectNode out = json.createObjectNode();
        ObjectNode auth = out.putObject("authentication");
        auth.put("acr", p.acr());
        auth.put("level", props.level(p.acr()));
        ArrayNode amr = auth.putArray("amr");
        p.amr().forEach(amr::add);
        auth.put("auth_time", p.authTime() == null ? null : p.authTime().toString());
        auth.put("session_id", p.sessionId());

        Optional<List<Factor>> factors = p.subject() == null ? Optional.empty() : idp.factors(p.subject());
        out.put("factors_available", factors.isPresent());
        ArrayNode list = out.putArray("factors");
        factors.orElse(List.of()).forEach(f -> list.addObject().put("id", f.id()).put("type", f.type())
                .put("label", f.label()).put("created_at", f.createdAt() == null ? null : f.createdAt().toString()));
        if (factors.isPresent()) {
            out.put("has_second_factor", !factors.get().isEmpty());
        } else {
            out.putNull("has_second_factor");
        }

        ObjectNode policy = out.putObject("step_up");
        Policy def = props.stepUp().forOperation("");
        policy.put("acr", def.acr()).put("max_age_seconds", def.maxAge().toSeconds());
        ArrayNode ops = policy.putArray("operations");
        for (String op : STEP_UP_OPERATIONS) {
            Policy po = stepUp.policy(op);
            ObjectNode o = ops.addObject().put("operation", op).put("acr", po.acr())
                    .put("max_age_seconds", po.maxAge().toSeconds()).put("satisfied", stepUp.satisfied(op));
            ArrayNode a = o.putArray("amr");
            po.amr().forEach(a::add);
        }
        ObjectNode enrol = out.putObject("enrolment");
        enrol.put("authenticator_app", "CONFIGURE_TOTP");
        enrol.put("security_key", "webauthn-register");
        enrol.put("passkey", "webauthn-register-passwordless");
        enrol.put("remove", "delete_credential:{id}");
        return out;
    }
}
