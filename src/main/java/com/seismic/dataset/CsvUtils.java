package com.seismic.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RFC 4180-style CSV helpers: comma separator, optional double quotes, and "" for a quote
 * inside a quoted field. Needed because USGS exports quote the "place" column, which contains commas.
 */
public final class CsvUtils {

    private CsvUtils() {
    }

    /**
     * Splits one CSV line into fields.
     *
     * @throws IllegalArgumentException if a quoted field is not closed
     */
    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        fields.add(current.toString());
        return fields;
    }

    /** Quotes a value only when it contains a comma, quote or line break. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** Formats a number for CSV output; NaN becomes an empty field (a missing value). */
    public static String number(double value, int decimals) {
        if (Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** Header names are compared case-insensitively and without surrounding spaces or a BOM. */
    public static String normaliseHeader(String header) {
        return header.replace("﻿", "").trim().toLowerCase(Locale.ROOT);
    }
}
