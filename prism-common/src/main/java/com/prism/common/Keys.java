package com.prism.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Virtual keys are never stored in plain text: the database keeps a SHA-256 hash and a masked hint. */
public final class Keys {

    private Keys() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** "prism-sk-search-1a2b3c" becomes "prism-sk-sea…2b3c": recognisable, but useless if leaked. */
    public static String hint(String key) {
        if (key == null || key.isBlank()) {
            return "(none)";
        }
        if (key.length() <= 10) {
            return key.charAt(0) + "…";
        }
        return key.substring(0, 12) + "…" + key.substring(key.length() - 4);
    }
}
