package uk.gov.defra.trade.imports.animals.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Carries the timer for {@link MongoCollectionInitialiser#recheckCollectionsAndIndexes()} on its
 * own bean so the schedule can be switched off without losing the startup bootstrap, which the
 * service cannot run without.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mongo.schema.recheck", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class MongoSchemaRechecker {

    private final MongoCollectionInitialiser initialiser;

    @Scheduled(fixedDelayString = "${mongo.schema.recheck.interval-ms:60000}")
    public void recheck() {
        initialiser.recheckCollectionsAndIndexes();
    }
}
