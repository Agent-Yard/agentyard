package com.agentyard.channel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agentyard")
@ConfigurationPropertiesScan
@EnableScheduling
public class AgentYardChannelGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentYardChannelGatewayApplication.class, args);
    }
}
