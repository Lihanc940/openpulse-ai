package io.github.lihanc940.openpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OpenPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenPulseApplication.class, args);
    }
}
