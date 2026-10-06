package io.meridian.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** merchant-service: Merchant onboarding and acceptance: stores, terminals, EMVCo static/dynamic QR codes and payment links; merchant discount rate fee plans; periodic settlement to wallet or bank; ref */
@SpringBootApplication
public class MerchantApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantApplication.class, args);
    }
}
