package uk.gov.defra.trade.imports.animals.configuration.tls;

import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads X509 certificates from TRUSTSTORE_* environment variables.
 *
 * This component handles the decoding and parsing of base64-encoded PEM certificates
 * provided by the CDP platform. Individual certificate failures are logged but do not
 * prevent the application from starting.
 */
@Component
@Slf4j
public class CertificateLoader {

    private final String certificate;
    
    public CertificateLoader(@Value("${cdp.certificate}") String certificate) {
        this.certificate = certificate;
    }
    
    public X509Certificate loadCustomCertificate() {
        X509Certificate cert = null;
        if (certificate == null || certificate.isEmpty()) {
            log.info("No custom certificates to load");
            return cert;
        }
        
        byte[] certData = Base64.getDecoder().decode(certificate);

        CertificateFactory cf;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            log.error("Failed to get X.509 CertificateFactory: {}", e.getMessage());
            throw new IllegalStateException("Cannot initialize certificate factory", e);
        }
        
        try {
            ByteArrayInputStream certStream = new ByteArrayInputStream(certData);
            cert = (X509Certificate) cf.generateCertificate(certStream);

            log.info("Successfully loaded certificate: (Subject: {})",
                cert.getSubjectX500Principal().getName());

        } catch (CertificateException e) {
            log.error("Failed to parse certificate: {}. Skipping.", e.getMessage());
        }
        
        return cert;
    }
   
}
