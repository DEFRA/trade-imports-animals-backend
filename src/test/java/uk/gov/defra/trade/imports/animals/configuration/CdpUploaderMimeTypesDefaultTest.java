package uk.gov.defra.trade.imports.animals.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class CdpUploaderMimeTypesDefaultTest {

    private static final String PROPERTY = "cdp.uploader.mime-types";
    private static final String PLACEHOLDER_PREFIX = "${CDP_UPLOADER_MIME_TYPES:";

    private static List<String> checkedInDefault() throws IOException {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        String declared = sources.stream()
            .map(source -> source.getProperty(PROPERTY))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .findFirst()
            .orElseThrow(() -> new AssertionError(PROPERTY + " is not declared in application.yml"));

        assertThat(declared).startsWith(PLACEHOLDER_PREFIX).endsWith("}");

        String defaults =
            declared.substring(PLACEHOLDER_PREFIX.length(), declared.length() - 1);

        return List.of(defaults.split(","));
    }

    @Test
    void defaultMimeTypesMatchTheFrontendAllowlist() throws IOException {
        assertThat(checkedInDefault()).containsExactly(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/jpeg",
            "image/png",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
}
