package uk.gov.defra.trade.imports.animals.integration.document;

import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
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

final class CdpUploaderTestSupport {

    static final String CDP_UPLOADER_IMAGE = "defradigital/cdp-uploader:latest";
    static final String REDIS_IMAGE = "redis:7.2.3-alpine3.18";
    static final String FLOCI_IMAGE = "floci/floci:latest";

    static final String FLOCI_ALIAS = "floci";

    static final int CDP_UPLOADER_PORT = 3000;

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
    static GenericContainer<?> redisContainer(Network network, String alias) {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379)
            .withNetwork(network)
            .withNetworkAliases(alias)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                .withStartupTimeout(Duration.ofSeconds(30)));
    }

    /**
     * cdp-uploader Testcontainer in dev mode against Floci with the mock virus scanner, carrying
     * its port, its Redis host, single-instance caching, the consumer buckets it will accept
     * uploads for, and a {@code /health} readiness probe.
     *
     * <ul>
     *   <li>{@code NODE_ENV=development} switches cdp-uploader into dev mode, which relaxes
     *       redirect handling and enables the mock-scanner code paths.</li>
     *   <li>{@code MOCK_VIRUS_SCAN_ENABLED} turns the in-process mock scanner on.</li>
     *   <li>The {@code SQS_*_WAIT_TIME_SECONDS} values cut long-poll waits from the 20-second
     *       default so an upload, scan and callback round trip finishes inside a test timeout.</li>
     *   <li>{@code S3_ENDPOINT}, {@code AWS_REGION} and the static credentials point the
     *       uploader's AWS SDK clients at Floci, signing for the region it is pinned to.</li>
     * </ul>
     */
    static GenericContainer<?> cdpUploaderContainer(
        Network network, String alias, String redisAlias, String consumerBuckets, String region) {
        return new GenericContainer<>(DockerImageName.parse(CDP_UPLOADER_IMAGE))
            .withExposedPorts(CDP_UPLOADER_PORT)
            .withNetwork(network)
            .withNetworkAliases(alias)
            .withEnv("PORT", String.valueOf(CDP_UPLOADER_PORT))
            .withEnv("REDIS_HOST", redisAlias)
            .withEnv("USE_SINGLE_INSTANCE_CACHE", "true")
            .withEnv("CONSUMER_BUCKETS", consumerBuckets)
            .withEnv("NODE_ENV", "development")
            .withEnv("MOCK_VIRUS_SCAN_ENABLED", "true")
            .withEnv("SQS_SCAN_RESULTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_SCAN_RESULTS_CALLBACK_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_DOWNLOAD_REQUESTS_WAIT_TIME_SECONDS", "1")
            .withEnv("SQS_MOCK_CLAMAV_WAIT_TIME_SECONDS", "1")
            .withEnv("S3_ENDPOINT", "http://" + FLOCI_ALIAS + ":4566")
            .withEnv("AWS_REGION", region)
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("cdp-uploader")))
            .waitingFor(Wait.forHttp("/health").forPort(CDP_UPLOADER_PORT)
                .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /**
     * Floci Testcontainer with the AWS region pinned. SQS queues are scoped per region, so a queue
     * created through one region's SDK client is invisible to a client signing as another. Floci
     * enables every emulated AWS service by default, so no per-service selector is needed.
     */
    static FlociContainer flociContainer(Network network, String region) {
        return new FlociContainer(DockerImageName.parse(FLOCI_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(FLOCI_ALIAS)
            .withRegion(region);
    }

    /**
     * Builds an AWS S3 client pointed at Floci and signing for the given region.
     * Path-style addressing is forced because virtual-hosted-style attempts to resolve
     * {@code <bucket>.<host>} which doesn't work against a containerised endpoint.
     */
    static S3Client s3Client(FlociContainer flociContainer, String region) {
        return S3Client.builder()
            .endpointOverride(URI.create(flociContainer.getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(flociContainer.getAccessKey(), flociContainer.getSecretKey())))
            .region(Region.of(region))
            .forcePathStyle(true)
            .build();
    }

    /**
     * Builds an AWS SQS client pointed at Floci and signing for the given region.
     */
    static SqsClient sqsClient(FlociContainer flociContainer, String region) {
        return SqsClient.builder()
            .endpointOverride(URI.create(flociContainer.getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(flociContainer.getAccessKey(), flociContainer.getSecretKey())))
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
     *       {@code mock-clamav} queue.</li>
     * </ol>
     *
     * Expects the quarantine bucket to exist already.
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
                        .queueArn(flociSqsArn(region, MOCK_CLAMAV_QUEUE))
                        .events(Event.S3_OBJECT_CREATED)
                        .build())
                    .build())
                .build());
    }

    private static String flociSqsArn(String region, String queueName) {
        // Floci uses the literal AWS account id 000000000000
        return "arn:aws:sqs:" + region + ":000000000000:" + queueName;
    }
}
