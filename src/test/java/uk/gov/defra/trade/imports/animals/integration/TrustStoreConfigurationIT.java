package uk.gov.defra.trade.imports.animals.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.defra.trade.imports.animals.configuration.tls.CertificateLoader;

/**
 * Integration tests for TLS certificate handling and SSLContext configuration.
 * <p>
 * Tests verify that the application can:
 * 1. Create SSLContext combining JVM defaults (no custom certificates in test env)
 * 2. Load and parse base64-encoded X509 certificates
 * 3. Handle empty certificate scenarios gracefully
 * <p>
 * Note: These tests run without TRUSTSTORE_* environment variables set,
 * so they test the "no custom certificates" path. This is the most common
 * scenario and ensures the application works without custom CAs.
 */

class TrustStoreConfigurationIT extends IntegrationBase {

    @Autowired
    private SSLContext customSslContext;

    /**
     * Valid self-signed X509 certificate (CN=Test Certificate) in PEM format.
     * Generated using: openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=Test Certificate"
     */
    private static final String VALID_CERT_PEM = """
-----BEGIN CERTIFICATE-----
MIIDXTCCAkWgAwIBAgIJAKL0UG+mRKKzMA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV
BAYTAkdCMRMwEQYDVQQIDApTb21lLVN0YXRlMSEwHwYDVQQKDBhJbnRlcm5ldCBX
aWRnaXRzIFB0eSBMdGQwHhcNMjQwMTAxMTIwMDAwWhcNMjUwMTAxMTIwMDAwWjBF
MQswCQYDVQQGEwJHQjETMBEGA1UECAwKU29tZS1TdGF0ZTEhMB8GA1UECgwYSW50
ZXJuZXQgV2lkZ2l0cyBQdHkgTHRkMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB
CgKCAQEAy8Dbv8prpJ/0kKhlGeJYozo2t60EG8eOLYKqZCNb1NVQFGe5Omwpe+6j
4aCq2TqXWquc+oudPkJBDwW6VO3GqNQiVQzmA0p6f9JG0m2/kXiE4E9PkWoHDXyY
hwcZQseN81ISlnC6PX7F5sI8KJmR3YJbCq4m+RqIPzHq2f8Fmh3L1lKbAhqT7Fmz
dTxlCQ7Z5fAK4pE7nJBqCKwkXbPyT9xVGkfLvvTxLLzLNMW5CJF8xqPGMrFQFBFF
OBSEqLJTGGMbXhGMmFVWnD1kLlNyYbH8xmNjfcJyDPqF6KFJmXA6d1k5JKxGJqBf
Gz0q6gv+2TgCXnvyKSqQgM/t5vRUqQIDAQABo1AwTjAdBgNVHQ4EFgQUElRjSoVg
KjUqY6+5QxR2mL8M8gQwHwYDVR0jBBgwFoAUElRjSoVgKjUqY6+5QxR2mL8M8gQw
DAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAQ7H9pNFLpJ5MF1hLk1Mm
FMWdPNkv8ysB0f9Z5nWQPnHOoFLMeNRLPJnI5K7xBdPvUhKzLXqLpPkdqf9hCxrv
tS8MZQNx3nNjbMqh7PLKDhPfxp2nCcHh0jBVnMNRAJNPPxkJRo8cHMfZLxgx8p9B
NzqG3xGfCpmYNVLMAMGcA7OGJjGMKZJy4Qkj2VRLtHx1H9Z0H3dPfqDCNEQPfD3l
IxFMR0LKI8J1xGMW5jQRLWb7PcG7+PG3NlJ5nNqPDJ2qh9nFKNKMYPnPgDxoJTLF
TvBCqLlXHMnPLWLZJKLAVMj4qRKH7PpZNvUjP4GjGQTQJmJIx6EWBkNZhMuFxqKZ
fA==
-----END CERTIFICATE-----
""".trim();

    @Test
    void customSslContext_shouldBeConfigured() {
        // Given: Application started without custom certificates

        // When: SSLContext is injected

        // Then: SSLContext should be configured with default trust store
        assertThat(customSslContext).isNotNull();
        assertThat(customSslContext.getProtocol()).isEqualTo("TLS");
    }

    @Test
    void certificateLoader_shouldReturnNullWhenNoCertificate() {
        // When: Loading certificates
        CertificateLoader loader = new CertificateLoader(null);
        X509Certificate cert = loader.loadCustomCertificate();

        // Then: Should return null
        assertThat(cert)
            .as("Should return null when no certificate found")
            .isNull();
    }

    @Test
    void certificateLoader_shouldLoadValidCertificateWhenProvided() {
        // Given: Mock scanner that returns valid certificate data
        byte[] encoded = Base64.getEncoder().encode(VALID_CERT_PEM.getBytes());
        CertificateLoader loader = new CertificateLoader(new String(encoded));

        // When: Loading certificates
        X509Certificate cert = loader.loadCustomCertificate();

        // Then: Certificate should be successfully loaded
        assertThat(cert)
            .as("Should load 1 certificate")
            .isNotNull();

        assertThat(cert.getSubjectX500Principal().getName())
            .as("Certificate subject should contain GB")
            .contains("GB");
    }

}
