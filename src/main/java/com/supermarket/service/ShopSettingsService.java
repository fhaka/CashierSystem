package com.supermarket.service;

import com.supermarket.dto.ShopSettings;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.ShopSetting;
import com.supermarket.repository.ShopSettingRepository;
import com.supermarket.util.ReceiptLayout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The shop's details (printed at the top of every receipt and report) and receipt options. Stored one row per
 * setting; anything not set yet uses the default, so a new installation works before it is configured.
 */
@Service
public class ShopSettingsService {

    public static final Set<Integer> RECEIPT_WIDTHS = Set.of(32, 42, 48);

    private final ShopSettingRepository repository;
    private final AuditService auditService;
    private final MessageSource messageSource;
    private final String defaultPrinter;

    public ShopSettingsService(ShopSettingRepository repository, AuditService auditService, MessageSource messageSource,
                               @Value("${receipt.printer.name:}") String defaultPrinter) {
        this.repository = repository;
        this.auditService = auditService;
        this.messageSource = messageSource;
        this.defaultPrinter = defaultPrinter;
    }

    @Transactional(readOnly = true)
    public ShopSettings get() {
        Map<String, String> v = repository.findAll().stream()
                .collect(Collectors.toMap(ShopSetting::getKey, ShopSetting::getValue));
        int width;
        try {
            width = Integer.parseInt(v.getOrDefault("receiptWidth", "32"));
        } catch (NumberFormatException exception) {
            width = 32;
        }
        return new ShopSettings(
                v.getOrDefault("name", "Supermarket"),
                v.getOrDefault("address", ""),
                v.getOrDefault("city", ""),
                v.getOrDefault("taxId", ""),
                v.getOrDefault("phone", ""),
                v.getOrDefault("email", ""),
                v.getOrDefault("receiptFooter", ""),
                RECEIPT_WIDTHS.contains(width) ? width : 32,
                Boolean.parseBoolean(v.getOrDefault("autoPrint", "false")),
                v.getOrDefault("receiptPrinter", defaultPrinter)
        );
    }

    @Transactional
    public ShopSettings update(ShopSettings settings, Cashier admin) {
        ShopSettings clean = validate(settings);
        Map<String, String> values = toMap(clean);
        Map<String, ShopSetting> existing = repository.findAll().stream()
                .collect(Collectors.toMap(ShopSetting::getKey, Function.identity()));
        values.forEach((key, value) -> {
            ShopSetting row = existing.get(key);
            if (row == null) {
                repository.save(new ShopSetting(key, value));
            } else {
                row.setValue(value);
            }
        });
        auditService.record(admin, "SETTINGS_CHANGED", "SHOP", null, clean.name());
        return clean;
    }

    /** Checks and tidies the values; throws with the first problem found. */
    public ShopSettings validate(ShopSettings s) {
        String name = trim(s.name());
        if (name.isEmpty()) {
            throw new ValidationException("settings.nameRequired");
        }
        if (!RECEIPT_WIDTHS.contains(s.receiptWidth())) {
            throw new ValidationException("settings.invalidWidth");
        }
        ShopSettings clean = new ShopSettings(name, trim(s.address()), trim(s.city()), trim(s.taxId()), trim(s.phone()),
                trim(s.email()), trim(s.receiptFooter()), s.receiptWidth(), s.autoPrint(), trim(s.receiptPrinter()));
        for (Map.Entry<String, String> entry : toMap(clean).entrySet()) {
            int limit = "receiptFooter".equals(entry.getKey()) ? 300 : 120;
            if (entry.getValue().length() > limit) {
                throw new ValidationException("settings.tooLong", entry.getKey(), limit);
            }
        }
        return clean;
    }

    /** Shop name, address, tax number and phone, centered, for the top of a receipt or report. */
    public ReceiptLayout header(ShopSettings shop) {
        ReceiptLayout layout = new ReceiptLayout(shop.receiptWidth());
        layout.center(shop.name().toUpperCase());
        String place = List.of(shop.address(), shop.city()).stream().filter(p -> !p.isBlank())
                .collect(Collectors.joining(", "));
        if (!place.isEmpty()) {
            layout.center(place);
        }
        if (!shop.taxId().isBlank()) {
            layout.center(text("receipt.taxId") + ": " + shop.taxId());
        }
        if (!shop.phone().isBlank()) {
            layout.center(text("receipt.phone") + ": " + shop.phone());
        }
        if (!shop.email().isBlank()) {
            layout.center(shop.email());
        }
        return layout.doubleRule();
    }

    public ReceiptLayout header() {
        return header(get());
    }

    /** A made-up receipt with the given settings, so the administrator sees the result before saving. */
    public String preview(ShopSettings settings) {
        ShopSettings shop = validate(settings);
        ReceiptLayout r = header(shop);
        r.center(text("receipt.title"));
        r.row(text("receipt.invoiceNumber"), "000123");
        r.rule();
        r.line(text("settings.sampleProduct"));
        r.row("  2 x 120.00", new BigDecimal("240.00"));
        r.rule();
        r.row(text("receipt.total") + " LEK", new BigDecimal("240.00"));
        r.rule();
        r.center(shop.receiptFooter().isBlank() ? text("receipt.thanks") : shop.receiptFooter());
        return r.toString();
    }

    private static Map<String, String> toMap(ShopSettings s) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", s.name());
        values.put("address", s.address());
        values.put("city", s.city());
        values.put("taxId", s.taxId());
        values.put("phone", s.phone());
        values.put("email", s.email());
        values.put("receiptFooter", s.receiptFooter());
        values.put("receiptWidth", String.valueOf(s.receiptWidth()));
        values.put("autoPrint", String.valueOf(s.autoPrint()));
        values.put("receiptPrinter", s.receiptPrinter());
        return values;
    }

    private String text(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
