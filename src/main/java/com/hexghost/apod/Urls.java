package com.hexghost.apod;

import java.net.URI;
import java.net.URISyntaxException;

final class Urls {
    private Urls() {}
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
