package io.meridian.liquidity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** liquidity-service: Real-time and projected cash positions by currency and nostro, maturity ladders, Basel III LCR and NSFR calculations, intraday liquidity monitoring and liquidity stress testing. */
@SpringBootApplication
public class LiquidityApplication {
    public static void main(String[] args) {
        SpringApplication.run(LiquidityApplication.class, args);
    }
}
