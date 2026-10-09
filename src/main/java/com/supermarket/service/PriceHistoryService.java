package com.supermarket.service;

import com.supermarket.model.Cashier;
import com.supermarket.model.PriceChange;
import com.supermarket.repository.PriceChangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Keeps every change of a selling or purchase price, so the product card can show how a price moved. */
@Service
public class PriceHistoryService {

    private final PriceChangeRepository repository;

    public PriceHistoryService(PriceChangeRepository repository) {
        this.repository = repository;
    }

    /** Records the change if a price really changed. packageId is set for a box's own price. */
    @Transactional
    public void record(Long productId, Long packageId, PriceChange.Source source, Cashier actor,
                       BigDecimal oldPrice, BigDecimal newPrice, BigDecimal oldPurchasePrice, BigDecimal newPurchasePrice) {
        boolean priceChanged = !same(oldPrice, newPrice);
        boolean costChanged = !same(oldPurchasePrice, newPurchasePrice);
        if (!priceChanged && !costChanged) {
            return;
        }
        repository.save(new PriceChange(productId, packageId, actor, source,
                priceChanged ? oldPrice : null, priceChanged ? newPrice : null,
                costChanged ? oldPurchasePrice : null, costChanged ? newPurchasePrice : null, LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public List<PriceChange> forProduct(Long productId) {
        return repository.findForProduct(productId);
    }

    @Transactional(readOnly = true)
    public List<Long> productsWithNewPriceSince(LocalDateTime since) {
        return repository.productsWithNewPriceSince(since);
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
