package com.lynxus.platform;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LynxusApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(LynxusApiApplication.class, args);
    }
}
