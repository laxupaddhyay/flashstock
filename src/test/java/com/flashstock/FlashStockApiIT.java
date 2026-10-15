package com.flashstock;

import com.flashstock.security.CustomerPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FlashStockApiIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.username", () -> "test-user");
        registry.add("spring.rabbitmq.password", () -> "test-password");
        registry.add("flashstock.security.dev-password", () -> "integration-test-password");
        registry.add("flashstock.messaging.enabled", () -> "false");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void productReservationConfirmationAndOrderWorkThroughHttpApi() throws Exception {
        String product = mvc.perform(post("/api/v1/products").with(httpBasic("demo", "integration-test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API item","sku":"API-%s","price":12.50,"initialQuantity":5}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.availableQuantity").value(5))
                .andReturn().getResponse().getContentAsString();
        String productId = com.jayway.jsonpath.JsonPath.read(product, "$.id");

        String reservation = mvc.perform(post("/api/v1/reservations").with(httpBasic("demo", "integration-test-password"))
                        .header("Idempotency-Key", "api-flow-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String reservationId = com.jayway.jsonpath.JsonPath.read(reservation, "$.reservationId");

        String confirmation = mvc.perform(post("/api/v1/reservations/{id}/confirm", reservationId)
                        .with(httpBasic("demo", "integration-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();
        String orderId = com.jayway.jsonpath.JsonPath.read(confirmation, "$.order.id");
        mvc.perform(get("/api/v1/orders/{id}", orderId).with(httpBasic("demo", "integration-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(reservationId));
        mvc.perform(get("/api/v1/reservations/{id}", reservationId)
                        .with(user(new CustomerPrincipal(UUID.randomUUID(), "another-customer", "unused"))))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select total_quantity from inventory where product_id = ?", Long.class, UUID.fromString(productId))).isEqualTo(3);
    }

    @Test void protectedApiReturnsStructuredUnauthorizedProblemWithoutCredentials() throws Exception {
        mvc.perform(get("/api/v1/products/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
