package com.supermarket.service;

import com.supermarket.dto.ProductRequest;
import com.supermarket.exception.LocalizedException;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.repository.ProductRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Products to and from a CSV file that opens in Excel. Export uses ";" and a UTF-8 BOM so Albanian letters
 * show correctly. Import accepts ";" or "," as separator and "," or "." as decimal mark, matches products by
 * barcode (updates existing ones, creates new ones), and reports problem lines without stopping the rest.
 */
@Service
public class ProductCsvService {

    static final List<String> COLUMNS = List.of("barcode", "name", "category", "unit", "purchase_price", "price",
            "tax_rate", "stock", "min_stock", "reorder_quantity");

    public record ImportResult(int created, int updated, List<LineError> errors) {
    }

    public record LineError(int line, String barcode, String message) {
    }

    private final ProductRepository productRepository;
    private final ProductService productService;
    private final AuditService auditService;
    private final MessageSource messageSource;
    private final StockService stockService;

    public ProductCsvService(ProductRepository productRepository, ProductService productService, AuditService auditService,
                             MessageSource messageSource, StockService stockService) {
        this.productRepository = productRepository;
        this.productService = productService;
        this.auditService = auditService;
        this.messageSource = messageSource;
        this.stockService = stockService;
    }

    public String export() {
        StringBuilder csv = new StringBuilder("﻿").append(String.join(";", COLUMNS)).append(";active\r\n");
        productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> csv.append(String.join(";",
                        cell(p.getBarcode()), cell(p.getName()), cell(p.getCategory() == null ? "" : p.getCategory().getName()),
                        p.getUnit(), plain(p.getPurchasePrice()), plain(p.getPrice()), plain(p.getTaxRate()), plain(p.getStock()),
                        plain(p.getMinStock()), plain(p.getReorderQuantity()), String.valueOf(p.isActive()))).append("\r\n"));
        return csv.toString();
    }

    /** Each line is saved on its own, so one bad line does not block the others. */
    public ImportResult importCsv(String content, Cashier actor) {
        List<String> lines = content == null ? List.of() : content.replace("﻿", "").lines().toList();
        if (lines.isEmpty()) {
            return new ImportResult(0, 0, List.of(new LineError(1, null, text("import.empty"))));
        }
        char separator = lines.get(0).chars().filter(c -> c == ';').count() >= lines.get(0).chars().filter(c -> c == ',').count() ? ';' : ',';
        List<String> header = split(lines.get(0), separator).stream().map(h -> h.trim().toLowerCase()).toList();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i), i);
        }
        if (!index.containsKey("barcode") || !index.containsKey("name") || !index.containsKey("price")) {
            return new ImportResult(0, 0, List.of(new LineError(1, null, text("import.missingColumns"))));
        }

        int created = 0;
        int updated = 0;
        List<LineError> errors = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            if (lines.get(n).isBlank()) {
                continue;
            }
            List<String> cells = split(lines.get(n), separator);
            String barcode = value(cells, index, "barcode");
            try {
                if (barcode == null) {
                    throw new IllegalArgumentException(text("product.barcodeRequired"));
                }
                Optional<Product> existing = productRepository.findByBarcode(barcode);
                ProductRequest request = toRequest(cells, index, existing.orElse(null), separator);
                if (existing.isPresent()) {
                    productService.update(existing.get().getId(), request, actor);
                    // A different stock in the file counts as a stock count, kept in the product's stock history.
                    stockService.correctTo(existing.get().getId(), request.getStock(), "CSV import", actor);
                    updated++;
                } else {
                    productService.create(request, actor);
                    created++;
                }
            } catch (LocalizedException exception) {
                errors.add(new LineError(n + 1, barcode, text(exception.getCode(), exception.getArgs())));
            } catch (IllegalArgumentException exception) {
                errors.add(new LineError(n + 1, barcode, exception.getMessage()));
            }
        }
        auditService.record(actor, "PRODUCTS_IMPORTED", "PRODUCT", null,
                created + " created, " + updated + " updated, " + errors.size() + " lines with errors");
        return new ImportResult(created, updated, errors);
    }

    /** Columns left out (or empty) keep the product's current value; new products get sensible defaults. */
    private ProductRequest toRequest(List<String> cells, Map<String, Integer> index, Product current, char separator) {
        ProductRequest request = new ProductRequest();
        request.setBarcode(value(cells, index, "barcode"));
        request.setName(orElse(value(cells, index, "name"), current == null ? null : current.getName()));
        request.setCategoryName(orElse(value(cells, index, "category"),
                current == null || current.getCategory() == null ? null : current.getCategory().getName()));
        request.setUnit(orElse(value(cells, index, "unit"), current == null ? "pcs" : current.getUnit()));
        request.setPurchasePrice(number(cells, index, "purchase_price", separator, current == null ? BigDecimal.ZERO : current.getPurchasePrice()));
        request.setPrice(number(cells, index, "price", separator, current == null ? null : current.getPrice()));
        request.setTaxRate(number(cells, index, "tax_rate", separator, current == null ? BigDecimal.valueOf(20) : current.getTaxRate()));
        request.setStock(number(cells, index, "stock", separator, current == null ? BigDecimal.ZERO : current.getStock()));
        request.setMinStock(number(cells, index, "min_stock", separator, current == null ? null : current.getMinStock()));
        request.setReorderQuantity(number(cells, index, "reorder_quantity", separator, current == null ? null : current.getReorderQuantity()));
        return request;
    }

    private BigDecimal number(List<String> cells, Map<String, Integer> index, String column, char separator, BigDecimal fallback) {
        String raw = value(cells, index, column);
        if (raw == null) {
            return fallback;
        }
        // With ";" as separator Excel writes decimal commas (12,50); with "," it writes points (12.50).
        String normalized = raw.replace(" ", "");
        if (separator == ';') {
            normalized = normalized.contains(",") ? normalized.replace(".", "").replace(',', '.') : normalized;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(text("import.badNumber", column, raw));
        }
    }

    private static String value(List<String> cells, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String value = cells.get(i).trim();
        return value.isEmpty() ? null : value;
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** Splits one CSV line, honouring double quotes ("Djathë; i bardhë" stays one cell). */
    static List<String> split(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == separator && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(";") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String text(String code, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, args.length == 0 ? null : args, code, locale);
    }
}
