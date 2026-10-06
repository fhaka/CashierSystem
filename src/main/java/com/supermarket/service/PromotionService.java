package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.Promotion;
import com.supermarket.repository.CategoryRepository;
import com.supermarket.repository.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/** Promotions are set up by managers and applied automatically by the till (see PricingService). */
@Service
public class PromotionService {

    /** What a manager fills in. Either productId/barcode or categoryId. daysOfWeek like "6,7" (Saturday, Sunday). */
    public record PromotionRequest(String name, String type, BigDecimal discountPercent, Integer buyQuantity,
                                   Integer freeQuantity, Long productId, String barcode, Long categoryId,
                                   LocalDate startsOn, LocalDate endsOn, String daysOfWeek, LocalTime startTime,
                                   LocalTime endTime, Boolean active) {
    }

    private final PromotionRepository promotionRepository;
    private final ProductService productService;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;

    public PromotionService(PromotionRepository promotionRepository, ProductService productService,
                            CategoryRepository categoryRepository, AuditService auditService) {
        this.promotionRepository = promotionRepository;
        this.productService = productService;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Promotion> findAll() {
        return promotionRepository.findAllByOrderByActiveDescCreatedAtDesc();
    }

    @Transactional
    public Promotion create(PromotionRequest request, Cashier actor) {
        Promotion promotion = new Promotion(LocalDateTime.now());
        apply(promotion, request);
        Promotion saved = promotionRepository.save(promotion);
        auditService.record(actor, "PROMOTION_CREATED", "PROMOTION", saved.getId(), describe(saved));
        return saved;
    }

    @Transactional
    public Promotion update(Long id, PromotionRequest request, Cashier actor) {
        Promotion promotion = promotionRepository.findById(id).orElseThrow(() -> new ValidationException("promotion.notFound", id));
        apply(promotion, request);
        auditService.record(actor, "PROMOTION_UPDATED", "PROMOTION", id, describe(promotion));
        return promotion;
    }

    private void apply(Promotion promotion, PromotionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ValidationException("promotion.nameRequired");
        }
        Promotion.Type type;
        try {
            type = Promotion.Type.valueOf(request.type() == null ? "" : request.type().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("promotion.invalidType");
        }
        promotion.setName(request.name().trim());
        promotion.setType(type);
        if (type == Promotion.Type.PERCENT) {
            if (request.discountPercent() == null || request.discountPercent().signum() <= 0
                    || request.discountPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new ValidationException("discount.invalidPercent");
            }
            promotion.setDiscountPercent(request.discountPercent());
            promotion.setBuyQuantity(null);
            promotion.setFreeQuantity(null);
        } else {
            if (request.buyQuantity() == null || request.buyQuantity() < 1 || request.freeQuantity() == null || request.freeQuantity() < 1) {
                throw new ValidationException("promotion.invalidQuantities");
            }
            promotion.setBuyQuantity(request.buyQuantity());
            promotion.setFreeQuantity(request.freeQuantity());
            promotion.setDiscountPercent(null);
        }

        boolean hasProduct = request.productId() != null || (request.barcode() != null && !request.barcode().isBlank());
        if (hasProduct == (request.categoryId() != null)) {
            throw new ValidationException("promotion.scopeRequired");
        }
        promotion.setProduct(hasProduct
                ? (request.productId() != null ? productService.findById(request.productId()) : productService.findByBarcode(request.barcode().trim()))
                : null);
        promotion.setCategory(request.categoryId() == null ? null : categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ValidationException("category.notFound", request.categoryId())));

        if (request.startsOn() != null && request.endsOn() != null && request.endsOn().isBefore(request.startsOn())) {
            throw new ValidationException("promotion.invalidDates");
        }
        if (request.startTime() != null && request.endTime() != null && !request.startTime().isBefore(request.endTime())) {
            throw new ValidationException("promotion.invalidHours");
        }
        promotion.setStartsOn(request.startsOn());
        promotion.setEndsOn(request.endsOn());
        promotion.setDaysOfWeek(normalizeDays(request.daysOfWeek()));
        promotion.setStartTime(request.startTime());
        promotion.setEndTime(request.endTime());
        promotion.setActive(request.active() == null || request.active());
    }

    /** "6, 7" -> "6,7"; only 1 (Monday) to 7 (Sunday). Empty: every day. */
    private static String normalizeDays(String days) {
        if (days == null || days.isBlank()) {
            return null;
        }
        List<String> values = Arrays.stream(days.split(",")).map(String::trim).filter(d -> !d.isEmpty()).distinct().sorted().toList();
        if (values.stream().anyMatch(d -> !d.matches("[1-7]"))) {
            throw new ValidationException("promotion.invalidDays");
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static String describe(Promotion p) {
        String what = p.getType() == Promotion.Type.PERCENT
                ? p.getDiscountPercent().stripTrailingZeros().toPlainString() + "%"
                : "buy " + p.getBuyQuantity() + " get " + p.getFreeQuantity();
        String scope = p.getProductName() != null ? p.getProductName() : "category " + p.getCategoryName();
        return p.getName() + ": " + what + " on " + scope + (p.isActive() ? "" : " (inactive)")
                + (p.getDaysOfWeek() == null ? "" : ", days " + p.getDaysOfWeek())
                + (p.getStartTime() == null ? "" : ", " + p.getStartTime() + "-" + (p.getEndTime() == null ? "" : p.getEndTime()));
    }
}
