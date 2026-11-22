package com.hexghost.apod;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PageParserTest {
    final PageParser parser = new PageParser();
    @Test void actualNasaImageExcerpt() throws Exception {
        var html = new String(getClass().getResourceAsStream("/fixtures/nasa-image.html").readAllBytes());
        var p = parser.parse(html, URI.create("https://science.nasa.gov/image-article/apod-2019-june-24-example/"));
        assertEquals(LocalDate.of(2019, 6, 24), p.date());
        assertTrue(p.image().endsWith("/AnticrepuscularRays_Patekar_5600.jpg"));
        assertFalse(p.image().contains("?")); assertEquals("https://apod.nasa.gov/apod/ap190623.html", p.previous());
        assertEquals("", p.error());
    }
    @Test void actualNasaLandingVideoExcerpt() throws Exception {
        var html = new String(getClass().getResourceAsStream("/fixtures/nasa-video.html").readAllBytes());
        var p = parser.parse(html, URI.create("https://science.nasa.gov/apod/"));
        assertTrue(p.video()); assertEquals(LocalDate.of(2026, 10, 18), p.date()); assertEquals("", p.error());
    }
    @Test void relativeLinksAndAdditionalClasses() {
        var p = parser.parse(TestSupport.page("June 24, 2019", TestSupport.image("/full.jpg"), "../previous"), URI.create("https://example.test/day/one"));
        assertEquals("https://example.test/full.jpg", p.image()); assertEquals("https://example.test/previous", p.previous());
    }
    @Test void absentImageIsFailureNotVideoAndNavigationSurvives() {
        var p = parser.parse(TestSupport.page("June 24, 2019", "<img src='thumb.jpg'>", "/previous"), URI.create("https://example.test/one"));
        assertFalse(p.video()); assertFalse(p.error().isEmpty()); assertFalse(p.previous().isEmpty());
    }
    @Test void urlDateFallbackAndUnexpectedNavigationAbsence() {
        var p = parser.parse(TestSupport.image("/full.jpg"), URI.create("https://example.test/apod-2019-june-24-test/"));
        assertEquals(LocalDate.of(2019, 6, 24), p.date()); assertTrue(p.error().contains("navigation"));
    }
    @Test void earliestDateIsBoundary() {
        var p = parser.parse(TestSupport.page("June 16, 1995", TestSupport.image("/full.jpg"), null), URI.create("https://example.test/one"));
        assertTrue(p.boundary()); assertEquals("", p.error());
    }
    @Test void selfLinkIsError() {
        var p = parser.parse(TestSupport.page("June 24, 2019", TestSupport.image("/full.jpg"), "/one"), URI.create("https://example.test/one"));
        assertTrue(p.error().contains("same page"));
    }
    @Test void actualNasaRolloverUsesOriginalPrimaryAsset() throws Exception {
        var html = new String(getClass().getResourceAsStream("/fixtures/nasa-rollover.html").readAllBytes());
        var p = parser.parse(html, URI.create("https://science.nasa.gov/image-article/apod-2026-may-26-ngc-3660-and-burcins-galaxy/"));
        assertEquals(LocalDate.of(2026, 5, 26), p.date());
        assertEquals("https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2026/may/Ngc3660Burcin_Block_960.jpg", p.image());
        assertTrue(p.previous().endsWith("/apod-2026-may-25-thackerays-globules/"));
        assertFalse(p.video()); assertEquals("", p.error());
    }
    @Test void rolloverPrimaryIsChosenEvenWhenSecondaryComesFirst() {
        var media = "<div class='smd-image-rollover'>"
                + "<img class='smd-image-rollover__image--secondary' src='https://assets.science.nasa.gov/content/dam/science/annotated.jpg'>"
                + "<img class='smd-image-rollover__image--primary' src='https://assets.science.nasa.gov/content/dam/science/original%20image.jpg?w=960&amp;h=640#preview'></div>";
        var p = parser.parse(TestSupport.page("May 26, 2026", media, "/previous"), URI.create("https://science.nasa.gov/day"));
        assertEquals("https://assets.science.nasa.gov/content/dam/science/original%20image.jpg", p.image());
        assertEquals("", p.error());
    }
    @Test void actualNasaDynamicRolloverUsesOriginalAfterImage() throws Exception {
        var html = new String(getClass().getResourceAsStream("/fixtures/nasa-rollover-dynamic.html").readAllBytes());
        var p = parser.parse(html, URI.create("https://science.nasa.gov/image-article/apod-2026-september-23-the-mcgetchin-crater/"));
        assertEquals(LocalDate.of(2026, 9, 23), p.date());
        assertEquals("https://assets.science.nasa.gov/content/dam/science/cds/apod/apod/2026/september/content_mcgetchin_after.jpg", p.image());
        assertTrue(p.previous().contains("apod-2026-september-22-"));
        assertFalse(p.video()); assertEquals("", p.error());
    }
    @Test void dynamicRolloverMappingPreservesEscapedAssetPathAndDropsTransformations() {
        String media = "<div class='smd-image-rollover'><img class='smd-image-rollover__image--primary' "
                + "src='https://assets.science.nasa.gov/dynamicimage/assets/science/example%20after.jpg?w=100&amp;h=100#preview'></div>";
        var p = parser.parse(TestSupport.page("September 23, 2026", media, "/previous"), URI.create("https://science.nasa.gov/day"));
        assertEquals("https://assets.science.nasa.gov/content/dam/science/example%20after.jpg", p.image());
        assertEquals("", p.error());
    }
    @Test void arbitraryUnlinkedImagesAndUnrecognizedRolloverSourcesStillFail() {
        for (String src : new String[]{"https://example.test/image.jpg?token=keep", "https://assets.science.nasa.gov/dynamicimage/thumbnail.jpg?w=100", "javascript:alert(1)"}) {
            String media = "<div class='smd-image-rollover'><img class='smd-image-rollover__image--primary' src='" + src + "'></div>";
            var p = parser.parse(TestSupport.page("May 26, 2026", media, "/previous"), URI.create("https://science.nasa.gov/day"));
            assertEquals("", p.image()); assertFalse(p.error().isEmpty());
        }
    }
    @Test void rolloverWithoutPrimaryDoesNotSelectAnnotatedSecondary() {
        String media = "<div class='smd-image-rollover'><img class='smd-image-rollover__image--secondary' src='https://assets.science.nasa.gov/content/dam/science/annotated.jpg'></div>";
        var p = parser.parse(TestSupport.page("May 26, 2026", media, "/previous"), URI.create("https://science.nasa.gov/day"));
        assertEquals("", p.image()); assertFalse(p.error().isEmpty());
    }
    @Test void fullResolutionAnchorTakesPrecedenceOverRolloverFallback() {
        String media = "<div class='smd-image-rollover'><a href='/explicit-original.jpg'>"
                + "<img class='smd-image-rollover__image--primary' src='https://assets.science.nasa.gov/content/dam/science/preview.jpg?w=100'></a></div>";
        var p = parser.parse(TestSupport.page("May 26, 2026", media, "/previous"), URI.create("https://science.nasa.gov/day"));
        assertEquals("https://science.nasa.gov/explicit-original.jpg", p.image()); assertEquals("", p.error());
    }
    @Test void brokenCutoffPageStillAdvancesAfterRedirect() {
        var p = parser.parse("<title>APOD: 2020 August 13 - Example</title>",
                URI.create("https://science.nasa.gov/image-article/apod-2020-august-13-example/"));
        assertEquals("https://apod.nasa.gov/apod/ap200812.html", p.previous());
        assertTrue(p.error().contains("full-resolution"));
        assertFalse(p.error().contains("navigation"));
    }
    @Test void switchesAtCutoffAndPreservesModernDates() {
        assertEquals("https://apod.nasa.gov/apod/ap200813.html", Urls.archiveUrl(
                "https://science.nasa.gov/image-article/apod-2020-august-13-example/"));
        String modern = "https://science.nasa.gov/image-article/apod-2020-august-14-example/";
        assertEquals(modern, Urls.archiveUrl(modern));
        var p = parser.parse(TestSupport.page("August 14, 2020", TestSupport.image("/full.jpg"),
                "https://science.nasa.gov/image-article/apod-2020-august-13-example/"), URI.create(modern));
        assertEquals("https://apod.nasa.gov/apod/ap200813.html", p.previous());
    }
    @Test void originalHtmlAndCenturyBoundary() {
        var p = parser.parse("<center><a href='image/full.jpg'><img src='thumb.jpg'></a></center>",
                URI.create("https://apod.nasa.gov/apod/ap000101.html"));
        assertEquals(LocalDate.of(2000, 1, 1), p.date());
        assertEquals("https://apod.nasa.gov/apod/ap991231.html", p.previous());
        assertEquals("https://apod.nasa.gov/apod/image/full.jpg", p.image());
        assertEquals("", p.error());
        var first = parser.parse("<center><a href='image/full.jpg'><img src='thumb.jpg'></a></center>",
                URI.create("https://apod.nasa.gov/apod/ap950616.html"));
        assertTrue(first.boundary()); assertEquals("", first.previous()); assertEquals("", first.error());
    }
    @Test void historicalRedirectToTodayFailsWithHistoricalNavigation() {
        var p = parser.parse(TestSupport.page("October 21, 2026", TestSupport.image("/today.jpg"), "/yesterday"),
                URI.create("https://science.nasa.gov/apod/"), URI.create("https://apod.nasa.gov/apod/ap200610.html"));
        assertEquals(LocalDate.of(2020, 6, 10), p.date());
        assertEquals("https://apod.nasa.gov/apod/ap200609.html", p.previous());
        assertEquals("", p.image()); assertFalse(p.video());
        assertTrue(p.error().contains("response date"));
    }
    @Test void matchingHistoricalRedirectKeepsImage() {
        var p = parser.parse(TestSupport.page("June 10, 2020", TestSupport.image("/correct.jpg"), null),
                URI.create("https://science.nasa.gov/image-article/apod-2020-june-10-example/"),
                URI.create("https://apod.nasa.gov/apod/ap200610.html"));
        assertEquals("https://science.nasa.gov/correct.jpg", p.image()); assertEquals("", p.error());
        assertEquals("https://apod.nasa.gov/apod/ap200609.html", p.previous());
    }
}
