package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Proves the collections and indexes exist by the time the context is up, that the bootstrap can
 * put them back, and that it refuses to start the service without a unique index. This is the test
 * that would have caught the fault EUDPA-356 reports: before the fix, nothing created the
 * {@code outbox} collection ahead of the first transactional write to it, and nothing rebuilt the
 * indexes after a database wipe.
 *
 * <p>Method order is pinned rather than left to JUnit's default. The assertions about what startup
 * left behind have to run before the tests that deliberately drop and rebuild it, or they would be
 * reading state an earlier test in this class had restored by hand — which is not the same claim
 * at all, and would silently change meaning the next time a method here is renamed. With
 * {@code spring.data.mongodb.auto-index-creation} off, nothing but
 * {@link MongoCollectionInitialiser} creates these indexes, so observing them here is genuinely
 * evidence that the bootstrap ran at startup.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoCollectionInitialiserIT extends IntegrationBase {

    private static final String NOTIFICATION_COLLECTION = "notification";
    private static final String REFERENCE_INDEX = "referenceNumber";
    private static final String DUPLICATED_REFERENCE = "EUDPA-356-DUPLICATE";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @Autowired
    private MongoCollectionInitialiser initialiser;

    @Test
    @Order(1)
    void mappedEntities_shouldBeTheFourCollectionsThisServiceOwns() {
        assertThat(mappedCollections()).containsExactlyInAnyOrder(
            "notification", "outbox", "audit", "accompanying_documents");
    }

    @Test
    @Order(2)
    void startup_shouldCreateEveryMappedCollection() {
        assertThat(mongoTemplate.getCollectionNames()).containsAll(mappedCollections());
    }

    @Test
    @Order(3)
    void startup_shouldCreateEveryAnnotationDeclaredIndex() {
        for (MongoPersistentEntity<?> entity : mappedEntities()) {
            assertThat(indexNames(entity.getCollection()))
                .as("indexes on %s", entity.getCollection())
                .containsAll(declaredIndexNames(entity));
        }
    }

    @Test
    @Order(4)
    void startup_shouldCreateTheOutboxUniquenessIndex_soDuplicateEventsAreRejected() {
        assertThat(mongoTemplate.indexOps("outbox").getIndexInfo())
            .filteredOn(index -> "aggregate_version_uq".equals(index.getName()))
            .singleElement()
            .satisfies(index -> assertThat(index.isUnique()).isTrue());
    }

    @Test
    @Order(5)
    void startup_shouldCreateTheNotificationReferenceIndex_soDuplicateReferencesAreRejected() {
        assertThat(mongoTemplate.indexOps(NOTIFICATION_COLLECTION).getIndexInfo())
            .filteredOn(index -> REFERENCE_INDEX.equals(index.getName()))
            .singleElement()
            .satisfies(index -> assertThat(index.isUnique()).isTrue());
    }

    @Test
    @Order(6)
    void ensureCollectionsAndIndexes_shouldTolerateCollectionsThatAlreadyExist() {
        assertThatNoException().isThrownBy(initialiser::ensureCollectionsAndIndexes);
        assertThatNoException().isThrownBy(initialiser::ensureCollectionsAndIndexes);
    }

    /**
     * Exactly what a database reseed leaves behind under a running service. Drops only the mapped
     * collections rather than the whole database: every integration test shares this container, so
     * dropping the database would reach well beyond the thing under test.
     */
    @Test
    @Order(7)
    void ensureCollectionsAndIndexes_shouldRestoreEverything_whenTheCollectionsHaveBeenDropped() {
        mappedCollections().forEach(mongoTemplate::dropCollection);
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

    /**
     * The scheduled recovery is the whole reason nothing outside the service has to restart it
     * after a reseed. This drives the same entry point the timer calls, so the wiring cannot be
     * dropped without a red test.
     */
    @Test
    @Order(9)
    void recheckCollectionsAndIndexes_shouldRestoreEverything_afterTheDatabaseIsWipedUnderneath() {
        mappedCollections().forEach(mongoTemplate::dropCollection);
        assertThat(mongoTemplate.getCollectionNames()).doesNotContainAnyElementsOf(
            mappedCollections());

        initialiser.recheckCollectionsAndIndexes();

        assertThat(mongoTemplate.getCollectionNames()).containsAll(mappedCollections());
        assertThat(indexNames(NOTIFICATION_COLLECTION))
            .as("the unique reference index is rebuilt without a restart")
            .contains(REFERENCE_INDEX);
    }

    /**
     * A recheck must not take the service down. It runs on a timer against a database that may be
     * mid-wipe, and a failure there is not a reason to stop serving traffic — unlike at startup,
     * where the same failure is fatal.
     */
    @Test
    @Order(10)
    void recheckCollectionsAndIndexes_shouldNotThrow_whenTheSchemaCannotBeBuilt() {
        givenDuplicateReferenceNumbersAndNoIndexToRejectThem();
        try {
            assertThatNoException().isThrownBy(initialiser::recheckCollectionsAndIndexes);
        } finally {
            removeTheDuplicates();
            initialiser.ensureCollectionsAndIndexes();
        }
    }

    /**
     * A unique index that will not build has to stop the service starting. The application treats
     * these indexes as the enforcement mechanism — {@code createNotification} and
     * {@code OutboxService.appendEvent} both write and catch the duplicate-key error — so starting
     * without one turns a rejected duplicate into a persisted one, silently.
     */
    @Test
    @Order(8)
    void ensureCollectionsAndIndexes_shouldFail_whenAUniqueIndexCannotBuild() {
        givenDuplicateReferenceNumbersAndNoIndexToRejectThem();
        try {
            assertThatThrownBy(initialiser::ensureCollectionsAndIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique index")
                .hasMessageContaining(REFERENCE_INDEX)
                .hasMessageContaining(NOTIFICATION_COLLECTION);
        } finally {
            removeTheDuplicates();
        }

        assertThatNoException().isThrownBy(initialiser::ensureCollectionsAndIndexes);
        assertThat(indexNames(NOTIFICATION_COLLECTION)).contains(REFERENCE_INDEX);
    }

    private void givenDuplicateReferenceNumbersAndNoIndexToRejectThem() {
        mongoTemplate.indexOps(NOTIFICATION_COLLECTION).dropIndex(REFERENCE_INDEX);
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION).insertMany(List.of(
            new org.bson.Document(REFERENCE_INDEX, DUPLICATED_REFERENCE),
            new org.bson.Document(REFERENCE_INDEX, DUPLICATED_REFERENCE)));
    }

    private void removeTheDuplicates() {
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION)
            .deleteMany(new org.bson.Document(REFERENCE_INDEX, DUPLICATED_REFERENCE));
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
