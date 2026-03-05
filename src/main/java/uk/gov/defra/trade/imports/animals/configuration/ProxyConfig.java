package uk.gov.defra.trade.imports.animals.configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;

/**
 * Configures HTTP proxy for all outbound HTTP/HTTPS requests.
 *
 * CDP Platform requirement: All egress traffic (except database connections)
 * must go through the platform's proxy server for security and access control.
 *
 * Configuration:
 * - Reads HTTP_PROXY environment variable (format: http://host:port)
 * - Sets Java system properties (http.proxyHost, http.proxyPort, https.proxyHost, https.proxyPort)
 * - Sets default ProxySelector for Java HttpClient
 *
 * This global configuration ensures:
 * - RestClient/RestTemplate automatically use the proxy
 * - Any other Java HTTP clients automatically use the proxy
 * - Internal CDP URLs are auto-routed by the proxy (no bypass needed)
 *
 * The CDP proxy:
 * - Runs on localhost:3128 when deployed
 * - Handles authentication automatically via sidecar
 * - Enforces ACLs (allowed_domains) per service
 * - Auto-routes internal *.cdp-int.defra.cloud URLs directly
 *
 * Local development:
 * - No HTTP_PROXY variable = direct connections (no proxy)
 * - Set HTTP_PROXY for testing proxy behavior locally
 */
@Configuration
@Slf4j
public class ProxyConfig {

    @Value("${cdp.proxyUrl}")
    private String httpProxy;
    
    @PostConstruct
    public void configureProxy() {

        if (httpProxy == null || httpProxy.isEmpty()) {
            log.info("No HTTP_PROXY configured - using direct connections");
            return;
        }

        try {
            URI proxyUri = URI.create(httpProxy);
            String proxyHost = proxyUri.getHost();
            int proxyPort = proxyUri.getPort();

            if (proxyHost == null || proxyPort == -1) {
                log.warn("Invalid HTTP_PROXY format: {}. Expected http://host:port", httpProxy);
                return;
            }

            // Set system properties for traditional Java HTTP clients
            System.setProperty("http.proxyHost", proxyHost);
            System.setProperty("http.proxyPort", String.valueOf(proxyPort));
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));

            // Set default ProxySelector for modern Java HttpClient (Java 11+)
            ProxySelector.setDefault(ProxySelector.of(
                new InetSocketAddress(proxyHost, proxyPort)
            ));

            log.info("HTTP proxy configured: {}:{}", proxyHost, proxyPort);

        } catch (IllegalArgumentException e) {
            log.error("Failed to parse HTTP_PROXY: {}. Error: {}",
                httpProxy, e.getMessage());
        }
    }
}
