package uk.gov.defra.trade.imports.animals.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

class CacheConfigTest {

    private CacheConfig cacheConfigUnderTest;

    @BeforeEach
    void setUp() {
        cacheConfigUnderTest = new CacheConfig();
    }

    @Test
    void testCaffeineConfig() {
        // Setup
        // Run the test
        final Caffeine<Object, Object> result = cacheConfigUnderTest.caffeineConfig();

        // Verify the results
        assertNotNull(result);
    }

    @Test
    void testCacheManager() {
        // Setup
        final Caffeine<Object, Object> caffeine = Caffeine.newBuilder();

        // Run the test
        final CacheManager result = cacheConfigUnderTest.cacheManager(caffeine);
        result.getCache("IDENTITY_TOKEN_CACHE").put("CACHE_KEY", "jwtToken");

        // Verify the results
        assertNotNull(result);
        assertEquals(1, result.getCacheNames().size());
        assertEquals(result.getCacheNames().stream().toList(), List.of("IDENTITY_TOKEN_CACHE"));
        assertEquals("jwtToken", result.getCache("IDENTITY_TOKEN_CACHE").get("CACHE_KEY").get());
    }
}
