package com.example.skybuddy.shared.data

/**
 * Compact bitfield codec for blocked-region beacons.
 *
 * Instead of sending full node IDs like `SBBLK:ENTRANCE,GATE_C1,GATE_C2`,
 * which quickly exceeds the 29-char BLE device-name limit, this codec
 * assigns each known node a fixed index (0–31) and encodes the blocked set
 * as a hex bitfield:
 *
 *   `SBBLK:#00000003`   ← nodes at index 0 and 1 are blocked
 *
 * 32 nodes → 32 bits → 4 bytes → 8 hex chars → payload is always
 * `SBBLK:#` + 8 hex chars = **15 chars total** — well within 29.
 *
 * The canonical node order is defined in [NODE_ORDER] and must match
 * between the broadcaster (SkySecurity) and receiver (SkyBuddy).
 * Both share this module, so they stay in sync automatically.
 */
object BeaconCodec {

    /**
     * Canonical order of all node IDs on the map. Index = bit position.
     * Keep this in sync with `surat_layout.json`.
     */
    val NODE_ORDER: List<String> = listOf(
        "ENTRANCE",                // 0
        "080_ARRIVAL_LOUNGE",      // 1
        "COFFEE_BEAN",             // 2
        "PIZZA_HUT",              // 3
        "SUBWAY",                 // 4
        "BAGGAGE_DROP",           // 5
        "SECURITY_CHECK",         // 6
        "ZIMSON",                 // 7
        "STARBUCKS",              // 8
        "DUTY_FREE",              // 9
        "MICHAEL_KORS",           // 10
        "RELAY",                  // 11
        "BOSS",                   // 12
        "ANAND_SWEETS",           // 13
        "JAMIE_OLIVER",           // 14
        "WOLFGANG_PUCK",          // 15
        "THE_IRISH_HOUSE",        // 16
        "ARMANI_EXCHANGE",        // 17
        "BOMBAY_BRASSERIE",       // 18
        "SWAROVSKI",              // 19
        "SS_BEAUTY",              // 20
        "FRESH_HEALTHY",          // 21
        "LA_MADELEINE",           // 22
        "T2_080_DOMESTIC_LOUNGE", // 23
        "GATE_C1",                // 24
        "GATE_C2",                // 25
        "GATE_C3",                // 26
        "GATE_C6",                // 27
        "GATE_D1",                // 28
        "GATE_D2",                // 29
        "GATE_D9",                // 30
        "GATE_D11"                // 31
    )

    /** Fast lookup: node ID → bit index */
    private val NODE_INDEX: Map<String, Int> =
        NODE_ORDER.withIndex().associate { (i, id) -> id to i }

    const val PREFIX = "SBBLK:"

    // ── Encoding (Security → BLE) ──────────────────────────────────────

    /**
     * Encode a set of blocked node IDs into a compact BLE-name payload.
     *
     * @return e.g. `"SBBLK:#00000041"` (nodes 0 and 6 blocked) or
     *         `"SBBLK:"` when the set is empty (clear signal).
     */
    fun encodeBlocked(nodeIds: Set<String>): String {
        if (nodeIds.isEmpty()) return PREFIX // clear signal

        var bits = 0
        for (id in nodeIds) {
            val idx = NODE_INDEX[id] ?: continue // skip unknown IDs
            bits = bits or (1 shl idx)
        }
        // 8-digit zero-padded hex, prefixed with '#' to distinguish
        // from the old comma-separated format during rollout.
        return "${PREFIX}#${bits.toUInt().toString(16).padStart(8, '0').uppercase()}"
    }

    // ── Decoding (BLE → SkyBuddy) ──────────────────────────────────────

    /**
     * Decode a BLE-name payload back into a set of node IDs.
     *
     * Handles both formats:
     * - New bitfield: `SBBLK:#00000041`
     * - Legacy CSV:   `SBBLK:ENTRANCE,GATE_C1`
     *
     * @return the set of blocked node IDs (empty set = clear signal).
     */
    fun decodeBlocked(payload: String): Set<String> {
        val body = payload.removePrefix(PREFIX)
        if (body.isEmpty()) return emptySet() // clear signal

        // New bitfield format
        if (body.startsWith("#")) {
            val hex = body.removePrefix("#")
            // Take exactly 8 characters for the bitfield to ignore any trailing 
            // data (like fragments from other fields)
            val bits = hex.take(8).toUIntOrNull(16)?.toInt() ?: return emptySet()
            val result = mutableSetOf<String>()
            for (i in NODE_ORDER.indices) {
                if (bits and (1 shl i) != 0) {
                    result.add(NODE_ORDER[i])
                }
            }
            return result
        }

        // Legacy comma-separated format (backwards compat)
        return body.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
