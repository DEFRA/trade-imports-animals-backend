package uk.gov.defra.trade.imports.animals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.defra.trade.imports.animals.configuration.AppAwsConfig;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@SpringBootApplication
@EnableConfigurationProperties({AppAwsConfig.class, AppConfig.class, CdpConfig.class, OutboxConfig.class})
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
