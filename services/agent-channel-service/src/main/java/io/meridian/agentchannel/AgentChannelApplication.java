package io.meridian.agentchannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** agent-channel-service: Agent channel BFF behind /v1/agent for the agent outlet app (offline PWA). Every operation acts for the signed-in agent only: the agent comes from the access token (agent_id claim, */
@SpringBootApplication
public class AgentChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentChannelApplication.class, args);
    }
}
