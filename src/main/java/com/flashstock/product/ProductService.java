package com.flashstock.product;

import com.flashstock.api.ApiException;
import com.flashstock.inventory.InventoryEntity;
import com.flashstock.inventory.InventoryRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ProductService {
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final ProductMetadataCache cache;
    public ProductService(ProductRepository products, InventoryRepository inventory, ProductMetadataCache cache) { this.products=products; this.inventory=inventory; this.cache=cache; }

    @Transactional
    public ProductDtos.ProductResponse create(ProductDtos.CreateRequest request) {
        if (products.findBySku(request.sku()).isPresent()) throw ApiException.conflict("SKU_ALREADY_EXISTS", "A product with this SKU already exists");
        UUID id = UUID.randomUUID();
        ProductEntity product = products.saveAndFlush(new ProductEntity(id, request.name().trim(), request.sku().trim(), request.price()));
        inventory.saveAndFlush(new InventoryEntity(id, request.initialQuantity()));
        return response(product, inventory.findById(id).orElseThrow());
    }

    public ProductDtos.ProductResponse get(UUID id) {
        ProductMetadataCache.Metadata product = cache.get(id, () -> products.findById(id).orElseThrow(() -> ApiException.notFound("Product")));
        InventoryEntity stock = inventory.findById(id).orElseThrow(() -> ApiException.notFound("Inventory"));
        return response(product, stock);
    }

    @Transactional
    public ProductDtos.ProductResponse adjust(UUID id, ProductDtos.InventoryChange change) {
        ProductEntity product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        int updated = change.direction() == ProductDtos.Direction.INCREASE
                ? inventory.increase(id, change.quantity()) : inventory.decrease(id, change.quantity());
        if (updated == 0) throw ApiException.conflict("INVALID_INVENTORY_ADJUSTMENT", "Adjustment would make total stock negative or lower than reserved stock");
        return response(product, inventory.findById(id).orElseThrow());
    }

    private ProductDtos.ProductResponse response(ProductEntity product, InventoryEntity stock) {
        return new ProductDtos.ProductResponse(product.getId(), product.getName(), product.getSku(), product.getPrice(),
                stock.getTotalQuantity(), stock.getReservedQuantity(), stock.getAvailableQuantity(), product.getCreatedAt());
    }

    private ProductDtos.ProductResponse response(ProductMetadataCache.Metadata product, InventoryEntity stock) {
        return new ProductDtos.ProductResponse(product.id(), product.name(), product.sku(), product.price(),
                stock.getTotalQuantity(), stock.getReservedQuantity(), stock.getAvailableQuantity(), product.createdAt());
    }
}
