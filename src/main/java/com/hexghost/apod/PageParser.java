package com.hexghost.apod;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

final class PageParser {
    record Page(String url, LocalDate date, String previous, String image, boolean video, boolean boundary, String error) {}
    private static final DateTimeFormatter META_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);
    private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("uuuu-MMMM-d", Locale.US);
    private static final Pattern SLUG_DATE = Pattern.compile("apod-(\\d{4}-[a-z]+-\\d{1,2})(?:-|/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_DATE = Pattern.compile("APOD:\\s*(\\d{4} [A-Za-z]+ \\d{1,2})");
    Page parse(String html, URI uri) {
        var doc = Jsoup.parse(html, uri.toString());
        String url = Urls.canonical(uri), previous = "", image = "", error = "";
        boolean video = false, boundary = false;
        LocalDate date = null;
        var prev = doc.selectFirst("a.smd-next-prev__prev");
        if (prev != null && !prev.hasClass("smd-next-prev__disabled") && !prev.attr("href").isBlank()
                && !prev.attr("href").startsWith("#")) {
            try { previous = Urls.canonical(URI.create(prev.absUrl("href"))); }
            catch (IllegalArgumentException e) { error = "Invalid previous-page link"; }
        }
        for (var row : doc.select(".media-detail-hero__meta-table tr")) {
            var th = row.selectFirst("th"); var td = row.selectFirst("td");
            if (th != null && td != null && th.text().replace(":", "").equalsIgnoreCase("Date")) {
                try { date = LocalDate.parse(td.text(), META_DATE); } catch (RuntimeException ignored) { }
            }
        }
        if (date == null) {
            var m = SLUG_DATE.matcher(uri.getPath());
            if (m.find()) try { date = LocalDate.parse(m.group(1).toLowerCase(Locale.US),
                    new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().append(URL_DATE).toFormatter(Locale.US)); }
            catch (RuntimeException ignored) { }
        }
        if (date == null) {
            var m = TITLE_DATE.matcher(doc.title());
            if (m.find()) try { date = LocalDate.parse(m.group(1), DateTimeFormatter.ofPattern("uuuu MMMM d", Locale.US)); }
            catch (RuntimeException ignored) { }
        }
        // APOD's first published day is the only accepted implicit archive boundary.
        boundary = LocalDate.of(1995, 6, 16).equals(date) && previous.isEmpty();
        Element media = doc.selectFirst("div.media-detail-hero__media");
        if (media != null) {
            video = media.selectFirst("video, iframe[src], embed[src], object[data], .is-type-video") != null;
            if (!video) {
                var img = media.selectFirst("a[href] img");
                var anchor = img == null ? null : img.closest("a[href]");
                if (anchor != null) try { image = Urls.validate(URI.create(anchor.absUrl("href"))).toString(); }
                catch (IllegalArgumentException ignored) { }
                if (image.isEmpty()) image = rolloverImage(media);
            }
        }
        if (date == null) error = append(error, "Cannot determine APOD date");
        if (!video && image.isEmpty()) error = append(error, "Cannot identify full-resolution image or explicit video");
        if (previous.isEmpty() && !boundary) error = append(error, "Missing previous-page navigation");
        if (previous.equals(url)) error = append(error, "Previous link points to the same page");
        return new Page(url, date, previous, image, video, boundary, error);
    }
    private String rolloverImage(Element media) {
        var primary = media.selectFirst(".smd-image-rollover img.smd-image-rollover__image--primary[src]");
        if (primary == null) return "";
        try {
            URI source = Urls.validate(URI.create(primary.absUrl("src")));
            // This NASA component has no download anchor. Its static asset path names the
            // original; the query parameters request resized/cropped presentation variants.
            // Restrict this fallback to that component and asset service, not arbitrary thumbnails.
            if (!"assets.science.nasa.gov".equalsIgnoreCase(source.getHost())
                    || !source.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|gif|webp|tiff?|avif)$")) return "";
            String dynamicPrefix = "/dynamicimage/assets/science/";
            if (source.getRawPath().startsWith(dynamicPrefix)) {
                // NASA's dynamic-image service exposes the same asset under this static path.
                return source.resolve("/content/dam/science/" + source.getRawPath().substring(dynamicPrefix.length())).toString();
            }
            if (!source.getPath().startsWith("/content/dam/science/")) return "";
            return source.toString().split("[?#]", 2)[0];
        } catch (IllegalArgumentException ignored) { return ""; }
    }
    private String append(String a, String b) { return a.isEmpty() ? b : a + "; " + b; }
}
