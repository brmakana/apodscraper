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
        assertFalse(p.image().contains("?")); assertTrue(p.previous().contains("apod-2019-june-23-"));
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
}
