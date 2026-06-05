package com.agentyard.platform;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agentyard")
@ConfigurationPropertiesScan
@EnableScheduling
public class AgentYardApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentYardApiApplication.class, args);
    }
}
