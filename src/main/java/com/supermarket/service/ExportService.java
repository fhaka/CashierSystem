package com.supermarket.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.supermarket.dto.Analytics;
import com.supermarket.dto.SalesPage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Reports as Excel workbooks and PDF documents. Column titles follow the till's language. */
@Service
public class ExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MessageSource messageSource;

    public ExportService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** The sales analysis: summary, days, hours, products, categories, cashiers, payments and dead stock. */
    public byte[] analyticsXlsx(Analytics a) {
        try (Workbook book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            Analytics.Kpis k = a.kpis();
            Sheet summary = book.createSheet(text("export.summary"));
            int r = 0;
            r = title(summary, r, styles, text("export.analyticsTitle") + " " + a.from().format(DATE) + " - " + a.to().format(DATE));
            Object[][] kpis = {
                    {text("export.salesCount"), k.salesCount()}, {text("export.grossSales"), k.grossSales()},
                    {text("export.discounts"), k.discounts()}, {text("export.refunds"), k.refunds()},
                    {text("export.revenue"), k.revenue()}, {text("export.vat"), k.vat()},
                    {text("export.revenueWithoutVat"), k.revenueWithoutVat()}, {text("export.cost"), k.cost()},
                    {text("export.profit"), k.profit()}, {text("export.margin"), k.marginPercent()},
                    {text("export.averageSale"), k.averageSale()}, {text("export.piecesSold"), k.piecesSold()}};
            for (Object[] kpi : kpis) {
                r = row(summary, r, styles, kpi);
            }
            summary.autoSizeColumn(0);
            summary.autoSizeColumn(1);

            table(book.createSheet(text("export.byDay")), styles,
                    List.of(text("export.day"), text("export.salesCount"), text("export.revenue")),
                    a.byDay().stream().map(d -> new Object[] {d.day().format(DATE), d.salesCount(), d.revenue()}).toList());
            table(book.createSheet(text("export.byHour")), styles,
                    List.of(text("export.hour"), text("export.salesCount"), text("export.revenue")),
                    a.byHour().stream().filter(h -> h.salesCount() > 0)
                            .map(h -> new Object[] {String.format("%02d:00", h.hour()), h.salesCount(), h.revenue()}).toList());
            table(book.createSheet(text("export.products")), styles,
                    List.of(text("export.product"), text("export.barcode"), text("export.category"), text("export.quantity"),
                            text("export.revenue"), text("export.vat"), text("export.cost"), text("export.profit"), text("export.margin")),
                    a.products().stream().map(p -> new Object[] {p.name(), p.barcode(), p.category(), p.quantity(), p.revenue(),
                            p.vat(), p.cost(), p.profit(), p.marginPercent()}).toList());
            table(book.createSheet(text("export.categories")), styles,
                    List.of(text("export.category"), text("export.quantity"), text("export.revenue"), text("export.profit")),
                    a.byCategory().stream().map(g -> new Object[] {g.name(), g.count(), g.revenue(), g.profit()}).toList());
            table(book.createSheet(text("export.cashiers")), styles,
                    List.of(text("export.cashier"), text("export.salesCount"), text("export.revenue")),
                    a.byCashier().stream().map(g -> new Object[] {g.name(), g.count(), g.revenue()}).toList());
            table(book.createSheet(text("export.payments")), styles,
                    List.of(text("export.method"), text("export.count"), text("export.amount")),
                    a.byPayment().stream().map(g -> new Object[] {text("payment." + g.name().toLowerCase()), g.count(), g.revenue()}).toList());
            table(book.createSheet(text("export.deadStock")), styles,
                    List.of(text("export.product"), text("export.barcode"), text("export.category"), text("export.stock"), text("export.value")),
                    a.deadStock().stream().map(d -> new Object[] {d.name(), d.barcode(), d.category(), d.stock(), d.value()}).toList());
            return bytes(book);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the Excel file", exception);
        }
    }

    /** All sales of a period, one row per sale. */
    public byte[] salesXlsx(List<SalesPage.Row> sales, LocalDate from, LocalDate to) {
        try (Workbook book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            table(book.createSheet(text("export.sales")), styles,
                    List.of(text("export.invoice"), text("export.date"), text("export.cashier"), text("export.customer"),
                            text("export.lines"), text("export.discounts"), text("export.total"), text("export.method")),
                    sales.stream().map(s -> new Object[] {s.invoiceNumber(), s.date().format(DATE_TIME), s.cashierName(),
                            s.customerName(), s.lines(), s.discountAmount(), s.totalAmount(),
                            String.join(", ", s.paymentMethods().stream().map(m -> text("payment." + m.toLowerCase())).toList())}).toList());
            return bytes(book);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the Excel file", exception);
        }
    }

    /** The sales analysis as a printable A4 document. */
    public byte[] analyticsPdf(Analytics a) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(document, out);
        document.open();
        Fonts fonts = new Fonts();
        document.add(new Paragraph(text("export.analyticsTitle") + " " + a.from().format(DATE) + " - " + a.to().format(DATE), fonts.title));
        document.add(new Paragraph(text("report.printed") + ": " + LocalDateTime.now().format(DATE_TIME), fonts.small));
        document.add(new Paragraph(" "));

        Analytics.Kpis k = a.kpis();
        PdfPTable kpis = pdfTable(fonts, List.of(text("export.summary"), ""), List.of(
                new Object[] {text("export.salesCount"), k.salesCount()}, new Object[] {text("export.grossSales"), k.grossSales()},
                new Object[] {text("export.discounts"), k.discounts()}, new Object[] {text("export.refunds"), k.refunds()},
                new Object[] {text("export.revenue"), k.revenue()}, new Object[] {text("export.vat"), k.vat()},
                new Object[] {text("export.cost"), k.cost()}, new Object[] {text("export.profit"), k.profit()},
                new Object[] {text("export.margin"), k.marginPercent() + "%"}, new Object[] {text("export.averageSale"), k.averageSale()}),
                new float[] {3, 2});
        document.add(kpis);
        document.add(new Paragraph(" "));
        document.add(heading(text("export.topProducts"), fonts));
        document.add(pdfTable(fonts, List.of(text("export.product"), text("export.quantity"), text("export.revenue"), text("export.profit")),
                a.products().stream().limit(20).map(p -> new Object[] {p.name(), p.quantity().stripTrailingZeros().toPlainString(),
                        p.revenue(), p.profit()}).toList(), new float[] {4, 1.2f, 1.6f, 1.6f}));
        document.add(new Paragraph(" "));
        document.add(heading(text("export.categories"), fonts));
        document.add(pdfTable(fonts, List.of(text("export.category"), text("export.revenue"), text("export.profit")),
                a.byCategory().stream().map(g -> new Object[] {g.name(), g.revenue(), g.profit()}).toList(), new float[] {3, 2, 2}));
        document.add(new Paragraph(" "));
        document.add(heading(text("export.payments"), fonts));
        document.add(pdfTable(fonts, List.of(text("export.method"), text("export.count"), text("export.amount")),
                a.byPayment().stream().map(g -> new Object[] {text("payment." + g.name().toLowerCase()), g.count(), g.revenue()}).toList(),
                new float[] {3, 1, 2}));
        if (!a.deadStock().isEmpty()) {
            document.add(new Paragraph(" "));
            document.add(heading(text("export.deadStock"), fonts));
            document.add(pdfTable(fonts, List.of(text("export.product"), text("export.stock"), text("export.value")),
                    a.deadStock().stream().limit(20).map(d -> new Object[] {d.name(), d.stock().stripTrailingZeros().toPlainString(), d.value()}).toList(),
                    new float[] {4, 1.5f, 2}));
        }
        document.close();
        return out.toByteArray();
    }

    /** A receipt-style text (Z report, X report) as a PDF page in a fixed-width font. */
    public byte[] textPdf(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 56, 56, 48, 48);
        PdfWriter.getInstance(document, out);
        document.open();
        Font mono = new Font(font(BaseFont.COURIER), 10);
        document.add(new Paragraph(text, mono));
        document.close();
        return out.toByteArray();
    }

    private PdfPTable pdfTable(Fonts fonts, List<String> header, List<Object[]> rows, float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        for (String title : header) {
            PdfPCell cell = new PdfPCell(new Phrase(title, fonts.bold));
            cell.setBackgroundColor(new java.awt.Color(232, 238, 252));
            table.addCell(cell);
        }
        for (Object[] row : rows) {
            for (Object value : row) {
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value.toString(), fonts.normal));
                if (value instanceof Number) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    private static int title(Sheet sheet, int r, Styles styles, String title) {
        Cell cell = sheet.createRow(r).createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(styles.bold);
        return r + 2;
    }

    private static int row(Sheet sheet, int r, Styles styles, Object[] values) {
        Row row = sheet.createRow(r);
        for (int i = 0; i < values.length; i++) {
            write(row.createCell(i), values[i], styles);
        }
        return r + 1;
    }

    private static void table(Sheet sheet, Styles styles, List<String> header, List<Object[]> rows) {
        Row head = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(header.get(i));
            cell.setCellStyle(styles.bold);
        }
        int r = 1;
        for (Object[] values : rows) {
            r = row(sheet, r, styles, values);
        }
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < header.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /** Numbers stay numbers in Excel (so they can be summed); amounts get two decimals. */
    private static void write(Cell cell, Object value, Styles styles) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
            cell.setCellStyle(decimal.scale() == 2 ? styles.money : styles.number);
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            cell.setCellStyle(styles.number);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private static byte[] bytes(Workbook book) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        book.write(out);
        return out.toByteArray();
    }

    private static BaseFont font(String name) {
        try {
            // Cp1252 covers the Albanian letters (ë, ç, Ë, Ç) in the built-in PDF fonts.
            return BaseFont.createFont(name, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception exception) {
            throw new IllegalStateException("PDF font not available", exception);
        }
    }

    private static Paragraph heading(String title, Fonts fonts) {
        Paragraph heading = new Paragraph(title, fonts.heading);
        heading.setSpacingAfter(6);
        return heading;
    }

    private String text(String code) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(code, null, code, locale);
    }

    private static final class Styles {
        private final CellStyle bold;
        private final CellStyle money;
        private final CellStyle number;

        private Styles(Workbook book) {
            org.apache.poi.ss.usermodel.Font boldFont = book.createFont();
            boldFont.setBold(true);
            bold = book.createCellStyle();
            bold.setFont(boldFont);
            money = book.createCellStyle();
            money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            number = book.createCellStyle();
            number.setDataFormat(book.createDataFormat().getFormat("#,##0.###"));
        }
    }

    private static final class Fonts {
        private final Font title = new Font(font(BaseFont.HELVETICA_BOLD), 15);
        private final Font heading = new Font(font(BaseFont.HELVETICA_BOLD), 11);
        private final Font bold = new Font(font(BaseFont.HELVETICA_BOLD), 9);
        private final Font normal = new Font(font(BaseFont.HELVETICA), 9);
        private final Font small = new Font(font(BaseFont.HELVETICA), 8);
    }
}
