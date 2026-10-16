package com.openpms.cicd;

import java.net.URI;

/** Build and deployment links: only an http(s) address on the same origin as the allowed base (no user part); else "". */
final class CicdLinks {
    private CicdLinks() {}

    static String sameOrigin(String url, String base) {
        if (url == null || url.isBlank() || base == null || base.isBlank()) return "";
        try {
            var u = URI.create(url.strip());
            var b = URI.create(base.strip());
            var scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null) return "";
            if (!scheme.equalsIgnoreCase(b.getScheme() == null ? "" : b.getScheme()) || !u.getHost().equalsIgnoreCase(b.getHost() == null ? "" : b.getHost())
                    || port(u) != port(b)) return "";
            return url.strip();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static int port(URI u) {
        if (u.getPort() != -1) return u.getPort();
        return "http".equalsIgnoreCase(u.getScheme()) ? 80 : 443;
    }
}
