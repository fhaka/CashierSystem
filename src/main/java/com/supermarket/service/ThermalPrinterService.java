package com.supermarket.service;

import com.supermarket.exception.ReceiptPrinterException;
import com.supermarket.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

@Service
public class ThermalPrinterService {

    /** Default code page of ESC/POS thermal printers. It has ë, ç and Ç, but not Ë. */
    private static final Charset PRINTER_CHARSET = Charset.forName("CP437");

    private final String configuredPrinterName;

    public ThermalPrinterService(@Value("${receipt.printer.name:}") String configuredPrinterName) {
        this.configuredPrinterName = configuredPrinterName;
    }

    public void printReceipt(String receiptText) {
        printReceipt(receiptText, null);
    }

    public void printReceipt(String receiptText, String printerName) {
        if (receiptText == null || receiptText.isBlank()) {
            throw new ValidationException("printer.receiptRequired");
        }

        PrintService printService = resolvePrintService(printerName);
        printRawBytes(printService, buildEscPosReceipt(receiptText));
    }

    public void testCut() {
        PrintService printService = resolvePrintService();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            output.write(new byte[] { 0x1B, 0x40 });
            output.write("CUT TEST\n\n\n\n\n\n".getBytes(PRINTER_CHARSET));
            writeCutCommands(output);
        } catch (Exception exception) {
            throw new ReceiptPrinterException("printer.buildFailed", exception, exception.getMessage());
        }
        printRawBytes(printService, output.toByteArray());
    }

    public List<String> findPrinterNames() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .toList();
    }

    private PrintService resolvePrintService() {
        return resolvePrintService(null);
    }

    private PrintService resolvePrintService(String requestedPrinterName) {
        PrintService[] printServices = PrintServiceLookup.lookupPrintServices(null, null);
        if (printServices.length == 0) {
            throw new ReceiptPrinterException("printer.noneFound");
        }

        String printerName = requestedPrinterName;
        if (printerName == null || printerName.isBlank()) {
            printerName = configuredPrinterName;
        }

        if (printerName != null && !printerName.isBlank()) {
            String selectedPrinterName = printerName.trim();
            return Arrays.stream(printServices)
                    .filter(service -> service.getName().equalsIgnoreCase(selectedPrinterName)
                            || service.getName().toLowerCase().contains(selectedPrinterName.toLowerCase()))
                    .findFirst()
                    .orElseThrow(() -> new ReceiptPrinterException("printer.notFound", selectedPrinterName));
        }

        PrintService defaultPrintService = PrintServiceLookup.lookupDefaultPrintService();
        if (defaultPrintService != null) {
            return defaultPrintService;
        }
        return printServices[0];
    }

    private void printRawBytes(PrintService printService, byte[] bytes) {
        try {
            DocPrintJob printJob = printService.createPrintJob();
            Doc doc = new SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
            PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
            printJob.print(doc, attributes);
        } catch (Exception exception) {
            throw new ReceiptPrinterException("printer.failed", exception, printService.getName(), exception.getMessage());
        }
    }

    private void printWithWindowsDriver(PrintService printService, String receiptText) {
        try {
            String[] lines = receiptText.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);

            double paperWidth = millimetersToPoints(80);
            double paperHeight = Math.max(millimetersToPoints(90), lines.length * 11.0 + 42.0);

            Paper paper = new Paper();
            paper.setSize(paperWidth, paperHeight);
            paper.setImageableArea(8, 8, paperWidth - 16, paperHeight - 16);

            PageFormat pageFormat = new PageFormat();
            pageFormat.setPaper(paper);

            PrinterJob printerJob = PrinterJob.getPrinterJob();
            printerJob.setPrintService(printService);
            printerJob.setPrintable((graphics, format, pageIndex) -> {
                if (pageIndex > 0) {
                    return java.awt.print.Printable.NO_SUCH_PAGE;
                }

                Graphics2D graphics2D = (Graphics2D) graphics;
                graphics2D.translate(format.getImageableX(), format.getImageableY());
                graphics2D.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));

                FontMetrics fontMetrics = graphics2D.getFontMetrics();
                int y = fontMetrics.getAscent();
                for (String line : lines) {
                    graphics2D.drawString(line, 0, y);
                    y += fontMetrics.getHeight();
                }
                return java.awt.print.Printable.PAGE_EXISTS;
            }, pageFormat);
            printerJob.print();
        } catch (PrinterException exception) {
            throw new ReceiptPrinterException("printer.failed", exception, printService.getName(), exception.getMessage());
        }
    }

    private double millimetersToPoints(double millimeters) {
        return millimeters * 72.0 / 25.4;
    }

    private byte[] buildEscPosReceipt(String receiptText) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            output.write(new byte[] { 0x1B, 0x40 }); // Initialize printer.
            output.write(toPrinterText(receiptText).getBytes(PRINTER_CHARSET));
            output.write(new byte[] { 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A });
            writeCutCommands(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ReceiptPrinterException("printer.buildFailed", exception, exception.getMessage());
        }
    }

    private String toPrinterText(String receiptText) {
        return receiptText.replace("\r\n", "\n").replace("\r", "\n").replace('Ë', 'E');
    }

    private void writeCutCommands(ByteArrayOutputStream output) throws Exception {
        output.write(new byte[] { 0x1D, 0x56, 0x00 }); // Full cut: GS V 0
    }
}
