package com.lynxus.platform;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.lynxus")
@ConfigurationPropertiesScan
@EnableScheduling
public class LynxusApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(LynxusApiApplication.class, args);
    }
}
