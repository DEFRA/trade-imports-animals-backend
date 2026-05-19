package uk.gov.defra.trade.imports.animals.notification;

import java.security.SecureRandom;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Generates notification reference numbers in the format {@code GBN-AG-{YY}-{XXXXXX}}.
 *
 * <p>{@code XXXXXX} is a 6-character Crockford base32 random body (digits 0–9 and letters A–Z
 * excluding I, L, O, U), drawn from {@link SecureRandom}. Collision detection and retry on
 * persistence failure are handled by the caller.
 */
@Component
public class ReferenceNumberGenerator {

    private static final String CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int REF_RANDOM_LENGTH = 6;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final String GBN = "GBN";
    public static final String COMMODITY = "-AG-";
    public static final String HYPHEN = "-";
    public static final String REFERENCE_NUMBER_PATTERN =
        "^" + GBN + COMMODITY + "\\d{2}" + HYPHEN + "[0-9A-HJ-KM-NP-TV-Z]{6}$";

    /**
     * Generates a reference number in the format {@code GBN-AG-{YY}-{XXXXXX}}.
     */
    public String generate() {
        String yy = String.format("%02d", LocalDate.now().getYear() % 100);
        return GBN + COMMODITY + yy + HYPHEN + randomBase32();
    }

    private static String randomBase32() {
        StringBuilder sb = new StringBuilder(REF_RANDOM_LENGTH);
        for (int i = 0; i < REF_RANDOM_LENGTH; i++) {
            sb.append(CROCKFORD_BASE32.charAt(SECURE_RANDOM.nextInt(CROCKFORD_BASE32.length())));
        }
        return sb.toString();
    }
}
