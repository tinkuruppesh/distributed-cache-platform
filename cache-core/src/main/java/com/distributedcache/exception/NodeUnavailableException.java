package com.distributedcache.exception;

/** A single cache node did not answer (timeout, refused connection, closed socket). */
public class NodeUnavailableException extends CacheException {
    private final String node;
    public NodeUnavailableException(String node, String message) { super("Node " + node + " unavailable: " + message); this.node = node; }
    public String node() { return node; }
}
