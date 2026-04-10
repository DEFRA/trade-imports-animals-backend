package uk.gov.defra.trade.imports.animals.configuration;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetWebIdentityTokenRequest;
import software.amazon.awssdk.services.sts.model.GetWebIdentityTokenResponse;
import software.amazon.awssdk.services.sts.model.StsException;
import uk.gov.defra.trade.imports.animals.exceptions.TradeImportsAnimalsBackendException;

@Slf4j
@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Value("${aws.sts.token.audience}")
    private String audience;

    @Value("${aws.sts.token.expiration}")
    private Integer expiration;

    @Value("${app.aws.endpoint-override:}")
    private String endpointOverride;

    @Value("${app.aws.access-key-id:}")
    private String accessKeyId;

    @Value("${app.aws.secret-access-key:}")
    private String secretAccessKey;

    private StsClient stsClient() {

        return StsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();

    }

    public String getWebIdentityToken() {
        try (StsClient stsClient = stsClient()) {

            GetWebIdentityTokenRequest request = GetWebIdentityTokenRequest.builder()
                .audience(audience)
                .signingAlgorithm("RS256")
                .durationSeconds(expiration)
                .build();
            GetWebIdentityTokenResponse response = stsClient.getWebIdentityToken(request);

            log.info("STS WebIdentityToken issued at: {}", LocalDateTime.now());

            return response.webIdentityToken();
        } catch (StsException ex) {
            throw new TradeImportsAnimalsBackendException("Sts connection error: " + ex.getMessage());
        }
    }

    @Bean
    public S3Client s3Client() {
        boolean hasEndpointOverride = endpointOverride != null && !endpointOverride.isBlank();
        boolean hasStaticCredentials = accessKeyId != null && !accessKeyId.isBlank()
            && secretAccessKey != null && !secretAccessKey.isBlank();

        var credentialsProvider = hasEndpointOverride && hasStaticCredentials
            ? StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey))
            : DefaultCredentialsProvider.builder().build();

        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider)
            .overrideConfiguration(c -> c
                .retryStrategy(RetryMode.ADAPTIVE)
                .apiCallTimeout(Duration.ofSeconds(30))
                .apiCallAttemptTimeout(Duration.ofSeconds(10)));

        if (hasEndpointOverride) {
            log.info("Using S3 endpoint override: {}", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride))
                   .serviceConfiguration(S3Configuration.builder()
                       .pathStyleAccessEnabled(true)
                       .build());
        }

        return builder.build();
    }
}

