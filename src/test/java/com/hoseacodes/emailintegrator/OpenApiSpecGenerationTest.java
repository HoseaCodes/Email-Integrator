package com.hoseacodes.emailintegrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Generates the OpenAPI specification from the running application and writes it to
 * {@code target/openapi.json}, which the documentation workflow publishes as the API reference.
 *
 * <p>Generating rather than hand-maintaining the spec is the whole point: a checked-in OpenAPI
 * document drifts from the code silently, and a published reference that disagrees with the running
 * service is worse than none — callers trust it and build against something that does not exist.
 * Because this runs as part of {@code mvn verify}, the published reference is regenerated on every
 * build and cannot fall behind.
 *
 * <p>It is a test rather than a Maven plugin so that it also <em>asserts</em>. A generated document
 * that happens to be empty, or that has lost its security scheme because a bean was renamed, would
 * publish silently. The assertions below are the guard against that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecGenerationTest {

    /** Written into the build directory, never into source control. */
    private static final Path OUTPUT = Path.of("target", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("generates a complete OpenAPI document and writes it for publication")
    void generatesSpec() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode spec = objectMapper.readTree(json);

        // The endpoints a caller most needs documented.
        assertThat(spec.at("/paths/~1email/post").isMissingNode())
                .as("POST /email must appear in the published spec").isFalse();
        assertThat(spec.at("/paths/~1auth~1send-email/post").isMissingNode())
                .as("POST /auth/send-email must appear in the published spec").isFalse();

        // Without this, Swagger UI renders an Authorize button that does nothing and every
        // request from the published docs returns 401 — which reads as a broken API.
        assertThat(spec.at("/components/securitySchemes/ApiKeyAuth/name").asText())
                .as("the API key security scheme must be published")
                .isEqualTo("X-API-Key");

        // Request schemas come from the typed DTOs. If a body ever reverts to an untyped Map, the
        // schema silently loses its properties and this catches it.
        assertThat(spec.at("/components/schemas/SendEmailRequest/required").isMissingNode())
                .as("SendEmailRequest must publish its required fields").isFalse();

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(spec));

        assertThat(OUTPUT).exists();
    }
}
