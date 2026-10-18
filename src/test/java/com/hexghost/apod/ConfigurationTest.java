package com.hexghost.apod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path root;
    @Test void defaultsAndDurationBindingWork() {
        var p = TestSupport.config(root);
        assertEquals(2, p.downloadWorkers()); assertEquals(16, p.queueSize()); assertFalse(p.retryFailed());
        assertEquals(Duration.ofSeconds(15), p.connectTimeout());
    }
    @Test void invalidSettingsFailAtStartup() {
        for (String key : new String[]{"download-workers", "queue-size", "rate-limit-per-second", "max-attempts"})
            assertThrows(Exception.class, () -> TestSupport.config(root, key, "0"));
        assertThrows(Exception.class, () -> TestSupport.config(root, "request-interval", "-1ms"));
        assertThrows(Exception.class, () -> TestSupport.config(root, "request-timeout", "0s"));
        assertThrows(Exception.class, () -> TestSupport.config(root, "initial-url", "file:///tmp/page.html"));
    }
    @Test void canonicalUrlsKeepEscapesAndDropFragmentsAndDefaultPorts() {
        assertEquals("https://example.test/a%20b?q=a%2Fb", Urls.canonical(java.net.URI.create("https://EXAMPLE.test:443/a%20b?q=a%2Fb#fragment")));
    }
}
