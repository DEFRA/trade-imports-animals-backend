package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.notification.Commodity;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;

/**
 * The regression test for EUDPA-356: several callers save at once while the {@code outbox}
 * collection is missing, which is what a database wipe under a running service leaves behind.
 * MongoDB takes an exclusive lock to create a collection, so the transactions racing to be first
 * conflict, and before this fix the loser's request became a 500.
 *
 * <p>Honest about what it proves: whether the transactions genuinely collide on a given run is up
 * to MongoDB's scheduling, so a green run does not prove the race occurred. It is the closest
 * thing to the reported failure, and it can only fail in the direction that matters — a caller
 * seeing anything other than 200. The deterministic proof lives in {@link
 * TransactionRetryAdvisorIT}.
 */
class OutboxCollectionRaceIT extends IntegrationBase {

    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final int CONCURRENT_WRITERS = 4;
    private static final int ROUNDS = 3;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
    }

    /**
     * Puts back what the test deliberately broke. Dropping {@code outbox} takes its indexes with
     * it, and the writes that recreate it implicitly do not restore them — so without this, every
     * integration test that runs afterwards against this shared container would be relying on an
     * {@code aggregate_version_uq} index that is no longer there.
     */
    @AfterEach
    void restoreTheCollectionsThisTestDropped() {
        MongoPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(OutboxEvent.class);
        IndexResolver.create(mappingContext)
            .resolveIndexFor(entity.getTypeInformation())
            .forEach(definition -> mongoTemplate.indexOps(entity.getCollection()).createIndex(definition));
    }

    @Test
    void put_shouldSucceedForEveryCaller_whenConcurrentSavesRaceOnAMissingOutboxCollection()
        throws Exception {

        // Distinct notifications, so the per-aggregate outbox lock cannot serialise the writers.
        // The collection-creation conflict is database-wide and races regardless.
        List<String> references = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_WRITERS; i++) {
            references.add(createDraft());
        }
        WebTestClient client = webClient("NoAuth");

        ExecutorService writers = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                // Exactly what a mid-life database reseed leaves behind: the service is up, its
                // collections are not.
                mongoTemplate.dropCollection("outbox");

                List<HttpStatusCode> statuses = editAllAtOnce(writers, client, references, round);

                assertThat(statuses).as("statuses on round %d", round)
                    .containsOnly(HttpStatus.OK);
                assertThat(outboxEventRepository.findAll())
                    .as("outbox events on round %d", round)
                    .hasSize(CONCURRENT_WRITERS);
            }
        } finally {
            writers.shutdownNow();
        }
    }

    private List<HttpStatusCode> editAllAtOnce(ExecutorService writers, WebTestClient client,
        List<String> references, int round) throws Exception {

        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<HttpStatusCode>> edits = references.stream()
            .map(reference -> editWhenReleased(client, reference, round, startGate))
            .toList();

        List<Future<HttpStatusCode>> inFlight = edits.stream().map(writers::submit).toList();
        startGate.countDown();

        List<HttpStatusCode> statuses = new ArrayList<>();
        for (Future<HttpStatusCode> future : inFlight) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private Callable<HttpStatusCode> editWhenReleased(WebTestClient client, String reference,
        int round, CountDownLatch startGate) {

        NotificationAggregate current = notificationRepository.findByReferenceNumber(reference)
            .orElseThrow();
        NotificationDto edit = NotificationDto.builder()
            .referenceNumber(reference)
            .origin(new Origin("GB", "no", "ROUND-" + round))
            .commodity(Commodity.builder().name("Live cattle").build())
            .concurrencyToken(current.getConcurrencyToken())
            .build();

        return () -> {
            startGate.await();
            return client
                .put().uri(NOTIFICATION_ENDPOINT + "/{ref}", reference)
                .bodyValue(SaveNotificationDto.of(edit))
                .exchange()
                .returnResult(Void.class)
                .getStatus();
        };
    }

    private String createDraft() {
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
            .returnResult().getResponseBody().getReferenceNumber();
    }
}
