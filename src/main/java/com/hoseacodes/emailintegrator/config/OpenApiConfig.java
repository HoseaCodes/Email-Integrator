package com.hoseacodes.emailintegrator.config;

import com.hoseacodes.emailintegrator.security.ApiKeyProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata and the API's security scheme.
 *
 * <p>springdoc generates paths and schemas from the controllers and DTOs, but it cannot infer how
 * a caller is expected to authenticate — that lives in the Spring Security filter chain, which is
 * invisible to it. Without the declaration below, Swagger UI renders an "Authorize" button that
 * does nothing and every request from it returns 401, which reads as a broken API rather than a
 * documented one.
 *
 * <p>Declaring the requirement globally rather than per-operation matches the actual rule: the
 * filter chain denies by default, so every endpoint needs the key except the handful explicitly
 * permitted. Operations that are genuinely public are marked individually.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    OpenAPI emailIntegratorOpenApi(ApiKeyProperties apiKeyProperties) {
        SecurityScheme apiKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(apiKeyProperties.headerName())
                .description("""
                        Shared secret issued per client. Send it on every request except \
                        GET /actuator/health, the approval links, and this documentation.

                        Approval links (GET /auth/approve, GET /auth/deny) are authorised by the \
                        signed JWT in their query string instead, because they are clicked from an \
                        email client that cannot attach headers.""");

        return new OpenAPI()
                .info(new Info()
                        .title("Email Integrator API")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                Sends transactional email through an external provider (Brevo) and \
                                through Gmail SMTP for templated account-workflow messages.

                                **Reliability note.** Sending email is not idempotent and this \
                                service does not retry. A 502 or 504 response may indicate the \
                                message was delivered anyway — see the `deliveryUncertain` field \
                                on the error body before retrying. Details in docs/RELIABILITY.md.

                                This is a portfolio project. See the repository README for what is \
                                and is not production-ready.""")
                        .license(new License().name("See repository")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, apiKeyScheme))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
