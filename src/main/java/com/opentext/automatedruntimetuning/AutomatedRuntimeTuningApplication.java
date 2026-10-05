package com.opentext.automatedruntimetuning;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(TuningProperties.class)
public class AutomatedRuntimeTuningApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutomatedRuntimeTuningApplication.class, args);
    }
}