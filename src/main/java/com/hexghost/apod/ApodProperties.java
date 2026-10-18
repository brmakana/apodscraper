package com.hexghost.apod;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("apod")
public record ApodProperties(
        @DefaultValue("https://science.nasa.gov/apod/") URI initialUrl,
        URI startUrl,
        @DefaultValue("./downloadedImages") Path downloadDirectory,
        @DefaultValue("./data") Path stateDirectory,
        @DefaultValue("100ms") Duration requestInterval,
        @DefaultValue("20") int rateLimitPerSecond,
        @DefaultValue("2") int downloadWorkers,
        @DefaultValue("16") int queueSize,
        @DefaultValue("15s") Duration connectTimeout,
        @DefaultValue("120s") Duration requestTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue("60s") Duration shutdownGracePeriod,
        @DefaultValue("false") boolean retryFailed,
        @DefaultValue("0") int pageLimit) {
    public ApodProperties {
        Urls.validate(initialUrl);
        if (startUrl != null) Urls.validate(startUrl);
        if (downloadDirectory == null || stateDirectory == null || rateLimitPerSecond < 1
                || downloadWorkers < 1 || queueSize < 1 || maxAttempts < 1 || pageLimit < 0)
            throw new IllegalArgumentException("Invalid apod paths/counts: workers, queue, rate and attempts must be positive");
        for (var d : new Duration[]{requestInterval, connectTimeout, requestTimeout, retryBackoff, shutdownGracePeriod})
            if (d == null || d.isNegative()) throw new IllegalArgumentException("apod durations must be nonnegative");
        if (connectTimeout.isZero() || requestTimeout.isZero())
            throw new IllegalArgumentException("Network timeouts must be positive");
    }
}
