package uk.gov.defra.trade.imports.animals.configuration;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
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
    
    
    private StsClient stsClient() {
        
        return StsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
        
    }
    
    public String getWebIdentityToken() {
        try(StsClient stsClient = stsClient()) {

            GetWebIdentityTokenRequest request = GetWebIdentityTokenRequest.builder()
                .audience(audience)
                .signingAlgorithm("RS256")
                .durationSeconds(expiration)
                .build();
            GetWebIdentityTokenResponse response = stsClient.getWebIdentityToken(request);

            log.info("STS WebIdentityToken issued at: {}", LocalDateTime.now());

            return response.webIdentityToken();
        } catch (StsException ex) {
            throw new TradeImportsAnimalsBackendException("Sts connection error: " +  ex.getMessage());
        }
        
    }
}
