package com.lynxus.worker;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LynxusWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LynxusWorkerApplication.class, args);
    }
}
