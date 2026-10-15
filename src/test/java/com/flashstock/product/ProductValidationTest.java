package com.flashstock.product;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ProductValidationTest {
    private static Validator validator;
    private static AutoCloseable factory;
    @BeforeAll static void createValidator() {
        var validationFactory = Validation.buildDefaultValidatorFactory();
        validator = validationFactory.getValidator();
        factory = validationFactory::close;
    }
    @AfterAll static void closeValidator() throws Exception { factory.close(); }

    @Test void rejectsBlankSkuNegativePriceAndNegativeInitialStock() {
        var invalid = new ProductDtos.CreateRequest(" ", " ", new BigDecimal("-1.00"), -3L);
        var violations = validator.validate(invalid);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("name", "sku", "price", "initialQuantity");
    }

    @Test void acceptsZeroStockAndNonNegativeDecimalPrice() {
        var valid = new ProductDtos.CreateRequest("Widget", "SKU-1", new BigDecimal("0.0000"), 0L);
        assertThat(validator.validate(valid)).isEmpty();
    }
}
