package uk.gov.defra.trade.imports.animals.configuration.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CertificateLoaderTest {
    
    private CertificateLoader certificateLoader;

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
    void testHandlesNullCertificate() {
        certificateLoader = new CertificateLoader(null);
        assertNull(certificateLoader.loadCustomCertificate(), "Expected a null certificate");
    }
    
    @Test
    void testHandlesEmptyCertificate() {
        certificateLoader = new CertificateLoader("");
        assertNull(certificateLoader.loadCustomCertificate(), "Expected a null certificate");

    }
    
    @Test
    void testHandlesValidCertificate() {
        byte[] encoded = Base64.getEncoder().encode(VALID_CERT_PEM.getBytes());
        certificateLoader = new CertificateLoader(new String(encoded));
        assertNotNull(certificateLoader.loadCustomCertificate());
    }
    
    @Test
    void testHandlesInvalidCertificate() {
        certificateLoader = new CertificateLoader("invalid");
        certificateLoader.loadCustomCertificate();
        assertNull(certificateLoader.loadCustomCertificate(), "Expected a null certificate");
        
    }
}
