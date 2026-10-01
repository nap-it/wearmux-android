package com.wearmux.android.integration.headless;

import java.net.URI;

public final class BridgeConfiguration {
    private BridgeConfiguration() { }
    public static boolean valid(String endpoint, String token) {
        if (endpoint == null || token == null || token.length() < 16 || token.length() > 256 || whitespace(token)) return false;
        try { URI uri = URI.create(endpoint); return ("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null && "/android-ble".equals(uri.getPath()) && !whitespace(endpoint); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    private static boolean whitespace(String value) { for (int i=0;i<value.length();i++) if (Character.isWhitespace(value.charAt(i))) return true; return false; }
}
