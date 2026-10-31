package com.supermarket.controller;

import com.supermarket.dto.Accounting;
import com.supermarket.dto.Accounting.Line;
import com.supermarket.dto.Accounting.ProfitAndLoss;
import com.supermarket.dto.Accounting.RateAmount;
import com.supermarket.dto.ApiResponse;
import com.supermarket.service.AccountingService;
import com.supermarket.service.ExportService;
import com.supermarket.service.ExportService.SheetData;
import com.supermarket.service.SessionService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Kontabiliteti: profit and loss, sales and purchase books and stock value of a month, for managers and the accountant. */
@RestController
@RequestMapping("/accounting")
public class AccountingController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AccountingService accountingService;
    private final SessionService sessionService;
    private final ExportService exportService;
    private final MessageSource messageSource;

    public AccountingController(AccountingService accountingService, SessionService sessionService, ExportService exportService,
                                MessageSource messageSource) {
        this.accountingService = accountingService;
        this.sessionService = sessionService;
        this.exportService = exportService;
        this.messageSource = messageSource;
    }

    /** month: yyyy-MM, by default this month. */
    @GetMapping
    public ApiResponse<Accounting.Month> month(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                               @RequestParam(required = false) String month) {
        sessionService.requireOperationalManager(token);
        return ApiResponse.ok("Accounting", accountingService.month(AccountingService.parseMonth(month)));
    }

    /** The whole month in one workbook: profit and loss, sales book (days and documents), purchase book, stock. */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> excel(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                        @RequestParam(required = false) String month) {
        sessionService.requireOperationalManager(token);
        YearMonth ym = AccountingService.parseMonth(month);
        Accounting.Month m = accountingService.month(ym);
        List<SheetData> sheets = List.of(profitAndLossSheet(m), salesDaysSheet(m.sales()), salesDocumentsSheet(m.sales()),
                purchaseSheet(m.purchases()), stockSheet(m.stockAtEnd()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"kontabiliteti-" + ym + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(exportService.sheetsXlsx(sheets));
    }

    private SheetData profitAndLossSheet(Accounting.Month m) {
        ProfitAndLoss c = m.profitAndLoss().current();
        ProfitAndLoss p = m.profitAndLoss().previous();
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {text("accounting.grossSales"), c.grossSales(), p.grossSales()});
        rows.add(new Object[] {text("accounting.refunds"), c.refunds().negate(), p.refunds().negate()});
        rows.add(new Object[] {text("accounting.revenue"), c.revenue(), p.revenue()});
        rows.add(new Object[] {text("accounting.vat"), c.vat().negate(), p.vat().negate()});
        rows.add(new Object[] {text("accounting.revenueWithoutVat"), c.revenueWithoutVat(), p.revenueWithoutVat()});
        rows.add(new Object[] {text("accounting.costOfGoods"), c.costOfGoods().negate(), p.costOfGoods().negate()});
        rows.add(new Object[] {text("accounting.grossProfit"), c.grossProfit(), p.grossProfit()});
        for (Line line : c.stockLosses()) {
            rows.add(new Object[] {"  " + text("accounting.loss." + line.key()), line.amount().negate(), amountOf(p.stockLosses(), line.key())});
        }
        rows.add(new Object[] {text("accounting.stockLosses"), c.stockLossTotal().negate(), p.stockLossTotal().negate()});
        for (Line line : c.expenses()) {
            rows.add(new Object[] {"  " + line.label(), line.amount().negate(), amountOf(p.expenses(), line.key())});
        }
        rows.add(new Object[] {text("accounting.expenses"), c.expenseTotal().negate(), p.expenseTotal().negate()});
        rows.add(new Object[] {text("accounting.netProfit"), c.netProfit(), p.netProfit()});
        rows.add(new Object[] {text("accounting.netMargin"), c.netMargin(), p.netMargin()});
        rows.add(new Object[] {});
        rows.add(new Object[] {text("accounting.stockAtStart"), m.stockAtStart().valueWithoutVat(), null});
        rows.add(new Object[] {text("accounting.purchases"), m.purchases().base(), null});
        rows.add(new Object[] {text("accounting.openingStock"), m.openingStock(), null});
        rows.add(new Object[] {text("accounting.stockAtEnd"), m.stockAtEnd().valueWithoutVat(), null});
        return new SheetData(text("accounting.sheet.profitAndLoss"),
                List.of("", c.from().format(DATE) + " - " + c.to().format(DATE), p.from().format(DATE) + " - " + p.to().format(DATE)), rows);
    }

    private SheetData salesDaysSheet(Accounting.SalesBook book) {
        List<String> header = new ArrayList<>(List.of(text("export.date"), text("accounting.receipts"), text("accounting.fromInvoice"),
                text("accounting.toInvoice"), text("export.refunds")));
        header.addAll(rateHeader(book.rates()));
        List<Object[]> rows = new ArrayList<>();
        for (Accounting.SalesDay d : book.days()) {
            List<Object> row = new ArrayList<>(List.of(d.day().format(DATE), d.receipts(), d.firstInvoice() == null ? "" : d.firstInvoice(),
                    d.lastInvoice() == null ? "" : d.lastInvoice(), d.refunds()));
            addRates(row, d.rates(), d.base(), d.vat(), d.total());
            rows.add(row.toArray());
        }
        List<Object> total = new ArrayList<>(List.of(text("export.total"), "", "", "", ""));
        addRates(total, book.totals(), book.base(), book.vat(), book.total());
        rows.add(total.toArray());
        return new SheetData(text("accounting.sheet.salesBook"), header, rows);
    }

    private SheetData salesDocumentsSheet(Accounting.SalesBook book) {
        List<String> header = new ArrayList<>(List.of(text("export.date"), text("export.document"), text("export.type"),
                text("export.customer"), text("accounting.forInvoice")));
        header.addAll(rateHeader(book.rates()));
        List<Object[]> rows = new ArrayList<>();
        for (Accounting.SalesDocument d : book.documents()) {
            List<Object> row = new ArrayList<>();
            row.add(d.at().format(DATE_TIME));
            row.add(d.number());
            row.add(text("accounting.document." + d.kind()));
            row.add(d.customer());
            row.add(d.forInvoice());
            addRates(row, d.rates(), d.base(), d.vat(), d.total());
            rows.add(row.toArray());
        }
        return new SheetData(text("accounting.sheet.salesDocuments"), header, rows);
    }

    private SheetData purchaseSheet(Accounting.PurchaseBook book) {
        List<String> header = new ArrayList<>(List.of(text("export.date"), text("export.invoice"), text("export.supplier"),
                text("accounting.taxNumber")));
        header.addAll(rateHeader(book.rates()));
        List<Object[]> rows = new ArrayList<>();
        for (Accounting.PurchaseRow r : book.invoices()) {
            List<Object> row = new ArrayList<>();
            row.add(r.date().format(DATE));
            row.add(r.invoice());
            row.add(r.supplier());
            row.add(r.taxNumber());
            addRates(row, r.rates(), r.base(), r.vat(), r.total());
            rows.add(row.toArray());
        }
        List<Object> total = new ArrayList<>(List.of(text("export.total"), "", "", ""));
        addRates(total, book.totals(), book.base(), book.vat(), book.total());
        rows.add(total.toArray());
        return new SheetData(text("accounting.sheet.purchaseBook"), header, rows);
    }

    private SheetData stockSheet(Accounting.StockValue stock) {
        List<Object[]> rows = new ArrayList<>(stock.products().stream()
                .map(p -> new Object[] {p.barcode(), p.name(), p.category(), p.unit(), p.stock(), p.cost(), p.value(), p.valueWithoutVat()})
                .toList());
        rows.add(new Object[] {text("export.total"), "", "", "", null, null, stock.value(), stock.valueWithoutVat()});
        return new SheetData(text("accounting.sheet.stock") + " " + stock.at().format(DATE),
                List.of(text("export.barcode"), text("export.product"), text("export.category"), text("accounting.unit"),
                        text("export.quantity"), text("accounting.cost"), text("accounting.value"), text("accounting.valueWithoutVat")), rows);
    }

    private List<String> rateHeader(List<BigDecimal> rates) {
        List<String> header = new ArrayList<>();
        for (BigDecimal rate : rates) {
            String r = rate.stripTrailingZeros().toPlainString() + "%";
            header.add(text("accounting.base") + " " + r);
            header.add(text("accounting.vat") + " " + r);
        }
        header.add(text("accounting.base"));
        header.add(text("accounting.vat"));
        header.add(text("export.total"));
        return header;
    }

    private static void addRates(List<Object> row, List<RateAmount> rates, BigDecimal base, BigDecimal vat, BigDecimal total) {
        for (RateAmount r : rates) {
            row.add(r.base());
            row.add(r.vat());
        }
        row.add(base);
        row.add(vat);
        row.add(total);
    }

    private static BigDecimal amountOf(List<Line> lines, String key) {
        return lines.stream().filter(l -> l.key().equals(key)).map(l -> l.amount().negate()).findFirst().orElse(null);
    }

    private String text(String code) {
        return messageSource.getMessage(code, null, code, LocaleContextHolder.getLocale());
    }
}
