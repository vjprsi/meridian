package io.meridian.merchantchannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** merchant-channel-service: Merchant app BFF behind /v1/merchant. Every operation acts for the signed-in merchant user only: the merchant (a merchant of merchant-service) and the user come from the access tok */
@SpringBootApplication
public class MerchantChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantChannelApplication.class, args);
    }
}
