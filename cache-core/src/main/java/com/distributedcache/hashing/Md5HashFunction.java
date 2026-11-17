package com.distributedcache.hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** MD5 is used only for its uniform spread (NOT for security). First 8 bytes become a signed long. */
public final class Md5HashFunction implements HashFunction {
    private static final ThreadLocal<MessageDigest> MD = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    });

    @Override
    public long hash(String input) {
        byte[] d = MD.get().digest(input.getBytes(StandardCharsets.UTF_8));
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xFF);
        return h;
    }
}
