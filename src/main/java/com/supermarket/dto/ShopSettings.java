package com.supermarket.dto;

/**
 * Shop details printed on receipts and shown in the app, and receipt options.
 * receiptWidth is the number of characters per line: 32 for 58 mm paper, 42 or 48 for 80 mm.
 */
public record ShopSettings(
        String name,
        String address,
        String city,
        String taxId,
        String phone,
        String email,
        String receiptFooter,
        int receiptWidth,
        boolean autoPrint,
        String receiptPrinter
) {
}
