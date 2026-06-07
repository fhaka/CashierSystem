package com.supermarket.service;

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
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

@Service
public class ThermalPrinterService {

    private final String configuredPrinterName;

    public ThermalPrinterService(@Value("${receipt.printer.name:}") String configuredPrinterName) {
        this.configuredPrinterName = configuredPrinterName;
    }

    public void printReceipt(String receiptText) {
        if (receiptText == null || receiptText.isBlank()) {
            throw new IllegalArgumentException("Receipt text is required");
        }

        PrintService printService = resolvePrintService();
        printRawBytes(printService, buildEscPosReceipt(receiptText));
    }

    public void testCut() {
        PrintService printService = resolvePrintService();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            output.write(new byte[] { 0x1B, 0x40 });
            output.write("CUT TEST\n\n\n\n\n\n".getBytes(Charset.forName("CP437")));
            writeCutCommands(output);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not build cut test: " + exception.getMessage(), exception);
        }
        printRawBytes(printService, output.toByteArray());
    }

    public List<String> findPrinterNames() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName)
                .toList();
    }

    private PrintService resolvePrintService() {
        PrintService[] printServices = PrintServiceLookup.lookupPrintServices(null, null);
        if (printServices.length == 0) {
            throw new IllegalStateException("No printers found on this computer");
        }

        if (configuredPrinterName != null && !configuredPrinterName.isBlank()) {
            return Arrays.stream(printServices)
                    .filter(service -> service.getName().equalsIgnoreCase(configuredPrinterName.trim())
                            || service.getName().toLowerCase().contains(configuredPrinterName.trim().toLowerCase()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Configured printer not found: " + configuredPrinterName));
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
            throw new IllegalStateException("Could not print to " + printService.getName() + ": " + exception.getMessage(), exception);
        }
    }

    private byte[] buildEscPosReceipt(String receiptText) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            output.write(new byte[] { 0x1B, 0x40 }); // Initialize printer.
            output.write(receiptText.replace("\r\n", "\n").replace("\r", "\n").getBytes(Charset.forName("CP437")));
            output.write(new byte[] { 0x0A, 0x0A, 0x0A, 0x0A, 0x0A, 0x0A });
            writeCutCommands(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not build receipt bytes: " + exception.getMessage(), exception);
        }
    }

    private void writeCutCommands(ByteArrayOutputStream output) throws Exception {
        output.write(new byte[] { 0x1D, 0x56, 0x00 }); // Full cut: GS V 0
    }
}
