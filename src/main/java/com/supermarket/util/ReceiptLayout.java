package com.supermarket.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds fixed-width text for the receipt printer: wrapped lines, centered lines, and rows with the amount
 * aligned to the right edge. Width is the number of characters per line (32 for 58 mm paper, 42/48 for 80 mm).
 */
public final class ReceiptLayout {

    private final int width;
    private final StringBuilder text = new StringBuilder();

    public ReceiptLayout(int width) {
        this.width = width;
    }

    public int width() {
        return width;
    }

    /** Text on as many lines as it needs, broken between words. */
    public ReceiptLayout line(String value) {
        wrap(value).forEach(part -> text.append(part).append('\n'));
        return this;
    }

    public ReceiptLayout center(String value) {
        for (String part : wrap(value)) {
            text.append(" ".repeat((width - part.length()) / 2)).append(part).append('\n');
        }
        return this;
    }

    /** "Label            12.50". A long label wraps; the value goes at the end of its last line, or under it. */
    public ReceiptLayout row(String label, Object value) {
        String right = format(value);
        List<String> parts = wrap(label);
        String last = parts.remove(parts.size() - 1);
        parts.forEach(part -> text.append(part).append('\n'));
        if (last.length() + 1 + right.length() > width) {
            text.append(last).append('\n');
            last = "";
        }
        text.append(last).append(" ".repeat(Math.max(0, width - last.length() - right.length()))).append(right).append('\n');
        return this;
    }

    public ReceiptLayout rule() {
        text.append("-".repeat(width)).append('\n');
        return this;
    }

    public ReceiptLayout doubleRule() {
        text.append("=".repeat(width)).append('\n');
        return this;
    }

    public ReceiptLayout blank() {
        text.append('\n');
        return this;
    }

    @Override
    public String toString() {
        return text.toString();
    }

    public static String format(Object value) {
        if (value instanceof BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> wrap(String value) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : (value == null ? "" : value).split("\n", -1)) {
            // Leading spaces (a line indented under a product name) stay on the first line.
            String indent = paragraph.substring(0, paragraph.length() - paragraph.stripLeading().length());
            StringBuilder current = new StringBuilder(indent.length() < width / 2 ? indent : "");
            int start = current.length();
            for (String word : paragraph.trim().split("\\s+")) {
                while (word.length() > width) {
                    if (current.length() > start) {
                        lines.add(current.toString());
                    }
                    current.setLength(0);
                    start = 0;
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (current.length() == start) {
                    current.append(word);
                } else if (current.length() + 1 + word.length() <= width) {
                    current.append(' ').append(word);
                } else {
                    lines.add(current.toString());
                    current.setLength(0);
                    start = 0;
                    current.append(word);
                }
            }
            lines.add(current.toString());
        }
        return lines;
    }
}
