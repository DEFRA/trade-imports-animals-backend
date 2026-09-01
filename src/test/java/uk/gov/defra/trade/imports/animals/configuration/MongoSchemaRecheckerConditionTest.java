package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MongoSchemaRecheckerConditionTest {

    private final MongoCollectionInitialiser initialiser = mock(MongoCollectionInitialiser.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withBean(MongoCollectionInitialiser.class, () -> initialiser)
        .withUserConfiguration(MongoSchemaRechecker.class);

    @Test
    void rechecker_shouldNotExist_whenRecheckDisabled() {
        runner.withPropertyValues("mongo.schema.recheck.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(MongoSchemaRechecker.class));
    }

    @Test
    void rechecker_shouldExist_whenRecheckEnabled() {
        runner.withPropertyValues("mongo.schema.recheck.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(MongoSchemaRechecker.class));
    }

    @Test
    void rechecker_shouldExist_whenRecheckPropertyMissing() {
        runner.run(context -> assertThat(context).hasSingleBean(MongoSchemaRechecker.class));
    }

    @Test
    void recheck_shouldDelegateToTheInitialiser() {
        new MongoSchemaRechecker(initialiser).recheck();

        verify(initialiser).recheckCollectionsAndIndexes();
    }
}
