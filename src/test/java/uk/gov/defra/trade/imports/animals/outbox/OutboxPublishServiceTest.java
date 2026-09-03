package uk.gov.defra.trade.imports.animals.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@ExtendWith(MockitoExtension.class)
class OutboxPublishServiceTest {

    private static final String TOPIC_ARN = "arn:aws:sns:eu-west-2:000000000000:test-topic.fifo";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SnsClient snsClient;

    private OutboxPublishService outboxPublishService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = createObjectMapper();
        OutboxConfig properties = new OutboxConfig(
            new OutboxConfig.Poller(2000, 10, 500, null, null, true),
            new OutboxConfig.Sns(TOPIC_ARN));
        outboxPublishService = new OutboxPublishService(
            outboxEventRepository, snsClient, objectMapper, properties);
    }

    @Nested
    class PublishUnpublishedEvents {

        @Test
        void shouldPublishFullEnvelopeWithMessageAttributes_andSetPublishedAt() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(1);
            assertThat(event.getPublishedAt()).isNotNull();

            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient).publish(captor.capture());
            PublishRequest request = captor.getValue();
            assertThat(request.topicArn()).isEqualTo(TOPIC_ARN);
            assertThat(request.messageGroupId()).isEqualTo("agg-a");
            assertThat(request.messageDeduplicationId()).isEqualTo("event-1");
            assertPublishedEnvelope(request.message(), event);
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_EVENT_TYPE).stringValue())
                .isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.value());
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_CORRELATION_ID).stringValue())
                .isEqualTo("trace-001");
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_SCHEMA_VERSION).stringValue())
                .isEqualTo("1");
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_SCHEMA_URL).stringValue()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl());
        }

        @Test
        void shouldSetPublishedAtForSavedEventButIgnoredForSentEvent() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ArgumentCaptor<PublishRequest> publishCaptor = ArgumentCaptor.forClass(PublishRequest.class);

            // When
            outboxPublishService.publishUnpublishedEvents();

            // Then
            verify(snsClient).publish(publishCaptor.capture());
            assertThat(event.getPublishedAt()).isNotNull();
            JsonNode envelope = createObjectMapper().readTree(publishCaptor.getValue().message());
            assertThat(envelope.get("publishedAt")).isNull();
        }

        @Test
        void shouldUseMillisecondPrecisionForPublishedAt() {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(event.getPublishedAt().getNano() % 1_000_000).isZero();
        }

        @Test
        void shouldPublishBeforePersistingPublishedAt() {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxPublishService.publishUnpublishedEvents();

            // Then
            var order = inOrder(snsClient, outboxEventRepository);
            order.verify(snsClient).publish(any(PublishRequest.class));
            order.verify(outboxEventRepository).save(event);
        }

        @Test
        void shouldNotRepublish_whenAlreadyPublished() {
            // Given
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of());

            // When
            int count = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(count).isZero();
            verify(snsClient, never()).publish(any(PublishRequest.class));
        }

        @Test
        void shouldPublishInRepositoryOrder_andStopOnFirstFailure() {
            // Given
            OutboxEvent v1 = unpublishedEvent("agg-a", 1L, "ref-a", "trace-a");
            OutboxEvent v2 = unpublishedEvent("agg-a", 2L, "ref-a", "trace-b");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(v1, v2));
            when(snsClient.publish(any(PublishRequest.class)))
                .thenThrow(SnsException.builder().message("SNS unavailable").build());

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isZero();
            assertThat(v1.getPublishedAt()).isNull();
            assertThat(v2.getPublishedAt()).isNull();
            verify(snsClient).publish(any(PublishRequest.class));
            verify(outboxEventRepository, never()).save(any());
        }

        @Test
        void shouldPublishEventsInAggregateVersionOrder_whenAllSucceed() {
            // Given
            OutboxEvent v1 = unpublishedEvent("agg-a", 1L, "ref-a", "trace-a");
            OutboxEvent v2 = unpublishedEvent("agg-a", 2L, "ref-a", "trace-b");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(v1, v2));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(2);
            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient, times(2)).publish(captor.capture());
            assertThat(captor.getAllValues()).hasSize(2);
            assertThat(v1.getPublishedAt()).isNotNull();
            assertThat(v2.getPublishedAt()).isNotNull();
        }

        @Test
        void shouldSkipWhenTopicArnNotConfigured() {
            // Given
            ObjectMapper objectMapper = createObjectMapper();
            OutboxConfig noTopic = new OutboxConfig(
                new OutboxConfig.Poller(2000, 10, 500, null, null, true),
                new OutboxConfig.Sns(" "));
            OutboxPublishService service = new OutboxPublishService(
                outboxEventRepository, snsClient, objectMapper, noTopic);

            // When
            int published = service.publishUnpublishedEvents();

            // Then
            assertThat(published).isZero();
            verify(outboxEventRepository, never())
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class));
        }

        @Test
        void shouldSkipEventWithNullData() {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            event.setData(null);
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isZero();
            assertThat(event.getPublishedAt()).isNull();
            verify(snsClient, never()).publish(any(PublishRequest.class));
        }

        @Test
        void shouldPublishWithEmptyAttributes_whenMetadataIsNull() {
            // Given
            OutboxEvent event = OutboxEvent.builder()
                .eventId("event-1")
                .aggregateId("agg-a")
                .aggregateVersion(1L)
                .eventType(OutboxEventType.NOTIFICATION_SUBMITTED.value())
                .data(Map.of("referenceNumber", "ref-001"))
                .metadata(null)
                .build();
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(1);
            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient).publish(captor.capture());
            assertThat(captor.getValue().messageAttributes()
                .get(OutboxPublishService.ATTR_CORRELATION_ID).stringValue()).isEmpty();
            assertThat(captor.getValue().messageAttributes()
                .get(OutboxPublishService.ATTR_SCHEMA_VERSION).stringValue()).isEmpty();
            assertThat(captor.getValue().messageAttributes()
                .get(OutboxPublishService.ATTR_SCHEMA_URL).stringValue()).isEmpty();
        }

        @Test
        void shouldStopBatchOnSerializationFailure_withoutCallingSns() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("not serializable") {});
            OutboxPublishService service = new OutboxPublishService(
                outboxEventRepository, snsClient, failingMapper,
                new OutboxConfig(
                    new OutboxConfig.Poller(2000, 10, 500, null, null, true),
                    new OutboxConfig.Sns(TOPIC_ARN)));

            // When
            int published = service.publishUnpublishedEvents();

            // Then
            assertThat(published).isZero();
            assertThat(event.getPublishedAt()).isNull();
            verify(snsClient, never()).publish(any(PublishRequest.class));
            verify(outboxEventRepository, never()).save(any());
        }

        @Test
        void shouldSetFifoFields_evenWhenTopicArnHasNoFifoSuffix() {
            // Given — transport semantics must not be inferred from the ARN
            OutboxPublishService fifoService = new OutboxPublishService(
                outboxEventRepository, snsClient, createObjectMapper(),
                new OutboxConfig(
                    new OutboxConfig.Poller(2000, 10, 500, null, null, true),
                    new OutboxConfig.Sns("arn:aws:sns:eu-west-2:000000000000:no-suffix")));
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            fifoService.publishUnpublishedEvents();

            // Then
            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient).publish(captor.capture());
            PublishRequest request = captor.getValue();
            assertThat(request.messageGroupId()).isEqualTo("agg-a");
            assertThat(request.messageDeduplicationId()).isEqualTo("event-1");
        }

        @Test
        void shouldDrainBacklogAcrossBatches_inASingleInvocation() {
            // Given — 25 events waiting against a batch size of 10
            List<OutboxEvent> backlog = backlogOf(25);
            stubRepositoryServing(backlog);

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(25);
            assertThat(backlog).allMatch(event -> event.getPublishedAt() != null);
            verify(snsClient, times(25)).publish(any(PublishRequest.class));
        }

        @Test
        void shouldStopAtMaxEventsPerRun_leavingRemainderUnpublished() {
            // Given — 25 events waiting, the run bounded to 15
            OutboxPublishService boundedService = new OutboxPublishService(
                outboxEventRepository, snsClient, createObjectMapper(),
                new OutboxConfig(
                    new OutboxConfig.Poller(2000, 10, 15, null, null, true),
                    new OutboxConfig.Sns(TOPIC_ARN)));
            List<OutboxEvent> backlog = backlogOf(25);
            stubRepositoryServing(backlog);

            // When
            int published = boundedService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(15);
            assertThat(backlog.subList(0, 15)).allMatch(event -> event.getPublishedAt() != null);
            assertThat(backlog.subList(15, 25)).allMatch(event -> event.getPublishedAt() == null);
            verify(snsClient, times(15)).publish(any(PublishRequest.class));
        }

        @Test
        void shouldStopDraining_whenPublishFailsPartWayThroughABatch() {
            // Given — 25 events waiting, SNS failing on the third
            List<OutboxEvent> backlog = backlogOf(25);
            stubRepositoryServing(backlog);
            when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().build())
                .thenReturn(PublishResponse.builder().build())
                .thenThrow(SnsException.builder().message("SNS unavailable").build());

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(2);
            assertThat(backlog.subList(2, 25)).allMatch(event -> event.getPublishedAt() == null);
            verify(snsClient, times(3)).publish(any(PublishRequest.class));
            verify(outboxEventRepository, times(2)).save(any());
        }

        @Test
        void shouldPublishOldestFirst_whenAggregateIdsSortAgainstWriteOrder() {
            // Given — the older event belongs to the higher-sorting aggregate id
            OutboxEvent older = unpublishedEvent("agg-z", 1L, "ref-z", "trace-z");
            older.setEventId("event-older");
            older.setTimestamp(Instant.parse("2026-01-15T10:00:00Z"));
            OutboxEvent newer = unpublishedEvent("agg-a", 1L, "ref-a", "trace-a");
            newer.setEventId("event-newer");
            newer.setTimestamp(Instant.parse("2026-01-15T10:00:05Z"));
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(older, newer));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(2);
            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient, times(2)).publish(captor.capture());
            assertThat(captor.getAllValues())
                .extracting(PublishRequest::messageDeduplicationId)
                .containsExactly("event-older", "event-newer");
            assertThat(captor.getAllValues())
                .extracting(PublishRequest::messageGroupId)
                .containsExactly("agg-z", "agg-a");
        }

        @Test
        void shouldKeepDrainingPastANullPayload_leavingOnlyThatEventUnpublished() {
            // Given — 25 events waiting, the first of them with no payload
            List<OutboxEvent> backlog = backlogOf(25);
            backlog.getFirst().setData(null);
            stubRepositoryServing(backlog);

            // When
            int published = outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(published).isEqualTo(24);
            assertThat(backlog.getFirst().getPublishedAt()).isNull();
            assertThat(backlog.subList(1, 25)).allMatch(event -> event.getPublishedAt() != null);
            verify(snsClient, times(24)).publish(any(PublishRequest.class));
        }
    }

    private void stubRepositoryServing(List<OutboxEvent> backlog) {
        when(outboxEventRepository
            .findByPublishedAtIsNullOrderByTimestampAscAggregateVersionAsc(any(Pageable.class)))
            .thenAnswer(invocation -> backlog.stream()
                .filter(event -> event.getPublishedAt() == null)
                .limit(invocation.<Pageable>getArgument(0).getPageSize())
                .toList());
        when(outboxEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static List<OutboxEvent> backlogOf(int size) {
        return IntStream.rangeClosed(1, size)
            .mapToObj(version ->
                unpublishedEvent("agg-a", version, "ref-" + version, "trace-" + version))
            .toList();
    }

    private static OutboxEvent unpublishedEvent(
        String aggregateId, long version, String referenceNumber, String correlationId) {
        return OutboxEvent.builder()
            .eventId("event-" + version)
            .aggregateId(aggregateId)
            .aggregateType(OutboxService.AGGREGATE_TYPE)
            .subType(OutboxService.SUB_TYPE)
            .aggregateVersion(version)
            .eventType(OutboxEventType.NOTIFICATION_SUBMITTED.value())
            .timestamp(Instant.parse("2026-01-15T10:00:00Z"))
            .data(Map.of("referenceNumber", referenceNumber))
            .metadata(OutboxEventMetadata.builder()
                .correlationId(correlationId)
                .schemaVersion("1")
                .schemaUrl(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUrl())
                .build())
            .build();
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static void assertPublishedEnvelope(String messageJson, OutboxEvent event) throws Exception {
        ObjectMapper mapper = createObjectMapper();
        JsonNode body = mapper.readTree(messageJson);
        assertThat(body.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(body.get("aggregateId").asText()).isEqualTo(event.getAggregateId());
        assertThat(body.get("aggregateType").asText()).isEqualTo(event.getAggregateType());
        assertThat(body.get("subType").asText()).isEqualTo(event.getSubType());
        assertThat(body.get("aggregateVersion").asLong()).isEqualTo(event.getAggregateVersion());
        assertThat(body.get("eventType").asText()).isEqualTo(event.getEventType());
        Instant publishedTimestamp = body.get("timestamp").isNumber()
            ? Instant.ofEpochSecond((long) body.get("timestamp").asDouble())
            : Instant.parse(body.get("timestamp").asText());
        assertThat(publishedTimestamp).isEqualTo(event.getTimestamp());
        assertThat(body.get("metadata").get("correlationId").asText())
            .isEqualTo(event.getMetadata().getCorrelationId());
        assertThat(body.get("metadata").get("schemaVersion").asText())
            .isEqualTo(event.getMetadata().getSchemaVersion());
        assertThat(body.get("data").get("referenceNumber").asText())
            .isEqualTo(event.getData().get("referenceNumber"));
        assertThat(body.has("publishedAt")).isFalse();
    }
}
