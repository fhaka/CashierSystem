package com.supermarket.service;

import com.supermarket.dto.ReturnDtos.LineRequest;
import com.supermarket.dto.ReturnDtos.LineRow;
import com.supermarket.dto.ReturnDtos.ReturnRequest;
import com.supermarket.dto.ReturnDtos.ReturnRow;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.NumberSequence;
import com.supermarket.model.Product;
import com.supermarket.model.StockAdjustment;
import com.supermarket.model.Supplier;
import com.supermarket.model.SupplierReturn;
import com.supermarket.model.SupplierReturnLine;
import com.supermarket.repository.NumberSequenceRepository;
import com.supermarket.repository.SupplierRepository;
import com.supermarket.repository.SupplierReturnRepository;
import com.supermarket.util.Quantities;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Returns to suppliers. The goods leave stock when the return is made. A CREDIT return lowers what the shop owes the
 * supplier; a REPLACEMENT return waits until the same goods come back, which puts them back in stock.
 */
@Service
public class SupplierReturnService {

    static final String SEQUENCE = "SUPPLIER_RETURN";

    private final SupplierReturnRepository returnRepository;
    private final SupplierRepository supplierRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final StockService stockService;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public SupplierReturnService(SupplierReturnRepository returnRepository, SupplierRepository supplierRepository,
                                 NumberSequenceRepository numberSequenceRepository, StockService stockService,
                                 AuditService auditService, EntityManager entityManager) {
        this.returnRepository = returnRepository;
        this.supplierRepository = supplierRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.stockService = stockService;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    /** pendingOnly: only replacement returns still waiting for their goods. */
    @Transactional(readOnly = true)
    public List<ReturnRow> list(boolean pendingOnly) {
        return (pendingOnly ? returnRepository.findPending() : returnRepository.findAllNewestFirst()).stream().map(SupplierReturnService::row).toList();
    }

    @Transactional(readOnly = true)
    public List<ReturnRow> forSupplier(Long supplierId) {
        return returnRepository.findForSupplier(supplierId).stream().map(SupplierReturnService::row).toList();
    }

    @Transactional(readOnly = true)
    public ReturnRow get(Long id) {
        return row(find(id));
    }

    @Transactional
    public ReturnRow create(ReturnRequest request, Cashier actor) {
        if (request.supplierId() == null) {
            throw new ValidationException("return.supplierRequired");
        }
        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ValidationException("supplier.notFound", request.supplierId()));
        SupplierReturn.Kind kind = parse(SupplierReturn.Kind.class, request.kind(), "return.invalidKind");
        SupplierReturn.Reason reason = parse(SupplierReturn.Reason.class, request.reason(), "return.invalidReason");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 255) {
            note = note.substring(0, 255);
        }
        if (reason == SupplierReturn.Reason.OTHER && note == null) {
            throw new ValidationException("stock.noteRequired");
        }
        List<LineRequest> lines = request.lines();
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("return.linesRequired");
        }
        Set<Long> seen = new HashSet<>();
        for (LineRequest line : lines) {
            if (line.productId() == null) {
                throw new ValidationException("order.productRequired");
            }
            if (!seen.add(line.productId())) {
                throw new ValidationException("return.duplicateLine", line.productId());
            }
        }

        NumberSequence sequence = numberSequenceRepository.findForUpdate(SEQUENCE)
                .orElseThrow(() -> new IllegalStateException("Supplier return number sequence is missing"));
        SupplierReturn supplierReturn = new SupplierReturn(String.format("K%06d", sequence.next()), supplier, kind, reason, note,
                actor, LocalDateTime.now());
        // Lock the products in one order, like a sale, so two tills cannot take the same last pieces.
        for (LineRequest line : lines.stream().sorted(Comparator.comparing(LineRequest::productId)).toList()) {
            Product product = stockService.lock(line.productId());
            BigDecimal quantity = Quantities.requirePositive(line.quantity(), product.getUnit());
            BigDecimal price = line.price() != null ? line.price() : lastPrice(product, supplier);
            if (price.signum() < 0) {
                throw new ValidationException("product.purchasePriceNegative");
            }
            price = price.setScale(2, RoundingMode.HALF_UP);
            stockService.apply(product, quantity.negate(), StockAdjustment.Reason.SUPPLIER_RETURN,
                    supplierReturn.getNumber() + " · " + supplier.getName(), null, actor);
            supplierReturn.addLine(new SupplierReturnLine(product, quantity, price, product.getTaxRate(),
                    price.multiply(quantity).setScale(2, RoundingMode.HALF_UP)));
        }
        SupplierReturn saved = returnRepository.save(supplierReturn);
        auditService.record(actor, "SUPPLIER_RETURN", "SUPPLIER_RETURN", saved.getNumber(), supplier.getName() + ", " + kind + " "
                + reason + ", " + saved.getLines().size() + " lines, " + saved.getTotalAmount() + " LEK");
        return row(saved);
    }

    /** The same goods came back for a replacement return: they go back into stock. */
    @Transactional
    public ReturnRow replaced(Long id, Cashier actor) {
        SupplierReturn supplierReturn = find(id);
        if (supplierReturn.getStatus() != SupplierReturn.Status.PENDING) {
            throw new ValidationException("return.notPending", supplierReturn.getNumber());
        }
        for (SupplierReturnLine line : supplierReturn.getLines().stream()
                .sorted(Comparator.comparing((SupplierReturnLine l) -> l.getProduct().getId())).toList()) {
            Product product = stockService.lock(line.getProduct().getId());
            stockService.apply(product, line.getQuantity(), StockAdjustment.Reason.REPLACEMENT,
                    supplierReturn.getNumber() + " · " + supplierReturn.getSupplier().getName(), null, actor);
        }
        supplierReturn.markReplaced(actor, LocalDateTime.now());
        auditService.record(actor, "SUPPLIER_REPLACEMENT", "SUPPLIER_RETURN", supplierReturn.getNumber(),
                supplierReturn.getSupplier().getName());
        return row(supplierReturn);
    }

    /** The price of the product on the last invoice of this supplier, else its average cost. */
    BigDecimal lastPrice(Product product, Supplier supplier) {
        List<BigDecimal> prices = entityManager.createQuery("""
                        select pi.purchasePrice from PurchaseItem pi join pi.invoice inv
                        where pi.product.id = :productId and inv.supplier.id = :supplierId
                        order by inv.invoiceDate desc, pi.id desc""", BigDecimal.class)
                .setParameter("productId", product.getId()).setParameter("supplierId", supplier.getId())
                .setMaxResults(1).getResultList();
        return prices.isEmpty() ? product.getPurchasePrice() : prices.get(0);
    }

    static ReturnRow row(SupplierReturn r) {
        List<LineRow> lines = r.getLines().stream().map(l -> new LineRow(l.getProduct().getId(), l.getProduct().getName(),
                l.getProduct().getBarcode(), l.getProduct().getUnit(), l.getQuantity().stripTrailingZeros(), l.getPrice(), l.getTaxRate(),
                l.getLineTotal())).toList();
        return new ReturnRow(r.getId(), r.getNumber(), r.getSupplier().getId(), r.getSupplier().getName(), r.getKind().name(),
                r.getStatus().name(), r.getReason().name(), r.getNote(), r.getTotalAmount(), r.getCreatedBy().getFullName(),
                r.getCreatedAt(), r.getReplacedBy() == null ? null : r.getReplacedBy().getFullName(), r.getReplacedAt(), lines);
    }

    private SupplierReturn find(Long id) {
        return returnRepository.findById(id).orElseThrow(() -> new ValidationException("return.notFound", id));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String error) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(error, value);
        }
    }
}
