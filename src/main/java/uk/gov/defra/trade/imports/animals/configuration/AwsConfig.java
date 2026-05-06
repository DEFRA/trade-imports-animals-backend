package uk.gov.defra.trade.imports.animals.configuration;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
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

    private final String region;
    private final String audience;
    private final Integer expiration;
    private final AppAwsConfig appAwsConfig;

    public AwsConfig(
        @Value("${aws.region}") String region,
        @Value("${aws.sts.token.audience}") String audience,
        @Value("${aws.sts.token.expiration}") Integer expiration,
        AppAwsConfig appAwsConfig) {
        this.region = region;
        this.audience = audience;
        this.expiration = expiration;
        this.appAwsConfig = appAwsConfig;
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
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(resolveCredentialsProvider())
            .overrideConfiguration(c -> c
                .retryStrategy(RetryMode.ADAPTIVE_V2)
                .apiCallTimeout(Duration.ofSeconds(30))
                .apiCallAttemptTimeout(Duration.ofSeconds(10)));
        applyEndpointOverride(builder);
        return builder.build();
    }

    private StsClient stsClient() {
        return StsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    }

    private boolean hasEndpointOverride() {
        return appAwsConfig.endpointOverride() != null
            && !appAwsConfig.endpointOverride().isBlank();
    }

    private boolean hasStaticCredentials() {
        return appAwsConfig.accessKeyId() != null
            && !appAwsConfig.accessKeyId().isBlank()
            && appAwsConfig.secretAccessKey() != null
            && !appAwsConfig.secretAccessKey().isBlank();
    }

    private AwsCredentialsProvider resolveCredentialsProvider() {
        if (hasEndpointOverride() && hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(appAwsConfig.accessKeyId(), appAwsConfig.secretAccessKey()));
        }
        if (hasEndpointOverride()) {
            log.warn("APP_AWS_ENDPOINT_OVERRIDE is set but static credentials are absent — falling back to DefaultCredentialsProvider");
        }
        return DefaultCredentialsProvider.builder().build();
    }

    private void applyEndpointOverride(S3ClientBuilder builder) {
        if (!hasEndpointOverride()) {
            return;
        }
        log.info("Using S3 endpoint override: {}", appAwsConfig.endpointOverride());
        builder.endpointOverride(URI.create(appAwsConfig.endpointOverride()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build());
    }
}
