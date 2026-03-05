package uk.gov.defra.trade.imports.animals.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Example entity demonstrating MongoDB persistence with CDP compliance.
 *
 * This entity showcases:
 * - MongoDB document mapping with @Document
 * - Unique index constraint with @Indexed
 * - Bean Validation with @NotBlank
 * - Automatic ID generation with @Id
 */
@Document(collection = "examples")
@Data
@NoArgsConstructor
public class Example {

    @Id
    private String id;

    @Indexed(unique = true)
    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Value is required")
    private String value;

    private Integer counter;

    private LocalDateTime created;

    public Example(String name, String value) {
        this.name = name;
        this.value = value;
        this.created = LocalDateTime.now();
    }
}
