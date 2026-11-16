package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

final class ExpireAtIndexes {

    private ExpireAtIndexes() {}

    static List<Document> expireAtIndexes(MongoTemplate mongoTemplate, Class<?> entityClass) {
        return mongoTemplate.getCollection(mongoTemplate.getCollectionName(entityClass))
            .listIndexes().into(new ArrayList<>()).stream()
            .filter(index -> index.get("key", Document.class).containsKey("expireAt"))
            .toList();
    }

    static void assertSingleTtlIndexNamed(
        MongoTemplate mongoTemplate, Class<?> entityClass, String indexName) {
        assertThat(expireAtIndexes(mongoTemplate, entityClass)).singleElement().satisfies(index -> {
            assertThat(index.getString("name")).isEqualTo(indexName);
            assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
        });
    }
}
