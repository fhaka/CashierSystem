package com.supermarket.service;

import com.supermarket.dto.OrderDtos.LineRequest;
import com.supermarket.dto.OrderDtos.LineRow;
import com.supermarket.dto.OrderDtos.OrderRequest;
import com.supermarket.dto.OrderDtos.OrderRow;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.NumberSequence;
import com.supermarket.model.Product;
import com.supermarket.model.ProductPackage;
import com.supermarket.model.PurchaseOrder;
import com.supermarket.model.PurchaseOrderLine;
import com.supermarket.model.Supplier;
import com.supermarket.repository.NumberSequenceRepository;
import com.supermarket.repository.ProductPackageRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.PurchaseOrderRepository;
import com.supermarket.repository.SupplierRepository;
import com.supermarket.util.Quantities;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orders to suppliers. An order lists what to ask a supplier for; it is sent by message (WhatsApp, Viber) or printed,
 * and closed as received when the goods came with the supplier's invoice, or cancelled. It never changes stock.
 */
@Service
public class PurchaseOrderService {

    static final String SEQUENCE = "PURCHASE_ORDER";

    private final PurchaseOrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductPackageRepository packageRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final AuditService auditService;

    public PurchaseOrderService(PurchaseOrderRepository orderRepository, SupplierRepository supplierRepository,
                                ProductRepository productRepository, ProductPackageRepository packageRepository,
                                NumberSequenceRepository numberSequenceRepository, AuditService auditService) {
        this.orderRepository = orderRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.packageRepository = packageRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.auditService = auditService;
    }

    /** status: OPEN, SENT, RECEIVED, CANCELLED, or ACTIVE for open and sent; empty for all. */
    @Transactional(readOnly = true)
    public List<OrderRow> list(String status) {
        List<PurchaseOrder> orders;
        if (status == null || status.isBlank()) {
            orders = orderRepository.findAllNewestFirst();
        } else if ("ACTIVE".equalsIgnoreCase(status.trim())) {
            orders = orderRepository.findByStatuses(EnumSet.of(PurchaseOrder.Status.OPEN, PurchaseOrder.Status.SENT));
        } else {
            orders = orderRepository.findByStatuses(EnumSet.of(parseStatus(status)));
        }
        Map<Long, ProductPackage> boxes = boxes();
        return orders.stream().map(o -> row(o, boxes)).toList();
    }

    @Transactional(readOnly = true)
    public OrderRow get(Long id) {
        return row(find(id), boxes());
    }

    @Transactional
    public OrderRow create(OrderRequest request, Cashier actor) {
        Supplier supplier = supplier(request.supplierId());
        List<PurchaseOrderLine> lines = lines(request.lines());
        NumberSequence sequence = numberSequenceRepository.findForUpdate(SEQUENCE)
                .orElseThrow(() -> new IllegalStateException("Purchase order number sequence is missing"));
        PurchaseOrder order = new PurchaseOrder(String.format("P%06d", sequence.next()), supplier, blankToNull(request.note()),
                actor, LocalDateTime.now());
        order.replaceLines(lines);
        PurchaseOrder saved = orderRepository.save(order);
        auditService.record(actor, "ORDER_CREATED", "PURCHASE_ORDER", saved.getNumber(),
                supplier.getName() + ", " + lines.size() + " lines");
        return row(saved, boxes());
    }

    /** Changes an order that is not closed yet (also after it was sent, when the supplier asks for a change). */
    @Transactional
    public OrderRow update(Long id, OrderRequest request, Cashier actor) {
        PurchaseOrder order = find(id);
        requireEditable(order);
        order.setSupplier(supplier(request.supplierId()));
        order.setNote(blankToNull(request.note()));
        order.replaceLines(lines(request.lines()));
        auditService.record(actor, "ORDER_CHANGED", "PURCHASE_ORDER", order.getNumber(), order.getLines().size() + " lines");
        return row(order, boxes());
    }

    @Transactional
    public OrderRow markSent(Long id, Cashier actor) {
        PurchaseOrder order = find(id);
        requireEditable(order);
        order.markSent(LocalDateTime.now());
        auditService.record(actor, "ORDER_SENT", "PURCHASE_ORDER", order.getNumber(), order.getSupplier().getName());
        return row(order, boxes());
    }

    @Transactional
    public OrderRow close(Long id, PurchaseOrder.Status outcome, Cashier actor) {
        PurchaseOrder order = find(id);
        requireEditable(order);
        order.close(outcome, actor, LocalDateTime.now());
        auditService.record(actor, outcome == PurchaseOrder.Status.RECEIVED ? "ORDER_RECEIVED" : "ORDER_CANCELLED",
                "PURCHASE_ORDER", order.getNumber(), order.getSupplier().getName());
        return row(order, boxes());
    }

    /** Pieces (or kg) of each product on orders still waiting for their goods, for the reorder list. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> onOrder() {
        Map<Long, ProductPackage> boxes = boxes();
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Object[] r : orderRepository.openLines()) {
            BigDecimal quantity = (BigDecimal) r[2];
            ProductPackage box = r[1] == null ? null : boxes.get((Long) r[1]);
            out.merge((Long) r[0], box == null ? quantity : quantity.multiply(BigDecimal.valueOf(box.getPieces())), BigDecimal::add);
        }
        return out;
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private List<PurchaseOrderLine> lines(List<LineRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException("order.linesRequired");
        }
        List<PurchaseOrderLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LineRequest r : requests) {
            if (r.productId() == null) {
                throw new ValidationException("order.productRequired");
            }
            Product product = productRepository.findById(r.productId())
                    .orElseThrow(() -> new ValidationException("order.productNotFound", r.productId()));
            ProductPackage box = null;
            if (r.packageId() != null) {
                box = packageRepository.findById(r.packageId())
                        .filter(b -> b.getProductId().equals(product.getId()))
                        .orElseThrow(() -> new ValidationException("package.notFound", r.packageId()));
            }
            if (!seen.add(product.getId() + "/" + (box == null ? "" : box.getId()))) {
                throw new ValidationException("order.duplicateLine", product.getName());
            }
            BigDecimal quantity = Quantities.requirePositive(r.quantity(), box == null ? product.getUnit() : Quantities.PIECES);
            BigDecimal price = r.price();
            if (price == null) {
                price = box == null ? product.getPurchasePrice() : product.getPurchasePrice().multiply(BigDecimal.valueOf(box.getPieces()));
            }
            if (price.signum() < 0) {
                throw new ValidationException("product.purchasePriceNegative");
            }
            lines.add(new PurchaseOrderLine(product, box == null ? null : box.getId(), quantity, price.setScale(2, RoundingMode.HALF_UP)));
        }
        return lines;
    }

    private OrderRow row(PurchaseOrder o, Map<Long, ProductPackage> boxes) {
        List<LineRow> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrderLine l : o.getLines()) {
            Product p = l.getProduct();
            ProductPackage box = l.getPackageId() == null ? null : boxes.get(l.getPackageId());
            BigDecimal lineTotal = l.getQuantity().multiply(l.getPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pieces = box == null ? l.getQuantity() : l.getQuantity().multiply(BigDecimal.valueOf(box.getPieces()));
            lines.add(new LineRow(p.getId(), p.getName(), p.getBarcode(), p.getUnit(), l.getPackageId(), box == null ? null : box.getName(),
                    box == null ? null : box.getPieces(), l.getQuantity().stripTrailingZeros(), pieces.stripTrailingZeros(), l.getPrice(),
                    lineTotal, p.getStock()));
            total = total.add(lineTotal);
        }
        Supplier s = o.getSupplier();
        return new OrderRow(o.getId(), o.getNumber(), o.getStatus().name(), s.getId(), s.getName(), s.getPhone(), o.getNote(),
                lines.size(), total, o.getCreatedBy().getFullName(), o.getCreatedAt(), o.getSentAt(),
                o.getClosedBy() == null ? null : o.getClosedBy().getFullName(), o.getClosedAt(), lines);
    }

    private Map<Long, ProductPackage> boxes() {
        Map<Long, ProductPackage> out = new HashMap<>();
        packageRepository.findAll().forEach(b -> out.put(b.getId(), b));
        return out;
    }

    private PurchaseOrder find(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new ValidationException("order.notFound", id));
    }

    private Supplier supplier(Long id) {
        if (id == null) {
            throw new ValidationException("order.supplierRequired");
        }
        return supplierRepository.findById(id).orElseThrow(() -> new ValidationException("supplier.notFound", id));
    }

    private static void requireEditable(PurchaseOrder order) {
        if (!order.isEditable()) {
            throw new ValidationException("order.closed", order.getNumber());
        }
    }

    private static PurchaseOrder.Status parseStatus(String status) {
        try {
            return PurchaseOrder.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("order.invalidStatus", status);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }
}
