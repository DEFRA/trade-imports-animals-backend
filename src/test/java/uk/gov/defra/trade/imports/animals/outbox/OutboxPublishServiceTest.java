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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.services.sns.model.SnsException;
import uk.gov.defra.trade.imports.animals.configuration.OutboxConfig;

@ExtendWith(MockitoExtension.class)
class OutboxPublishServiceTest {

    private static final String TOPIC_ARN = "arn:aws:sns:eu-west-2:000000000000:test-topic";
    private static final String FIFO_TOPIC_ARN =
        "arn:aws:sns:eu-west-2:000000000000:test-topic.fifo";
    
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private SnsClient snsClient;

    private OutboxPublishService outboxPublishService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = createObjectMapper();
        OutboxConfig properties = new OutboxConfig(
            new OutboxConfig.Poller(2000, 10, null, null, true),
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
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
            assertPublishedEnvelope(request.message(), event);
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_EVENT_TYPE).stringValue())
                .isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.value());
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_CORRELATION_ID).stringValue())
                .isEqualTo("trace-001");
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_SCHEMA_VERSION).stringValue())
                .isEqualTo("1");
            assertThat(request.messageAttributes().get(OutboxPublishService.ATTR_SCHEMA_URI).stringValue()).isEqualTo(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUri());
        }

        @Test
        void shouldSetPublishedAtBeforePublishingToSns() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ArgumentCaptor<PublishRequest> publishCaptor = ArgumentCaptor.forClass(PublishRequest.class);

            // When
            outboxPublishService.publishUnpublishedEvents();

            // Then
            verify(snsClient).publish(publishCaptor.capture());
            assertThat(event.getPublishedAt()).isNotNull();
            JsonNode envelope = createObjectMapper().readTree(publishCaptor.getValue().message());
            assertThat(Instant.parse(envelope.get("publishedAt").asText()))
                .isEqualTo(event.getPublishedAt());
        }

        @Test
        void shouldUseMillisecondPrecisionForPublishedAt() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // When
            outboxPublishService.publishUnpublishedEvents();

            // Then
            assertThat(event.getPublishedAt().getNano() % 1_000_000).isZero();
            ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
            verify(snsClient).publish(captor.capture());
            JsonNode body = createObjectMapper().readTree(captor.getValue().message());
            assertThat(body.get("publishedAt").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        }

        @Test
        void shouldPublishBeforePersistingPublishedAt() {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                new OutboxConfig.Poller(2000, 10, null, null, true),
                new OutboxConfig.Sns(" "));
            OutboxPublishService service = new OutboxPublishService(
                outboxEventRepository, snsClient, objectMapper, noTopic);

            // When
            int published = service.publishUnpublishedEvents();

            // Then
            assertThat(published).isZero();
            verify(outboxEventRepository, never())
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class));
        }

        @Test
        void shouldSkipEventWithNullData() {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            event.setData(null);
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
        }

        @Test
        void shouldStopBatchOnSerializationFailure_withoutCallingSns() throws Exception {
            // Given
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("not serializable") {});
            OutboxPublishService service = new OutboxPublishService(
                outboxEventRepository, snsClient, failingMapper,
                new OutboxConfig(
                    new OutboxConfig.Poller(2000, 10, null, null, true),
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
        void shouldSetFifoFields_whenTopicIsFifo() {
            // Given
            ObjectMapper objectMapper = createObjectMapper();
            OutboxPublishService fifoService = new OutboxPublishService(
                outboxEventRepository, snsClient, objectMapper,
                new OutboxConfig(
                    new OutboxConfig.Poller(2000, 10, null, null, true),
                    new OutboxConfig.Sns(FIFO_TOPIC_ARN)));
            OutboxEvent event = unpublishedEvent("agg-a", 1L, "ref-001", "trace-001");
            when(outboxEventRepository
                .findByPublishedAtIsNullOrderByAggregateIdAscAggregateVersionAsc(any(Pageable.class)))
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
                .schemaUri(OutboxEventType.NOTIFICATION_SUBMITTED.schemaUri())
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
        assertThat(body.has("publishedAt")).isTrue();
        Instant envelopePublishedAt = Instant.parse(body.get("publishedAt").asText());
        assertThat(envelopePublishedAt).isEqualTo(event.getPublishedAt());
        assertThat(envelopePublishedAt).isEqualTo(
            event.getPublishedAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(body.get("publishedAt").asText())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }
}
