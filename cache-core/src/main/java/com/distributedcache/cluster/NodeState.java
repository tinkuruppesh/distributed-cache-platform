package com.distributedcache.cluster;

/**
 * Lifecycle of a node as seen by the cluster manager.
 * ACTIVE = alive and serving (the "ALIVE/ACTIVE" states of the spec are merged: a reachable serving node is ACTIVE).
 */
public enum NodeState {
    JOINING,      // registered, data being synchronized, not yet in the hash ring
    ACTIVE,       // healthy, in the ring, serving requests
    SUSPECTED,    // missed heartbeats; still in the ring but tried last
    FAILED,       // confirmed dead; removed from the ring, replicas promoted
    RECOVERING,   // came back; synchronizing before re-entering the ring
    MAINTENANCE,  // drained on purpose by an operator
    REMOVED
}
