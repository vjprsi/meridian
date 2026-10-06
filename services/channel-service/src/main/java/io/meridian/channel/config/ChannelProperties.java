package io.meridian.channel.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * channel-service settings ({@code meridian.channel.*}).
 *
 * @param gatewaySecret      HMAC secret shared with the USSD / SMS gateway (from the vault); callbacks are refused without it
 * @param airtimeBillerId    biller used by the USSD AIRTIME action when the menu does not provide one
 * @param defaultCallingCode country calling code used to normalise local numbers (0712... -> +254712...)
 * @param smsProvider        name recorded on SMS log rows
 */
@ConfigurationProperties(prefix = "meridian.channel")
public record ChannelProperties(String gatewaySecret, UUID airtimeBillerId, String defaultCallingCode, String smsProvider) {
    public ChannelProperties {
        defaultCallingCode = defaultCallingCode == null ? "254" : defaultCallingCode;
        smsProvider = smsProvider == null ? "notification-service" : smsProvider;
    }

    /** E.164 form of a subscriber number typed locally or internationally. */
    public String e164(String msisdn) {
        if (msisdn == null) {
            return null;
        }
        String m = msisdn.trim().replaceAll("[\\s-]", "");
        if (m.startsWith("+")) {
            return m;
        }
        if (m.startsWith("00")) {
            return "+" + m.substring(2);
        }
        if (m.startsWith("0")) {
            return "+" + defaultCallingCode + m.substring(1);
        }
        return m.startsWith(defaultCallingCode) ? "+" + m : "+" + defaultCallingCode + m;
    }
}
