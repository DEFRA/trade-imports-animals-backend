package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;
import uk.gov.defra.trade.imports.animals.notification.Notification;
import uk.gov.defra.trade.imports.animals.notification.NotificationController;
import uk.gov.defra.trade.imports.animals.notification.NotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationStatus;
import uk.gov.defra.trade.imports.animals.notification.Origin;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEvent;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;
import uk.gov.defra.trade.imports.animals.outbox.OutboxPublishService;

/**
 * End-to-end integration test for the outbox SNS relay against LocalStack.
 */
class OutboxPollerIT extends IntegrationBase {

    private static final String AWS_REGION = "eu-west-2";
    private static final String TOPIC_NAME = "trade-imports-animals-outbox-it";
    private static final String QUEUE_NAME = "trade-imports-animals-outbox-it-queue";
    private static final String NOTIFICATION_ENDPOINT = "/notifications";
    private static final String HEADER_TRACE_ID = NotificationController.HEADER_TRACE_ID;
    private static final String TRACE_PREFIX = "trace-outbox-it-";
    private static final int LOCALSTACK_PORT = 4566;

    @SuppressWarnings("resource")
    static final GenericContainer<?> LOCAL_STACK = new GenericContainer<>(
        DockerImageName.parse("localstack/localstack:3.8.1"))
        .withExposedPorts(LOCALSTACK_PORT)
        .withEnv("SERVICES", "sns,sqs")
        .withEnv("DEFAULT_REGION", AWS_REGION)
        .waitingFor(Wait.forHttp("/_localstack/health")
            .forPort(LOCALSTACK_PORT)
            .forStatusCode(200));

    private static final SnsClient SNS_CLIENT;
    private static final String TOPIC_ARN;
    private static final String QUEUE_URL;
    private static final SqsClient SQS_CLIENT;

    static {
        Startables.deepStart(LOCAL_STACK).join();

        SNS_CLIENT = snsClient();
        TOPIC_ARN = SNS_CLIENT.createTopic(
            CreateTopicRequest.builder().name(TOPIC_NAME).build()).topicArn();

        SQS_CLIENT = sqsClient();
        QUEUE_URL = SQS_CLIENT.createQueue(
            CreateQueueRequest.builder().queueName(QUEUE_NAME).build()).queueUrl();

        String queueArn = SQS_CLIENT.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(QUEUE_URL)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build())
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN);

        String policy = """
            {
              "Statement": [{
                "Effect": "Allow",
                "Principal": "*",
                "Action": "sqs:SendMessage",
                "Resource": "%s",
                "Condition": {
                  "ArnEquals": { "aws:SourceArn": "%s" }
                }
              }]
            }
            """.formatted(queueArn, TOPIC_ARN);

        SQS_CLIENT.setQueueAttributes(SetQueueAttributesRequest.builder()
            .queueUrl(QUEUE_URL)
            .attributes(Map.of(QueueAttributeName.POLICY, policy))
            .build());

        SNS_CLIENT.subscribe(SubscribeRequest.builder()
            .topicArn(TOPIC_ARN)
            .protocol("sqs")
            .endpoint(queueArn)
            .build());
    }

    @Autowired
    private OutboxPublishService outboxPublishService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerOutboxProperties(DynamicPropertyRegistry registry) {
        registry.add("app.aws.endpoint-override", () -> localstackEndpoint().toString());
        registry.add("app.aws.access-key-id", () -> "test");
        registry.add("app.aws.secret-access-key", () -> "test");
        registry.add("outbox.sns.topic-arn", () -> TOPIC_ARN);
    }

    @AfterAll
    static void tearDownAwsClients() {
        SNS_CLIENT.close();
        SQS_CLIENT.close();
    }

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        purgeQueue();
    }

    @Test
    void publishUnpublishedEvents_shouldDeliverToSnsAndMarkPublishedAt() throws Exception {
        String referenceNumber = createAndSubmitNotification(TRACE_PREFIX + "001");

        int published = outboxPublishService.publishUnpublishedEvents();

        assertThat(published).isEqualTo(1);

        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNotNull();

        Message sqsMessage = awaitSqsMessage();
        JsonNode snsEnvelope = objectMapper.readTree(sqsMessage.body());
        JsonNode publishedMessage = objectMapper.readTree(snsEnvelope.get("Message").asText());
        assertThat(publishedMessage.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(publishedMessage.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(publishedMessage.get("aggregateId").asText()).isEqualTo(event.getAggregateId());
        assertThat(publishedMessage.get("aggregateType").asText()).isEqualTo("Notification");
        assertThat(publishedMessage.get("subType").asText()).isEqualTo("GBN-AG");
        assertThat(publishedMessage.get("eventType").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(publishedMessage.get("timestamp")).isNotNull();
        assertThat(publishedMessage.get("metadata").get("correlationId").asText())
            .isEqualTo(TRACE_PREFIX + "001");
        assertThat(publishedMessage.get("metadata").get("schemaVersion").asText()).isEqualTo("1");
        assertThat(publishedMessage.get("data").get("referenceNumber").asText()).isEqualTo(referenceNumber);
        assertThat(publishedMessage.has("publishedAt")).isFalse();

        JsonNode attributes = snsEnvelope.get("MessageAttributes");
        assertThat(attributes.get("eventType").get("Value").asText())
            .isEqualTo("uk.gov.defra.imports.notification.NotificationSubmitted");
        assertThat(attributes.get("correlationId").get("Value").asText())
            .isEqualTo(TRACE_PREFIX + "001");
        assertThat(attributes.get("schemaVersion").get("Value").asText()).isEqualTo("1");
    }

    @Test
    void publishUnpublishedEvents_shouldNotRepublishAlreadyPublishedEvents() {
        createAndSubmitNotification(TRACE_PREFIX + "002");
        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(1);

        purgeQueue();
        OutboxEvent event = outboxEventRepository.findAll().getFirst();
        assertThat(event.getPublishedAt()).isNotNull();

        assertThat(outboxPublishService.publishUnpublishedEvents()).isZero();
        assertThat(receiveMessages()).isEmpty();
    }

    @Test
    void publishUnpublishedEvents_shouldPublishAggregateVersionsInOrder() throws Exception {
        String referenceNumber = createAndSubmitNotification("trace-v1");
        Notification notification = notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow();
        notification.setStatus(NotificationStatus.DRAFT);
        notificationRepository.save(notification);

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, "trace-v2")
            .exchange()
            .expectStatus().isOk();

        List<OutboxEvent> unpublished = outboxEventRepository.findAll().stream()
            .filter(e -> e.getPublishedAt() == null)
            .toList();
        assertThat(unpublished).hasSize(2);

        assertThat(outboxPublishService.publishUnpublishedEvents()).isEqualTo(2);

        List<OutboxEvent> publishedEvents = outboxEventRepository.findAll().stream()
            .sorted(Comparator.comparingLong(OutboxEvent::getAggregateVersion))
            .toList();
        assertThat(publishedEvents.get(0).getAggregateVersion()).isEqualTo(1L);
        assertThat(publishedEvents.get(1).getAggregateVersion()).isEqualTo(2L);
        assertThat(publishedEvents).allMatch(e -> e.getPublishedAt() != null);

        List<Message> messages = awaitSqsMessages(2);
        JsonNode firstEnvelope = snsEnvelopeByAggregateVersion(messages, 1L);
        JsonNode secondEnvelope = snsEnvelopeByAggregateVersion(messages, 2L);
        assertThat(firstEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v1");
        assertThat(secondEnvelope.get("MessageAttributes").get("correlationId").get("Value").asText())
            .isEqualTo("trace-v2");

        JsonNode firstPayload = objectMapper.readTree(firstEnvelope.get("Message").asText());
        JsonNode secondPayload = objectMapper.readTree(secondEnvelope.get("Message").asText());
        assertThat(firstPayload.get("aggregateVersion").asLong()).isEqualTo(1L);
        assertThat(secondPayload.get("aggregateVersion").asLong()).isEqualTo(2L);
        assertThat(firstPayload.get("data").get("referenceNumber").asText()).isEqualTo(referenceNumber);
        assertThat(secondPayload.get("data").get("referenceNumber").asText()).isEqualTo(referenceNumber);
        assertThat(firstPayload.has("publishedAt")).isFalse();
        assertThat(secondPayload.has("publishedAt")).isFalse();
    }

    private String createAndSubmitNotification(String traceId) {
        String referenceNumber = webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .bodyValue(createNotificationDto())
            .exchange().expectStatus().isOk()
            .expectBody(Notification.class).returnResult()
            .getResponseBody().getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{ref}/submit", referenceNumber)
            .header(HEADER_TRACE_ID, traceId)
            .exchange()
            .expectStatus().isOk();

        return referenceNumber;
    }

    private static NotificationDto createNotificationDto() {
        return NotificationDto.builder()
            .origin(new Origin("GB", "true", "REF123"))
            .commodity(uk.gov.defra.trade.imports.animals.notification.Commodity.builder()
                .name("Live cattle")
                .build())
            .build();
    }

    private JsonNode snsEnvelopeByAggregateVersion(List<Message> messages, long aggregateVersion)
        throws Exception {
        for (Message message : messages) {
            JsonNode snsEnvelope = objectMapper.readTree(message.body());
            JsonNode payload = objectMapper.readTree(snsEnvelope.get("Message").asText());
            if (payload.get("aggregateVersion").asLong() == aggregateVersion) {
                return snsEnvelope;
            }
        }
        throw new AssertionError("No SNS message found for aggregateVersion " + aggregateVersion);
    }

    private Message awaitSqsMessage() {
        return awaitSqsMessages(1).getFirst();
    }

    private List<Message> awaitSqsMessages(int expectedCount) {
        return await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .until(this::receiveMessages, messages -> messages.size() >= expectedCount);
    }

    private List<Message> receiveMessages() {
        List<Message> messages = SQS_CLIENT.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .messageAttributeNames("All")
                .build())
            .messages();
        return messages != null ? messages : List.of();
    }

    private void purgeQueue() {
        receiveMessages().forEach(message -> SQS_CLIENT.deleteMessage(builder -> builder
            .queueUrl(QUEUE_URL)
            .receiptHandle(message.receiptHandle())));
    }

    private static URI localstackEndpoint() {
        return URI.create("http://" + LOCAL_STACK.getHost()
            + ":" + LOCAL_STACK.getMappedPort(LOCALSTACK_PORT));
    }

    private static SnsClient snsClient() {
        return SnsClient.builder()
            .endpointOverride(localstackEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .region(Region.of(AWS_REGION))
            .build();
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder()
            .endpointOverride(localstackEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .region(Region.of(AWS_REGION))
            .build();
    }
}
