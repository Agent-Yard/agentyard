package com.lynxus.channel.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.lynxus")
@ConfigurationPropertiesScan
public class LynxusChannelGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(LynxusChannelGatewayApplication.class, args);
    }
}
