package com.distributedcache.networking;

public record NodeAddress(String id, String host, int port) {
    @Override public String toString() { return id + "@" + host + ":" + port; }
}
