package com.flashstock.product;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ProductDtos.ProductResponse create(@Valid @RequestBody ProductDtos.CreateRequest request) { return service.create(request); }
    @GetMapping("/{id}") public ProductDtos.ProductResponse get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/inventory") public ProductDtos.ProductResponse adjust(@PathVariable UUID id, @Valid @RequestBody ProductDtos.InventoryChange change) { return service.adjust(id, change); }
}
