package uk.gov.defra.trade.imports.animals.integration.outbox;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.testcontainers.FlociContainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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
import uk.gov.defra.trade.imports.animals.filter.RequestTracingFilter;
import uk.gov.defra.trade.imports.animals.integration.IntegrationBase;
import uk.gov.defra.trade.imports.animals.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.animals.notification.SaveNotificationDto;
import uk.gov.defra.trade.imports.animals.notification.NotificationRepository;
import uk.gov.defra.trade.imports.animals.notification.NotificationTestSupport;
import uk.gov.defra.trade.imports.animals.outbox.OutboxEventRepository;

/**
 * Base class for integration tests that publish to SNS and consume from SQS via Floci. Starts a
 * single shared Floci container, creates the outbox SNS topic and a test SQS subscriber queue, and
 * wires them into the Spring context via {@link DynamicPropertySource}.
 */
abstract class OutboxIntegrationBase extends IntegrationBase {

    private static final String AWS_REGION = "eu-west-2";
    private static final String TOPIC_NAME = "trade-imports-animals-outbox-it.fifo";
    private static final String QUEUE_NAME = "trade-imports-animals-outbox-it-queue.fifo";

    @SuppressWarnings("resource")
    static final FlociContainer FLOCI = new FlociContainer(
        DockerImageName.parse("floci/floci:latest"))
        .withRegion(AWS_REGION);

    static final SnsClient SNS_CLIENT;
    static final String TOPIC_ARN;
    static final String QUEUE_URL;
    static final SqsClient SQS_CLIENT;
    static final S3Client S3_CLIENT;

    static {
        Startables.deepStart(FLOCI).join();

        SNS_CLIENT = configure(SnsClient.builder()).build();
        TOPIC_ARN = SNS_CLIENT.createTopic(CreateTopicRequest.builder()
            .name(TOPIC_NAME)
            .attributes(Map.of("FifoTopic", "true"))
            .build()).topicArn();

        SQS_CLIENT = configure(SqsClient.builder()).build();
        S3_CLIENT = configure(S3Client.builder()).forcePathStyle(true).build();
        QUEUE_URL = SQS_CLIENT.createQueue(CreateQueueRequest.builder()
            .queueName(QUEUE_NAME)
            .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
            .build()).queueUrl();

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

    @DynamicPropertySource
    static void registerOutboxProperties(DynamicPropertyRegistry registry) {
        registry.add("app.aws.endpoint-override", FLOCI::getEndpoint);
        registry.add("app.aws.access-key-id", FLOCI::getAccessKey);
        registry.add("app.aws.secret-access-key", FLOCI::getSecretKey);
        registry.add("outbox.sns.topic-arn", () -> TOPIC_ARN);
    }

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void clearOutboxState() {
        notificationRepository.deleteAll();
        outboxEventRepository.deleteAll();
        purgeQueue();
    }

    protected void ensureBucketExists(String bucket) {
        boolean exists = S3_CLIENT.listBuckets().buckets().stream()
            .anyMatch(existing -> existing.name().equals(bucket));
        if (!exists) {
            S3_CLIENT.createBucket(builder -> builder.bucket(bucket));
        }
    }

    protected Message awaitSqsMessage() {
        return awaitSqsMessages(1).getFirst();
    }

    protected List<Message> awaitSqsMessages(int expectedCount) {
        return awaitMessages(expectedCount, message -> {
            deleteMessage(message);
        });
    }

    protected List<Message> awaitInFlightMessages(int expectedCount) {
        return awaitMessages(expectedCount, message -> {});
    }

    private List<Message> awaitMessages(int expectedCount, Consumer<Message> onEach) {
        List<Message> collected = new ArrayList<>();
        await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(200))
            .until(() -> {
                receiveMessages().forEach(message -> {
                    collected.add(message);
                    onEach.accept(message);
                });
                return collected.size();
            }, count -> count >= expectedCount);
        return List.copyOf(collected);
    }

    protected List<Message> receiveMessages() {
        List<Message> messages = SQS_CLIENT.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .messageAttributeNames("All")
                .build())
            .messages();
        return messages != null ? messages : List.of();
    }

    protected void deleteMessage(Message message) {
        SQS_CLIENT.deleteMessage(builder -> builder
            .queueUrl(QUEUE_URL)
            .receiptHandle(message.receiptHandle()));
    }

    protected void purgeQueue() {
        receiveMessages().forEach(this::deleteMessage);
    }

    protected JsonNode snsEnvelopeByAggregateVersion(List<Message> messages, long aggregateVersion)
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

    protected String createAndSubmitNotification(String traceId) {
        String referenceNumber = createNewNotification(traceId).getReferenceNumber();

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT + "/{referenceNumber}/submit?concurrencyToken={token}",
                referenceNumber, currentConcurrencyToken(referenceNumber))
            .header(RequestTracingFilter.TRACE_ID_HEADER, traceId)
            .exchange()
            .expectStatus().isOk();

        return referenceNumber;
    }

    protected Long currentConcurrencyToken(String referenceNumber) {
        return NotificationTestSupport.findByReferenceNumber(mongoTemplate, referenceNumber)
            .orElseThrow()
            .getConcurrencyToken();
    }

    protected String createAndSaveNotification(String traceId) {
        NotificationAggregate created = createNewNotification(traceId);

        webClient("NoAuth")
            .post().uri(NOTIFICATION_ENDPOINT)
            .header(RequestTracingFilter.TRACE_ID_HEADER, traceId)
            .bodyValue(SaveNotificationDto.of(minimalNotificationDto().toBuilder()
                .referenceNumber(created.getReferenceNumber())
                .concurrencyToken(created.getConcurrencyToken())
                .build()))
            .exchange()
            .expectStatus().isOk();

        return created.getReferenceNumber();
    }

    private static <B extends AwsClientBuilder<B, C>, C> B configure(B builder) {
        return builder
            .endpointOverride(java.net.URI.create(FLOCI.getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
            .region(Region.of(AWS_REGION));
    }
}
