package org.fuin.porelo.message.matrix;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/** Fixed resource budgets for one account's local homeserver, independent of the host's cores. */
final class HomeserverBounds {

    static final SequencedMap<String, String> SETTINGS;

    static {
        final SequencedMap<String, String> settings = new LinkedHashMap<>();
        // Leave room for the server's other threads below the container's PID limit.
        settings.put("TUWUNEL_DB_POOL_MAX_WORKERS", "64");
        // Keep the database read cache from growing with the host's cores.
        settings.put("TUWUNEL_DB_CACHE_CAPACITY_MB", "256");
        // Reduce the LRU caches for a homeserver serving one account's tasks.
        settings.put("TUWUNEL_CACHE_CAPACITY_MODIFIER", "0.5");
        // Keep RocksDB's background work within the container's CPU budget.
        settings.put("TUWUNEL_ROCKSDB_PARALLELISM_THREADS", "2");
        SETTINGS = Collections.unmodifiableSequencedMap(settings);
    }

    static final List<String> PODMAN_FLAGS =
            List.of(
                    // Bound thread creation while leaving room beyond the database pool.
                    "--pids-limit=512",
                    // Keep one account's homeserver from exhausting the host's memory.
                    "--memory=1g",
                    // Keep background work from consuming every host core.
                    "--cpus=2");

    private HomeserverBounds() {}
}
