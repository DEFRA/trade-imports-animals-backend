package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoDatabase;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoSimpleTypes;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;

/**
 * Covers how the bootstrap reacts to a failing {@code create}, which the integration tests cannot
 * drive: they can only produce the success and already-exists cases against a real server.
 */
class MongoCollectionInitialiserTest {

    private static final int NAMESPACE_EXISTS = 48;
    private static final int UNAUTHORIZED = 13;

    private MongoTemplate mongoTemplate;
    private MongoDatabase database;
    private MongoMappingContext mappingContext;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        database = mock(MongoDatabase.class);
        when(mongoTemplate.getDb()).thenReturn(database);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOperations);

        mappingContext = new MongoMappingContext();
        // Without this the context treats Instant as an entity and tries to reflect into its
        // private constructor, which the module system refuses from an unnamed test module.
        mappingContext.setSimpleTypeHolder(
            new SimpleTypeHolder(Set.of(Instant.class), MongoSimpleTypes.HOLDER));
        mappingContext.setInitialEntitySet(Set.of(OutboxEvent.class));
        mappingContext.afterPropertiesSet();
    }

    @Test
    void ensureCollectionsAndIndexes_shouldContinue_whenTheCollectionAlreadyExists() {
        givenCreateFailsWith(NAMESPACE_EXISTS, "NamespaceExists",
            "Collection already exists. NS: trade-imports-animals-backend.outbox");

        assertThatNoException().isThrownBy(initialiser()::ensureCollectionsAndIndexes);
    }

    @Test
    void ensureCollectionsAndIndexes_shouldPropagate_whenCreateFailsForAnyOtherReason() {
        givenCreateFailsWith(UNAUTHORIZED, "Unauthorized",
            "not authorized on trade-imports-animals-backend to execute command { create: ... }");

        assertThatThrownBy(initialiser()::ensureCollectionsAndIndexes)
            .isInstanceOf(MongoCommandException.class)
            .hasMessageContaining("not authorized");
    }

    private MongoCollectionInitialiser initialiser() {
        return new MongoCollectionInitialiser(mongoTemplate, mappingContext);
    }

    private void givenCreateFailsWith(int code, String codeName, String errmsg) {
        doThrow(commandFailure(code, codeName, errmsg))
            .when(database).createCollection(anyString());
    }

    private static MongoCommandException commandFailure(int code, String codeName, String errmsg) {
        BsonDocument response = new BsonDocument()
            .append("ok", new BsonDouble(0.0))
            .append("code", new BsonInt32(code))
            .append("codeName", new BsonString(codeName))
            .append("errmsg", new BsonString(errmsg));
        return new MongoCommandException(response, new ServerAddress());
    }
}
