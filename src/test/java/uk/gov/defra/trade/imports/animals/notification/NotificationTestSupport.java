package uk.gov.defra.trade.imports.animals.notification;

import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public final class NotificationTestSupport {

    private NotificationTestSupport() {
    }

    public static Optional<NotificationAggregate> findByReferenceNumber(
            MongoTemplate mongoTemplate, String referenceNumber) {
        Query query = Query.query(Criteria.where("referenceNumber").is(referenceNumber));
        return Optional.ofNullable(
            mongoTemplate.findOne(query, NotificationAggregate.class, "notification"));
    }

    public static Document storedDocument(MongoTemplate mongoTemplate, String referenceNumber) {
        return mongoTemplate.getCollection("notification")
            .find(new Document("referenceNumber", referenceNumber))
            .first();
    }

    public static void unsetStoredField(
            MongoTemplate mongoTemplate, String referenceNumber, String fieldName) {
        mongoTemplate.getCollection("notification").updateOne(
            new Document("referenceNumber", referenceNumber),
            new Document("$unset", new Document(fieldName, "")));
    }

    public static void deleteByReferenceNumber(MongoTemplate mongoTemplate, String referenceNumber) {
        Query query = Query.query(Criteria.where("referenceNumber").is(referenceNumber));
        mongoTemplate.remove(query, NotificationAggregate.class, "notification");
    }
}
