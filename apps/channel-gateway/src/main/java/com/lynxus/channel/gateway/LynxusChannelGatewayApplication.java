package com.lynxus.channel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.lynxus")
@ConfigurationPropertiesScan
@EnableScheduling
public class LynxusChannelGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(LynxusChannelGatewayApplication.class, args);
    }
}
