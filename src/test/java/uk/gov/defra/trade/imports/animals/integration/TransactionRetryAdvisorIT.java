package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictAtCommit;
import static uk.gov.defra.trade.imports.animals.utils.TransientMongoFailure.writeConflictOnOutboxCreation;

import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventType;
import uk.gov.defra.trade.imports.animals.outbox.OutboxService;

/**
 * Proves the retry advisor is actually applied to {@code @Transactional} beans in the real
 * application context, and applied <em>outside</em> the transaction boundary.
 */
@Import(TransactionRetryAdvisorIT.FlakyServiceConfiguration.class)
class TransactionRetryAdvisorIT extends IntegrationBase {

    private static final String COLLECTION = "transaction_retry_probe";
    private static final String NOTIFICATION_ENDPOINT = "/notifications";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private FlakyTransactionalService flakyTransactionalService;

    @Autowired
    private CommitFailingTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        if (!mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.createCollection(COLLECTION);
        }
        mongoTemplate.remove(new Query(), COLLECTION);
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        flakyTransactionalService.reset();
        transactionManager.failNextCommits(0);
    }

    @Test
    void transactionalMethod_shouldSucceed_whenTheFirstAttemptFailsTransiently() {
        flakyTransactionalService.failNextAttempts(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
    }

    @Test
    void transactionalMethod_shouldCommitOnlyTheSuccessfulAttempt_whenAnEarlierOneFailed() {
        flakyTransactionalService.failNextAttempts(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .singleElement()
            .satisfies(stored -> assertThat(stored.getInteger("attempt")).isEqualTo(2));
    }

    @Test
    void transactionalMethod_shouldPropagate_whenEveryAttemptFailsTransiently() {
        flakyTransactionalService.failNextAttempts(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flakyTransactionalService.recordAttempt("probe"))
            .isInstanceOf(TransactionSystemException.class);

        assertThat(flakyTransactionalService.attempts()).isEqualTo(3);
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION)).isEmpty();
    }

    @Test
    void transactionalMethod_shouldSucceed_whenTheCommitItselfFailsTransiently() {
        transactionManager.failNextCommits(1);

        flakyTransactionalService.recordAttempt("probe");

        assertThat(flakyTransactionalService.attempts()).isEqualTo(2);
        assertThat(mongoTemplate.findAll(Document.class, COLLECTION))
            .singleElement()
            .satisfies(stored -> assertThat(stored.getInteger("attempt")).isEqualTo(2));
    }

    @Test
    void put_shouldSucceedAndWriteOneOutboxEvent_whenTheCommitFailsTransientlyOnce() {
        NotificationAggregate draft = createDraft();
        String referenceNumber = draft.getReferenceNumber();
        NotificationDto edit = NotificationDto.builder()
            .referenceNumber(referenceNumber)
            .origin(new Origin("GB", "no", "EDITED"))
            .commodity(Commodity.builder().name("Live cattle").build())
            .concurrencyToken(draft.getConcurrencyToken())
            .build();
        transactionManager.failNextCommits(1);

        webClient("NoAuth")
            .put().uri(NOTIFICATION_ENDPOINT + "/{ref}", referenceNumber)
            .bodyValue(SaveNotificationDto.of(edit))
            .exchange()
            .expectStatus().isOk();

        assertThat(outboxEventRepository.findAll())
            .extracting(OutboxEvent::getAggregateId, OutboxEvent::getEventType)
            .containsExactlyInAnyOrder(
                tuple(OutboxService.buildAggregateId(referenceNumber),
                    OutboxEventType.NOTIFICATION_CREATED.value()),
                tuple(OutboxService.buildAggregateId(referenceNumber),
                    OutboxEventType.NOTIFICATION_EDITED.value()));
    }

    private NotificationAggregate createDraft() {
        Origin origin = new Origin();
        origin.setCountryCode("GB");
        NotificationDto dto = NotificationDto.builder()
            .origin(origin)
            .commodity(Commodity.builder().name("Live cattle").build())
            .build();

        return webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(SaveNotificationDto.of(dto))
            .exchange()
            .expectStatus().isOk()
            .expectBody(NotificationAggregate.class)
            .returnResult().getResponseBody();
    }

    @TestConfiguration
    static class FlakyServiceConfiguration {

        @Bean
        FlakyTransactionalService flakyTransactionalService(MongoTemplate mongoTemplate) {
            return new FlakyTransactionalService(mongoTemplate);
        }

        @Bean
        @Primary
        CommitFailingTransactionManager commitFailingTransactionManager(
            MongoDatabaseFactory databaseFactory) {
            return new CommitFailingTransactionManager(databaseFactory);
        }
    }

    static class CommitFailingTransactionManager extends MongoTransactionManager {

        private final AtomicInteger commitFailuresRemaining = new AtomicInteger();

        CommitFailingTransactionManager(MongoDatabaseFactory databaseFactory) {
            super(databaseFactory);
        }

        void failNextCommits(int failures) {
            commitFailuresRemaining.set(failures);
        }

        @Override
        protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
            if (commitFailuresRemaining.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw writeConflictOnOutboxCreation();
            }
            super.doCommit(transactionObject);
        }
    }

    static class FlakyTransactionalService {

        private final MongoTemplate mongoTemplate;
        private int attempts;
        private int failuresRemaining;

        FlakyTransactionalService(MongoTemplate mongoTemplate) {
            this.mongoTemplate = mongoTemplate;
        }

        void reset() {
            attempts = 0;
            failuresRemaining = 0;
        }

        void failNextAttempts(int failures) {
            failuresRemaining = failures;
        }

        int attempts() {
            return attempts;
        }

        @Transactional
        public void recordAttempt(String id) {
            attempts++;
            mongoTemplate.save(new Document("_id", id).append("attempt", attempts), COLLECTION);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw writeConflictAtCommit();
            }
        }
    }
}
