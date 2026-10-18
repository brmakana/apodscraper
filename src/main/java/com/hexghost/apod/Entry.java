package com.hexghost.apod;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable CSV record. Blank fields are serialized as empty strings, never the literal null. */
record Entry(String url, String date, String previous, String image, String status,
             String localPath, String checksum, long bytes, int attempts, String stage, String at, String error) {
    static Entry page(PageParser.Page p) {
        return new Entry(p.url(), p.date() == null ? "" : p.date().toString(), p.previous(), p.image(),
                "PENDING", "", "", 0, 0, "", "", "");
    }
    static Entry unknown(String url) {
        return new Entry(url, "", "", "", "PENDING", "", "", 0, 0, "", "", "");
    }
    Entry outcome(String status, String stage, int attempts, String error) {
        return new Entry(url, date, previous, image, status, localPath, checksum, bytes, attempts, stage,
                Instant.now().toString(), error);
    }
    Entry file(String path, String checksum, long bytes, String status) {
        return new Entry(url, date, previous, image, status, path, checksum, bytes, attempts, stage, at, error);
    }
    LocalDate day() { return date.isEmpty() ? null : LocalDate.parse(date); }
    boolean failed() { return status.equals("FAILED"); }
}
