package com.supermarket.service;

import com.supermarket.dto.InventoryDtos.CountLineRequest;
import com.supermarket.dto.InventoryDtos.CountLineView;
import com.supermarket.dto.InventoryDtos.CountView;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.InventoryCount;
import com.supermarket.model.InventoryCountLine;
import com.supermarket.model.Product;
import com.supermarket.model.StockAdjustment;
import com.supermarket.repository.InventoryCountRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stock-taking. One count is open at a time; products are scanned and their shelf quantity typed in
 * (counting a product again replaces the number). Applying the count sets the stock of every counted
 * product to what was found, recording each difference as a COUNT_CORRECTION adjustment. Products that
 * were not counted are left as they are.
 */
@Service
public class InventoryCountService {

    private final InventoryCountRepository countRepository;
    private final ProductService productService;
    private final StockService stockService;
    private final AuditService auditService;

    public InventoryCountService(InventoryCountRepository countRepository, ProductService productService,
                                 StockService stockService, AuditService auditService) {
        this.countRepository = countRepository;
        this.productService = productService;
        this.stockService = stockService;
        this.auditService = auditService;
    }

    @Transactional
    public CountView start(Cashier actor, String note) {
        if (countRepository.findFirstByStatusOrderByIdDesc(InventoryCount.Status.OPEN).isPresent()) {
            throw new ValidationException("count.alreadyOpen");
        }
        InventoryCount count = countRepository.save(new InventoryCount(actor,
                note == null || note.isBlank() ? null : note.trim(), LocalDateTime.now()));
        auditService.record(actor, "COUNT_STARTED", "INVENTORY_COUNT", count.getId(), count.getNote());
        return view(count);
    }

    @Transactional(readOnly = true)
    public CountView current() {
        return countRepository.findFirstByStatusOrderByIdDesc(InventoryCount.Status.OPEN).map(this::view).orElse(null);
    }

    @Transactional(readOnly = true)
    public CountView find(Long id) {
        return view(load(id));
    }

    @Transactional
    public CountView record(Long countId, CountLineRequest request, Cashier actor) {
        InventoryCount count = requireOpen(countId);
        Product product = request.productId() != null
                ? productService.findById(request.productId())
                : productService.findByBarcode(request.barcode() == null ? "" : request.barcode().trim());
        BigDecimal counted = Quantities.requireStock(request.countedQuantity(), product.getUnit());
        LocalDateTime now = LocalDateTime.now();
        count.findLine(product.getId()).ifPresentOrElse(
                line -> line.recount(counted, actor, now),
                () -> count.addLine(new InventoryCountLine(product, counted, actor, now)));
        countRepository.flush();
        return view(count);
    }

    @Transactional
    public CountView apply(Long countId, Cashier actor) {
        InventoryCount count = requireOpen(countId);
        int changed = 0;
        for (InventoryCountLine line : count.getLines()) {
            Product product = stockService.lock(line.getProduct().getId());
            BigDecimal difference = line.getCountedQuantity().subtract(product.getStock());
            if (difference.signum() != 0) {
                stockService.apply(product, difference, StockAdjustment.Reason.COUNT_CORRECTION, null, count.getId(), actor);
                changed++;
            }
        }
        count.finish(InventoryCount.Status.APPLIED, actor, LocalDateTime.now());
        auditService.record(actor, "COUNT_APPLIED", "INVENTORY_COUNT", count.getId(),
                count.getLines().size() + " products counted, " + changed + " corrected");
        return view(count);
    }

    @Transactional
    public CountView cancel(Long countId, Cashier actor) {
        InventoryCount count = requireOpen(countId);
        count.finish(InventoryCount.Status.CANCELLED, actor, LocalDateTime.now());
        auditService.record(actor, "COUNT_CANCELLED", "INVENTORY_COUNT", count.getId(), count.getLines().size() + " products counted");
        return view(count);
    }

    private CountView view(InventoryCount count) {
        boolean open = count.getStatus() == InventoryCount.Status.OPEN;
        List<CountLineView> lines = count.getLines().stream().map(line -> {
            Product product = line.getProduct();
            // While the count is open the difference is against today's stock; afterwards it is history.
            BigDecimal stock = product.getStock();
            BigDecimal difference = open ? line.getCountedQuantity().subtract(stock) : null;
            return new CountLineView(product.getId(), product.getName(), product.getBarcode(), product.getUnit(),
                    line.getCountedQuantity(), open ? stock : null, difference, line.getCountedAt());
        }).toList();
        int withDifference = (int) lines.stream().filter(l -> l.difference() != null && l.difference().signum() != 0).count();
        return new CountView(count.getId(), count.getStatus().name(), count.getNote(), count.getStartedAt(),
                count.getStartedByName(), count.getFinishedAt(), count.getFinishedByName(), lines, withDifference);
    }

    private InventoryCount load(Long id) {
        return countRepository.findById(id).orElseThrow(() -> new ValidationException("count.notFound", id));
    }

    private InventoryCount requireOpen(Long id) {
        InventoryCount count = load(id);
        if (count.getStatus() != InventoryCount.Status.OPEN) {
            throw new ValidationException("count.notOpen");
        }
        return count;
    }
}
