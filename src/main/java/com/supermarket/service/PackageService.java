package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.PriceChange;
import com.supermarket.model.Product;
import com.supermarket.model.ProductPackage;
import com.supermarket.repository.ProductPackageRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Boxes of a product. Only products sold by the piece can have boxes. A barcode belongs to one product or one
 * box, never to two. Boxes are deactivated, not deleted: old sales and purchases point at them.
 */
@Service
public class PackageService {

    /** What a box looks like when it is created or changed. price null = pieces x the piece price. */
    public record PackageRequest(String barcode, String name, Integer pieces, BigDecimal price, Boolean active) {
    }

    private final ProductPackageRepository packageRepository;
    private final ProductRepository productRepository;
    private final PriceHistoryService priceHistoryService;
    private final AuditService auditService;

    public PackageService(ProductPackageRepository packageRepository, ProductRepository productRepository,
                          PriceHistoryService priceHistoryService, AuditService auditService) {
        this.packageRepository = packageRepository;
        this.productRepository = productRepository;
        this.priceHistoryService = priceHistoryService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<ProductPackage> forProduct(Long productId) {
        return packageRepository.findForProduct(productId);
    }

    @Transactional(readOnly = true)
    public Optional<ProductPackage> findByBarcode(String barcode) {
        return packageRepository.findByBarcode(barcode);
    }

    public ProductPackage find(Long id) {
        return packageRepository.findById(id).orElseThrow(() -> new ValidationException("package.notFound", id));
    }

    @Transactional
    public ProductPackage create(Long productId, PackageRequest request, Cashier actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ValidationException("product.notFound", productId));
        if (!Quantities.PIECES.equals(product.getUnit())) {
            throw new ValidationException("package.onlyPieces", product.getName());
        }
        String barcode = requireFreeBarcode(request.barcode(), null);
        ProductPackage box = packageRepository.save(new ProductPackage(product, barcode, requireName(request.name()),
                requirePieces(request.pieces()), requirePrice(request.price())));
        priceHistoryService.record(product.getId(), box.getId(), PriceChange.Source.EDIT, actor, null, box.getPrice(), null, null);
        auditService.record(actor, "PACKAGE_CREATED", "PRODUCT", product.getId(),
                product.getName() + ": " + box.getName() + " [" + box.getBarcode() + "] = " + box.getPieces() + " pcs"
                        + (box.getPrice() == null ? "" : ", price " + box.getPrice()));
        return box;
    }

    @Transactional
    public ProductPackage update(Long id, PackageRequest request, Cashier actor) {
        ProductPackage box = find(id);
        String before = summary(box);
        BigDecimal oldPrice = box.getPrice();
        box.setBarcode(requireFreeBarcode(request.barcode(), id));
        box.setName(requireName(request.name()));
        box.setPieces(requirePieces(request.pieces()));
        box.setPrice(requirePrice(request.price()));
        if (request.active() != null) {
            box.setActive(request.active());
        }
        priceHistoryService.record(box.getProductId(), box.getId(), PriceChange.Source.EDIT, actor, oldPrice, box.getPrice(), null, null);
        String after = summary(box);
        if (!before.equals(after)) {
            auditService.record(actor, "PACKAGE_UPDATED", "PRODUCT", box.getProductId(), before + " -> " + after);
        }
        return box;
    }

    private String requireFreeBarcode(String barcode, Long ownPackageId) {
        if (barcode == null || barcode.isBlank()) {
            throw new ValidationException("product.barcodeRequired");
        }
        String code = barcode.trim();
        productRepository.findByBarcode(code).ifPresent(product -> {
            throw new ValidationException("product.barcodeExists", product.getName());
        });
        packageRepository.findByBarcode(code).filter(other -> !other.getId().equals(ownPackageId)).ifPresent(other -> {
            throw new ValidationException("product.barcodeIsPackage", other.getName(), other.getProduct().getName());
        });
        return code;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("package.nameRequired");
        }
        return name.trim();
    }

    private static int requirePieces(Integer pieces) {
        if (pieces == null || pieces < 2 || pieces > 10000) {
            throw new ValidationException("package.invalidPieces");
        }
        return pieces;
    }

    private static BigDecimal requirePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.signum() <= 0) {
            throw new ValidationException("product.pricePositive");
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private static String summary(ProductPackage box) {
        return box.getName() + " [" + box.getBarcode() + "] = " + box.getPieces() + " pcs, price "
                + (box.getPrice() == null ? "auto" : box.getPrice()) + (box.isActive() ? "" : ", inactive");
    }
}
