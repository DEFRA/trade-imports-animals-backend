package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import uk.gov.defra.trade.imports.animals.configuration.MongoCollectionInitialiser;

/**
 * Proves the collections and indexes exist by the time the context is up, and that the bootstrap
 * can put them back. This is the test that would have caught the fault EUDPA-356 reports: before
 * the fix, nothing created the {@code outbox} collection ahead of the first transactional write to
 * it, and nothing rebuilt the indexes after a database wipe.
 */
class MongoCollectionInitialiserIT extends IntegrationBase {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @Autowired
    private MongoCollectionInitialiser initialiser;

    @Test
    void mappedEntities_shouldBeTheFourCollectionsThisServiceOwns() {
        assertThat(mappedCollections()).containsExactlyInAnyOrder(
            "notification", "outbox", "audit", "accompanying_documents");
    }

    @Test
    void startup_shouldCreateEveryMappedCollection() {
        assertThat(mongoTemplate.getCollectionNames()).containsAll(mappedCollections());
    }

    @Test
    void startup_shouldCreateEveryAnnotationDeclaredIndex() {
        for (MongoPersistentEntity<?> entity : mappedEntities()) {
            assertThat(indexNames(entity.getCollection()))
                .as("indexes on %s", entity.getCollection())
                .containsAll(declaredIndexNames(entity));
        }
    }

    @Test
    void startup_shouldCreateTheOutboxUniquenessIndex_soDuplicateEventsAreRejected() {
        assertThat(mongoTemplate.indexOps("outbox").getIndexInfo())
            .filteredOn(index -> "aggregate_version_uq".equals(index.getName()))
            .singleElement()
            .satisfies(index -> assertThat(index.isUnique()).isTrue());
    }

    @Test
    void startup_shouldCreateTheNotificationReferenceIndex_soDuplicateReferencesAreRejected() {
        assertThat(mongoTemplate.indexOps("notification").getIndexInfo())
            .filteredOn(index -> "referenceNumber".equals(index.getName()))
            .singleElement()
            .satisfies(index -> assertThat(index.isUnique()).isTrue());
    }

    @Test
    void ensureCollectionsAndIndexes_shouldRestoreEverything_whenTheDatabaseHasBeenDropped() {
        mongoTemplate.getDb().drop();
        assertThat(mongoTemplate.getCollectionNames()).doesNotContainAnyElementsOf(
            mappedCollections());

        initialiser.ensureCollectionsAndIndexes();

        assertThat(mongoTemplate.getCollectionNames()).containsAll(mappedCollections());
        for (MongoPersistentEntity<?> entity : mappedEntities()) {
            assertThat(indexNames(entity.getCollection()))
                .as("indexes restored on %s", entity.getCollection())
                .containsAll(declaredIndexNames(entity));
        }
    }

    @Test
    void ensureCollectionsAndIndexes_shouldTolerateCollectionsThatAlreadyExist() {
        assertThatNoException().isThrownBy(initialiser::ensureCollectionsAndIndexes);
        assertThatNoException().isThrownBy(initialiser::ensureCollectionsAndIndexes);
    }

    private List<String> indexNames(String collection) {
        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
            .map(IndexInfo::getName)
            .toList();
    }

    private List<String> declaredIndexNames(MongoPersistentEntity<?> entity) {
        IndexResolver resolver = IndexResolver.create(mappingContext);
        List<String> names = new ArrayList<>();
        for (IndexDefinition definition : resolver.resolveIndexFor(entity.getTypeInformation())) {
            names.add(indexNameOf(definition));
        }
        return names;
    }

    /**
     * The name an index will carry once created — the declared name where the annotation gives
     * one, otherwise the name MongoDB derives from the keys ({@code field_direction}, joined).
     */
    private static String indexNameOf(IndexDefinition definition) {
        String declared = definition.getIndexOptions().getString("name");
        if (declared != null) {
            return declared;
        }
        return definition.getIndexKeys().entrySet().stream()
            .map(key -> key.getKey() + "_" + key.getValue())
            .collect(Collectors.joining("_"));
    }

    private List<String> mappedCollections() {
        return mappedEntities().stream().map(MongoPersistentEntity::getCollection).toList();
    }

    private List<MongoPersistentEntity<?>> mappedEntities() {
        return mappingContext.getPersistentEntities().stream()
            .filter(entity -> entity.isAnnotationPresent(Document.class))
            .<MongoPersistentEntity<?>>map(entity -> entity)
            .toList();
    }
}
