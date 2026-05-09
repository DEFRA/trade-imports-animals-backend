package uk.gov.defra.trade.imports.animals.integration.document;

import java.time.Duration;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Testcontainer factories and LocalStack wiring shared between document-area ITs that exercise
 * cdp-uploader (currently {@link DocumentControllerIT} for the dev-mode real-scan flow and
 * {@link DocumentInitiateProductionModeIT} for the production-mode contract pin).
 *
 * <p>Each IT owns its own Docker {@link Network} and container instances — these helpers exist
 * to remove the boilerplate of constructing them, not to share container state across classes.
 */
final class CdpUploaderTestSupport {

    static final String CDP_UPLOADER_IMAGE = "defradigital/cdp-uploader:latest";
    static final String REDIS_IMAGE = "redis:7.2.3-alpine3.18";
    static final String LOCALSTACK_IMAGE = "localstack/localstack:3";

    static final String LOCALSTACK_ALIAS = "localstack";

    static final String DOCUMENTS_BUCKET = "trade-imports-animals-documents";
    static final String QUARANTINE_BUCKET = "cdp-uploader-quarantine";
    static final String MOCK_CLAMAV_QUEUE = "mock-clamav";
    static final String SCAN_RESULTS_QUEUE = "cdp-clamav-results";
    static final String SCAN_RESULTS_CALLBACK_QUEUE = "cdp-uploader-scan-results-callback.fifo";
    static final String DOWNLOAD_REQUESTS_QUEUE = "cdp-uploader-download-requests";

    private CdpUploaderTestSupport() {}

    /**
     * Redis Testcontainer with the standard wait-for-readiness log probe. cdp-uploader uses
     * Redis for upload-session state — without it, /initiate hangs.
     */
    @SuppressWarnings("resource") // long-lived static container; lifecycle owned by Testcontainers' Ryuk reaper
    static GenericContainer<?> redisContainer(Network network, String alias) {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379)
            .withNetwork(network)
            .withNetworkAliases(alias)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));
    }

    /**
     * Pre-configured cdp-uploader Testcontainer with the env vars common to all IT shapes
     * (PORT, REDIS_HOST, USE_SINGLE_INSTANCE_CACHE, CONSUMER_BUCKETS) and a /health-based
     * readiness probe. Callers chain {@code .withEnv(...)} for NODE_ENV and any mode-specific
     * scanner/AWS config.
     */
    @SuppressWarnings("resource")
    static GenericContainer<?> cdpUploaderContainer(
        Network network, String alias, String redisAlias, String consumerBuckets) {
        return new GenericContainer<>(DockerImageName.parse(CDP_UPLOADER_IMAGE))
            .withExposedPorts(3000)
            .withNetwork(network)
            .withNetworkAliases(alias)
            .withEnv("PORT", "3000")
            .withEnv("REDIS_HOST", redisAlias)
            .withEnv("USE_SINGLE_INSTANCE_CACHE", "true")
            .withEnv("CONSUMER_BUCKETS", consumerBuckets)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("cdp-uploader")))
            .waitingFor(Wait.forHttp("/health").forPort(3000)
                .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * LocalStack Testcontainer with S3 + SQS enabled and the AWS region pinned. Region pinning
     * is load-bearing: SQS queues are scoped per region in LocalStack, so a queue created via
     * one region's SDK client is invisible to a client signing as another. Match this to the
     * cdp-uploader container's {@code AWS_REGION}.
     */
    static LocalStackContainer localStackContainer(Network network, String region) {
        return new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE))
            .withServices("s3", "sqs")
            .withNetwork(network)
            .withNetworkAliases(LOCALSTACK_ALIAS)
            .withEnv("DEFAULT_REGION", region)
            .withEnv("AWS_DEFAULT_REGION", region);
    }

    /**
     * Builds an AWS S3 client pointed at LocalStack and signing for the given region.
     */
    static S3Client s3Client(LocalStackContainer localStack, String region) {
        return S3Client.builder()
            .endpointOverride(localStack.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
            .region(Region.of(region))
            .build();
    }

    /**
     * Builds an AWS SQS client pointed at LocalStack and signing for the given region.
     */
    static SqsClient sqsClient(LocalStackContainer localStack, String region) {
        return SqsClient.builder()
            .endpointOverride(localStack.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
            .region(Region.of(region))
            .build();
    }

    /**
     * Wires the SQS queues + the quarantine→mock-clamav S3 bucket notification that
     * cdp-uploader's mock virus scanner depends on:
     *
     * <ol>
     *   <li>Creates {@code mock-clamav}, {@code cdp-clamav-results}, {@code cdp-uploader-download-requests}
     *       (standard) and {@code cdp-uploader-scan-results-callback.fifo} (FIFO + content-based dedup).</li>
     *   <li>Configures the quarantine bucket to fan {@code s3:ObjectCreated:*} events to the
     *       {@code mock-clamav} queue. LocalStack uses the literal AWS account id 000000000000.</li>
     * </ol>
     *
     * Caller is responsible for pre-creating the buckets — we don't create them here because
     * each IT has its own additional buckets (e.g. the consumer bucket).
     */
    static void wireScanQueuesAndBucketNotification(
        SqsClient sqsClient, S3Client s3Client, String region) {
        sqsClient.createQueue(CreateQueueRequest.builder().queueName(MOCK_CLAMAV_QUEUE).build());
        sqsClient.createQueue(CreateQueueRequest.builder().queueName(SCAN_RESULTS_QUEUE).build());
        // cdp-uploader expects this queue to exist on startup; not consumed by these tests.
        sqsClient.createQueue(CreateQueueRequest.builder().queueName(DOWNLOAD_REQUESTS_QUEUE).build());
        sqsClient.createQueue(CreateQueueRequest.builder()
            .queueName(SCAN_RESULTS_CALLBACK_QUEUE)
            .attributes(Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"))
            .build());

        s3Client.putBucketNotificationConfiguration(
            PutBucketNotificationConfigurationRequest.builder()
                .bucket(QUARANTINE_BUCKET)
                .notificationConfiguration(NotificationConfiguration.builder()
                    .queueConfigurations(QueueConfiguration.builder()
                        .queueArn("arn:aws:sqs:" + region + ":000000000000:" + MOCK_CLAMAV_QUEUE)
                        .events(Event.S3_OBJECT_CREATED)
                        .build())
                    .build())
                .build());
    }

    /**
     * Adds the cluster of env vars required for cdp-uploader's mock virus scanner to run end-to-end:
     * dev-mode flags, mock-virus toggles, AWS endpoints/credentials, and SQS long-poll waits
     * cranked down to 1s so the upload→scan→callback round-trip completes inside a normal
     * IT timeout rather than blocking on the 20s default.
     */
    static GenericContainer<?> withDevModeMockScannerEnv(GenericContainer<?> container, String region) {
        return container
            .withEnv("NODE_ENV", "development")
            .withEnv("MOCK_VIRUS_SCAN_ENABLED", "true")
            .withEnv("MOCK_VIRUS_RESULT_DELAY", "0")
            .withEnv("SQS_SCAN_RESULTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_SCAN_RESULTS_CALLBACK_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_DOWNLOAD_REQUESTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_MOCK_CLAMAV_WAIT_TIME_SECONDS", "1")
            .withEnv("S3_ENDPOINT", "http://" + LOCALSTACK_ALIAS + ":4566")
            .withEnv("AWS_REGION", region)
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test");
    }
}
