package io.meridian.brebsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bre-B simulator for the sandbox and CI. Serves the participant API that routing-service's Bre-B adapter speaks,
 * once per access node ({@code /nodes/{node}/...}), so a rail with several adapters (one per connectivity node)
 * exercises smart routing and failover against real HTTP:
 * <ul>
 *   <li>DICE key directory: {@code /nodes/{node}/dice/v1/keys} - register, resolve (masked name), delete, port</li>
 *   <li>SPBVI transfers: {@code /nodes/{node}/spbvi/v1/transfers} - pacs.008 in, synchronous pacs.002 out,
 *       idempotent on end_to_end_id; {@code GET .../transfers/{e2e}} is the pacs.028 status query</li>
 *   <li>Request to pay: {@code /nodes/{node}/spbvi/v1/rtp}</li>
 *   <li>Test control: {@code /sim/nodes/{node}/down|up}, {@code /sim/reset}</li>
 * </ul>
 * Deterministic outcomes by the amount's cents: .13 rejected AM02 (amount not allowed), .66 rejected AC04 (closed
 * account), .77 answered only after the client timeout (accepted later: status query returns ACCP).
 */
@SpringBootApplication
public class BrebSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BrebSimulatorApplication.class, args);
    }
}
