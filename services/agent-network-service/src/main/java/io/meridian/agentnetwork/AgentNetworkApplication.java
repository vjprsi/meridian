package io.meridian.agentnetwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** agent-network-service: Agent hierarchy (super-agent, agent, sub-agent), outlets with geo-location, territories, float management and rebalancing, tills and cash counts, agent devices / POS, agent KYB doc */
@SpringBootApplication
public class AgentNetworkApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentNetworkApplication.class, args);
    }
}
