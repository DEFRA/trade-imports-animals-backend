package uk.gov.defra.trade.imports.animals.integration;

import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

public final class FlociClients {

    private FlociClients() {
    }

    public static <B extends AwsClientBuilder<B, C>, C> B pointedAt(
        FlociContainer floci, String region, B builder) {
        return builder
            .endpointOverride(URI.create(floci.getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
            .region(Region.of(region));
    }

    public static S3Client s3Client(FlociContainer floci, String region) {
        return pointedAt(floci, region, S3Client.builder()).forcePathStyle(true).build();
    }

    public static SqsClient sqsClient(FlociContainer floci, String region) {
        return pointedAt(floci, region, SqsClient.builder()).build();
    }
}
