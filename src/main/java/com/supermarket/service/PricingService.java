package com.supermarket.service;

import com.supermarket.model.CartItem;
import com.supermarket.model.Product;
import com.supermarket.model.Promotion;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.PromotionRepository;
import com.supermarket.strategy.PricingStrategy;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What each line of a cart costs after discounts, in this order:
 * <ol>
 *   <li>the best running promotion for the line's product or category (promotions do not stack);</li>
 *   <li>the manual discount on the whole cart, spread over the lines;</li>
 *   <li>the pricing strategies (the automatic large-purchase discount), spread over the lines.</li>
 * </ol>
 * Every discount ends up on a line, so the VAT of each line is calculated on what the customer pays.
 */
@Service
public class PricingService {

    /** One line: its full price, the promotion that applied, and the rest of the discount. */
    public record LinePrice(CartItem item, BigDecimal gross, Promotion promotion, BigDecimal promotionDiscount,
                            BigDecimal otherDiscount) {

        public BigDecimal discount() {
            return promotionDiscount.add(otherDiscount);
        }

        public BigDecimal total() {
            return gross.subtract(discount());
        }
    }

    public record CartPrice(List<LinePrice> lines, BigDecimal subtotal, BigDecimal promotionDiscount,
                            BigDecimal manualDiscountPercent, BigDecimal manualDiscount, BigDecimal otherDiscount,
                            BigDecimal total) {

        public BigDecimal discount() {
            return subtotal.subtract(total);
        }
    }

    private final PromotionRepository promotionRepository;
    private final ProductRepository productRepository;
    private final List<PricingStrategy> pricingStrategies;

    public PricingService(PromotionRepository promotionRepository, ProductRepository productRepository,
                          List<PricingStrategy> pricingStrategies) {
        this.promotionRepository = promotionRepository;
        this.productRepository = productRepository;
        this.pricingStrategies = pricingStrategies;
    }

    @Transactional(readOnly = true)
    public CartPrice price(List<CartItem> items, BigDecimal manualDiscountPercent, LocalDateTime now) {
        Map<Long, Product> products = productRepository.findAllById(items.stream().map(CartItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Promotion> running = promotionRepository.findByActiveTrue().stream().filter(p -> p.runsAt(now)).toList();

        List<BigDecimal> gross = new ArrayList<>();
        List<Promotion> applied = new ArrayList<>();
        List<BigDecimal> promoDiscounts = new ArrayList<>();
        for (CartItem item : items) {
            Product product = products.get(item.getProductId());
            Long categoryId = product == null || product.getCategory() == null ? null : product.getCategory().getId();
            Promotion best = null;
            BigDecimal bestDiscount = BigDecimal.ZERO;
            for (Promotion promotion : running) {
                if (promotion.covers(item.getProductId(), categoryId)) {
                    BigDecimal discount = discountFor(promotion, item);
                    if (discount.compareTo(bestDiscount) > 0) {
                        best = promotion;
                        bestDiscount = discount;
                    }
                }
            }
            gross.add(item.getLineTotal());
            applied.add(best);
            promoDiscounts.add(bestDiscount);
        }

        BigDecimal subtotal = sum(gross);
        BigDecimal promotionDiscount = sum(promoDiscounts);
        List<BigDecimal> afterPromotions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            afterPromotions.add(gross.get(i).subtract(promoDiscounts.get(i)));
        }

        BigDecimal percent = manualDiscountPercent == null ? BigDecimal.ZERO : manualDiscountPercent;
        BigDecimal manualDiscount = sum(afterPromotions).multiply(percent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> manualShares = split(afterPromotions, manualDiscount);

        List<BigDecimal> afterManual = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            afterManual.add(afterPromotions.get(i).subtract(manualShares.get(i)));
        }
        BigDecimal base = sum(afterManual);
        BigDecimal total = base;
        for (PricingStrategy strategy : pricingStrategies) {
            total = strategy.apply(items, total);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> strategyShares = split(afterManual, base.subtract(total));

        List<LinePrice> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            lines.add(new LinePrice(items.get(i), gross.get(i), applied.get(i), promoDiscounts.get(i),
                    manualShares.get(i).add(strategyShares.get(i))));
        }
        return new CartPrice(lines, subtotal, promotionDiscount, percent.signum() == 0 ? null : percent,
                manualDiscount, base.subtract(total), total);
    }

    /** What a promotion takes off one cart line. "Buy X get Y" only works on single pieces, not on boxes. */
    static BigDecimal discountFor(Promotion promotion, CartItem item) {
        BigDecimal gross = item.getLineTotal();
        if (promotion.getType() == Promotion.Type.PERCENT) {
            return gross.multiply(promotion.getDiscountPercent()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).min(gross);
        }
        if (Quantities.KILOGRAMS.equals(item.getUnit()) || item.getPackageId() != null) {
            return BigDecimal.ZERO;
        }
        int groupSize = promotion.getBuyQuantity() + promotion.getFreeQuantity();
        int freePieces = (item.getQuantity().intValue() / groupSize) * promotion.getFreeQuantity();
        return item.getPrice().multiply(BigDecimal.valueOf(freePieces)).setScale(2, RoundingMode.HALF_UP).min(gross);
    }

    /** Spreads an amount over lines in proportion to their value; the last line takes the rounding remainder. */
    static List<BigDecimal> split(List<BigDecimal> amounts, BigDecimal discount) {
        BigDecimal total = sum(amounts);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal share;
            if (discount.signum() == 0 || total.signum() == 0) {
                share = BigDecimal.ZERO.setScale(2);
            } else if (i == amounts.size() - 1) {
                share = discount.subtract(assigned);
            } else {
                share = discount.multiply(amounts.get(i)).divide(total, 2, RoundingMode.HALF_UP);
            }
            assigned = assigned.add(share);
            shares.add(share);
        }
        return shares;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
