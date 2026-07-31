package uk.gov.defra.trade.imports.plantproducts.notification;

import java.security.SecureRandom;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class PlantProductsReferenceNumberGenerator {

    public static final String REFERENCE_NUMBER_PATTERN = "^GBN-PP-\\d{2}-[0-9A-HJ-KM-NP-TV-Z]{6}$";

    private static final String CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int REF_RANDOM_LENGTH = 6;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TWO_DIGIT = "%02d";

    public String generate() {
        String yy = String.format(TWO_DIGIT, LocalDate.now().getYear() % 100);
        return String.format("GBN-PP-%s-%s", yy, randomBase32());
    }

    private static String randomBase32() {
        StringBuilder sb = new StringBuilder(REF_RANDOM_LENGTH);
        for (int i = 0; i < REF_RANDOM_LENGTH; i++) {
            sb.append(CROCKFORD_BASE32.charAt(SECURE_RANDOM.nextInt(CROCKFORD_BASE32.length())));
        }
        return sb.toString();
    }
}
