package com.openpms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** openPMS: work packages, planning views and administration for an internal team (replica/architecture.md). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OpenPmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenPmsApplication.class, args);
    }
}
