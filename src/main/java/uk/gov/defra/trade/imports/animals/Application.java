package uk.gov.defra.trade.imports.animals;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.defra.trade.imports.animals.addressbook.AddressBookConfig;
import uk.gov.defra.trade.imports.animals.configuration.AppAwsConfig;
import uk.gov.defra.trade.imports.animals.configuration.AppConfig;
import uk.gov.defra.trade.imports.animals.configuration.CdpConfig;
import uk.gov.defra.trade.imports.animals.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@SpringBootApplication
@EnableConfigurationProperties({AddressBookConfig.class, AppAwsConfig.class, AppConfig.class,
    CdpConfig.class, NotificationTtlConfig.class, OutboxConfig.class})
@EnableScheduling
public class Application {

    static {
        // Pin the JVM default zone so every zone-less API (LocalDate.now(), new Date(), ...)
        // resolves against UTC, whatever the host timezone. Complements — never replaces — the
        // explicit UTC conversion at the persistence boundary
        // (uk.gov.defra.trade.imports.animals.configuration.UtcLocalDateConverters). Runs on
        // class load, so it covers both main() and @SpringBootTest contexts, which bootstrap
        // this class directly without calling main().
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
