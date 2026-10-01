package com.example.peciwearables.integration.headless;

import java.net.URI;

public final class BridgeConfiguration {
    private BridgeConfiguration() { }
    public static boolean valid(String endpoint, String token) {
        token = token == null ? "" : token;
        if (!token.isEmpty() && (token.length() < 16 || token.length() > 256 || whitespace(token))) return false;
        try { URI uri = URI.create(endpoint); return ("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null && "/android-ble".equals(uri.getPath()) && !whitespace(endpoint); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    public static String authorizationHeader(String token) { return token == null || token.isEmpty() ? null : "Bearer " + token; }
    private static boolean whitespace(String value) { for (int i=0;i<value.length();i++) if (Character.isWhitespace(value.charAt(i))) return true; return false; }
}
