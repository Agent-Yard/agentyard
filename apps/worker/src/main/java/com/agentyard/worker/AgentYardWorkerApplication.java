package com.agentyard.worker;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.agentyard")
@ConfigurationPropertiesScan
public class AgentYardWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentYardWorkerApplication.class, args);
    }
}
