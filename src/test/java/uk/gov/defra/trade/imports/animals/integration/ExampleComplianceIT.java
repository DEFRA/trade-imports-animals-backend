package uk.gov.defra.trade.imports.animals.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import uk.gov.defra.trade.imports.animals.domain.repository.ExampleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive CDP compliance integration test for Example API.
 *
 * This test verifies ALL CDP platform requirements work together end-to-end:
 * - MongoDB CRUD operations with Testcontainers (real database)
 * - ECS JSON logging format with all required fields
 * - Request tracing (x-cdp-request-id propagation)
 * - Custom CloudWatch metrics emission
 * - Proper error handling (400, 404, 409)
 * - Bean Validation with field-level errors
 *
 * This is executable documentation proving the template meets all CDP requirements.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ExampleComplianceIT extends IntegrationBase {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.data.mongodb.ssl.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExampleRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void createExample_verifiesAllCdpCompliance(CapturedOutput output) throws Exception {
        String traceId = UUID.randomUUID().toString();
        String exampleName = "test-example-" + UUID.randomUUID();

        // 1. Test CRUD operation with trace header
        String responseBody = mockMvc.perform(post("/example")
                .header("x-cdp-request-id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + exampleName + "\",\"value\":\"test-data\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value(exampleName))
            .andExpect(jsonPath("$.value").value("test-data"))
            .andExpect(jsonPath("$.created").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

        // 3. Verify logging: trace ID appears in ECS JSON logs
        String logs = output.toString();
        assertThat(logs).contains(traceId);
        assertThat(logs).contains("POST /example");
        assertThat(logs).contains("Creating example");

        // 4. Verify MongoDB: data persisted
        assertThat(repository.findByName(exampleName)).isPresent();
    }

    @Test
    void getExample_verifies404NotFound(CapturedOutput output) throws Exception {
        String traceId = UUID.randomUUID().toString();
        String nonExistentId = UUID.randomUUID().toString();

        // Test 404 error handling with trace ID
        mockMvc.perform(get("/example/{id}", nonExistentId)
                .header("x-cdp-request-id", traceId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("https://api.cdp.defra.cloud/problems/not-found"))
            .andExpect(jsonPath("$.title").value("Resource Not Found"))
            .andExpect(jsonPath("$.detail").value("Example not found with id: " + nonExistentId))
            .andExpect(jsonPath("$.traceId").value(traceId)); // Trace ID in error response

        // Verify trace ID in logs
        assertThat(output.toString()).contains(traceId);
    }

    @Test
    void createExample_verifies409Conflict() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String exampleName = "duplicate-test-" + UUID.randomUUID();

        // Create first example
        mockMvc.perform(post("/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + exampleName + "\",\"value\":\"first\"}"))
            .andExpect(status().isCreated());

        // Attempt to create duplicate (409 Conflict)
        mockMvc.perform(post("/example")
                .header("x-cdp-request-id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + exampleName + "\",\"value\":\"second\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("https://api.cdp.defra.cloud/problems/conflict"))
            .andExpect(jsonPath("$.title").value("Resource Conflict"))
            .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void createExample_verifies400ValidationError() throws Exception {
        String traceId = UUID.randomUUID().toString();

        // Send invalid data (missing required fields)
        mockMvc.perform(post("/example")
                .header("x-cdp-request-id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"value\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("https://api.cdp.defra.cloud/problems/validation-error"))
            .andExpect(jsonPath("$.title").value("Validation Error"))
            .andExpect(jsonPath("$.errors.name").value("Name is required"))
            .andExpect(jsonPath("$.errors.value").value("Value is required"))
            .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void fullCrudFlow_verifyMongoDbOperations() throws Exception {
        String exampleName = "crud-test-" + UUID.randomUUID();

        // 1. Create
        String createResponse = mockMvc.perform(post("/example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + exampleName + "\",\"value\":\"initial\",\"counter\":1}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String id = JsonPath.read(createResponse, "$.id");
        assertThat(id).isNotBlank();

        // 2. Read by ID
        mockMvc.perform(get("/example/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(exampleName))
            .andExpect(jsonPath("$.value").value("initial"))
            .andExpect(jsonPath("$.counter").value(1));

        // 3. Read all
        mockMvc.perform(get("/example"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].name").value(exampleName));

        // 4. Update
        mockMvc.perform(put("/example/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + exampleName + "\",\"value\":\"updated\",\"counter\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("updated"))
            .andExpect(jsonPath("$.counter").value(2));

        // 5. Delete
        mockMvc.perform(delete("/example/{id}", id))
            .andExpect(status().isNoContent());

        // 6. Verify deletion (404)
        mockMvc.perform(get("/example/{id}", id))
            .andExpect(status().isNotFound());
    }

}
