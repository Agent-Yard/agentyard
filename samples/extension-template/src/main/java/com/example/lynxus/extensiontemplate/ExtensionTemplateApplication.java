package com.example.lynxus.extensiontemplate;

import com.example.lynxus.extensiontemplate.config.ExtensionTemplateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ExtensionTemplateProperties.class)
public class ExtensionTemplateApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExtensionTemplateApplication.class, args);
    }
}
