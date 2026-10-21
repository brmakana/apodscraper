package com.hexghost.apod;

import java.net.URI;
import java.net.URISyntaxException;

final class Urls {
    private Urls() {}
    static final java.time.LocalDate LEGACY_CUTOFF = java.time.LocalDate.of(2020, 8, 13);
    static final java.time.LocalDate FIRST_DAY = java.time.LocalDate.of(1995, 6, 16);
    static boolean nasaApod(URI uri) {
        return "apod.nasa.gov".equalsIgnoreCase(uri.getHost())
                || "science.nasa.gov".equalsIgnoreCase(uri.getHost());
    }
    static String legacy(java.time.LocalDate date) {
        return "https://apod.nasa.gov/apod/ap" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + ".html";
    }
    static java.time.LocalDate date(URI uri) {
        if (!nasaApod(uri)) return null;
        var old = java.util.regex.Pattern.compile("/apod/ap(\\d{6})\\.html").matcher(uri.getPath());
        if (old.matches()) {
            String d = old.group(1);
            int year = Integer.parseInt(d.substring(0, 2));
            try { return java.time.LocalDate.of(year >= 95 ? 1900 + year : 2000 + year,
                    Integer.parseInt(d.substring(2, 4)), Integer.parseInt(d.substring(4, 6))); }
            catch (RuntimeException ignored) { return null; }
        }
        var slug = java.util.regex.Pattern.compile("apod-(\\d{4}-[a-z]+-\\d{1,2})(?:-|/|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(uri.getPath());
        if (slug.find()) try {
            return java.time.LocalDate.parse(slug.group(1), new java.time.format.DateTimeFormatterBuilder()
                    .parseCaseInsensitive().appendPattern("uuuu-MMMM-d").toFormatter(java.util.Locale.US));
        } catch (RuntimeException ignored) { }
        return null;
    }
    static String archiveUrl(String url) {
        var date = date(URI.create(url));
        return date != null && !date.isAfter(LEGACY_CUTOFF) && !date.isBefore(FIRST_DAY) ? legacy(date) : url;
    }
    static URI validate(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Expected an absolute HTTP(S) URL: " + uri);
        return uri;
    }
    static String canonical(URI uri) {
        validate(uri);
        try {
            int port = uri.getPort();
            if ((uri.getScheme().equalsIgnoreCase("https") && port == 443)
                    || (uri.getScheme().equalsIgnoreCase("http") && port == 80)) port = -1;
            // Preserve escaped paths and queries; only remove the fragment and normalize dot segments.
            String authority = uri.getHost().toLowerCase();
            if (authority.contains(":")) authority = "[" + authority.replace("[", "").replace("]", "") + "]";
            return new URI(uri.getScheme().toLowerCase() + "://" + authority + (port < 0 ? "" : ":" + port)
                    + (uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).normalize().toString();
        } catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
    }
}
