package com.example.skybuddy.ai.tools

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

// ── Data Models ───────────────────────────────────────────────────────────────

@Keep
data class MenuItem(
    val item: String,
    val price: Int,
    val category: String,
    val isVeg: Boolean,
    val popular: Boolean,
    val description: String
)

@Keep
data class Timings(val open: String, val close: String, val days: String)

@Keep
data class PoiNode(
    val poiId: String,
    val name: String,
    val category: String,
    val cuisine: List<String>,
    val terminal: String,
    val zone: String,
    val floor: Int,
    val mapX: Double,   // Double, not Float — Gson deserializes JSON numbers as Double
    val mapY: Double,
    val isAirside: Boolean,
    val timings: Timings?,
    val priceRange: String,
    val rating: Double,
    val tags: List<String>,
    val menu: List<MenuItem>,
    val walkingTimeFromGates: Map<String, String>,
    val notes: String?
)

@Keep
data class ServiceNode(
    val serviceId: String,
    val label: String,
    val terminal: String,
    val floor: Int,
    val mapX: Double,   // Double, not Float — Gson deserializes JSON numbers as Double
    val mapY: Double,
    val tags: List<String>
)

@Keep
data class KbSearchResult(
    val poiId: String,
    val name: String,
    val category: String,
    val cuisine: List<String>,
    val terminal: String,
    val isAirside: Boolean,
    val floor: Int,
    val mapX: Double,
    val mapY: Double,
    val timings: Timings?,
    val priceRange: String,
    val rating: Double,
    val popularMenuItems: List<MenuItem>,
    val walkingFromNearestGate: String?,   // "G12: 2 min" or null if no data
    val notes: String?,
    val fuzzyScore: Double
)

@Keep
data class KbRoot(
    val pois: List<PoiNode>,
    val services: List<ServiceNode>
)

@Keep
data class KbNodeResult(
    val id: String,
    val name: String,
    val type: String,
    val mapX: Double,
    val mapY: Double,
    val score: Double
)

// ── Tool Implementation ───────────────────────────────────────────────────────

@Singleton
class AirportKnowledgeBaseTool @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val pois: List<PoiNode>
    private val services: List<ServiceNode>
    private val similarity = JaroWinklerSimilarity()
    // Single shared Gson instance — avoids re-allocating per call
    private val gson = Gson()

    init {
        var p: List<PoiNode> = emptyList()
        var s: List<ServiceNode> = emptyList()
        try {
            val json = context.assets.open("bangalore_airport_kb.json")
                .bufferedReader().use { it.readText() }
            val root = gson.fromJson(json, KbRoot::class.java)
            if (root != null) {
                p = root.pois ?: emptyList()
                s = root.services ?: emptyList()
                android.util.Log.d("SkyBuddy", "KB Tool: Initialized with ${p.size} POIs and ${s.size} services")
            } else {
                android.util.Log.e("SkyBuddy", "KB Tool: Root is null after parsing")
            }
        } catch (e: Exception) {
            android.util.Log.e("SkyBuddy", "KB Tool: Initialization failed", e)
        }
        pois = p
        services = s
    }

    /**
     * Search the BLR airport knowledge base. Returns a JSON string to be
     * injected back into the LLM's context as a tool result.
     *
     * @param query      Natural-language query from the user.
     * @param terminal   Optional hard filter: "T1", "T2", or "ALL".
     * @param isAirside  Optional hard filter for post/pre security.
     * @param isVegOnly  Optional filter to surface only venues with veg items.
     * @param category   Optional category filter: RESTAURANT, CAFE, RETAIL, LOUNGE, PHARMACY, SERVICE.
     * @param nearGate   Optional gate ID to prefer nearby results, e.g. "G12".
     * @param maxWalkMin Maximum walking minutes from nearGate.
     * @param openAt     24-hr time string "HH:MM" — only returns venues open at this time.
     * @param topK       Maximum number of POI results (default 5, capped at 10).
     */
    fun search(
        query: String,
        terminal: String? = null,
        isAirside: Boolean? = null,
        isVegOnly: Boolean? = null,
        category: String? = null,
        nearGate: String? = null,
        maxWalkMin: Int? = null,
        openAt: String? = null,
        currentX: Double? = null,
        currentY: Double? = null,
        maxRadius: Double? = null,
        topK: Int = 5
    ): String {
        val results = searchRaw(query, terminal, isAirside, isVegOnly, category, nearGate, maxWalkMin, openAt, currentX, currentY, maxRadius, topK)
        
        val result = gson.toJson(
            mapOf(
                "query" to query,
                "pois" to results.first,
                "services" to results.second,
                "totalMatches" to (results.first.size + results.second.size)
            )
        )
        android.util.Log.d("SkyBuddy", "KB Tool: Found ${results.first.size} POIs and ${results.second.size} services. Result length: ${result.length}")
        return result
    }

    /**
     * Exposes raw fuzzy search results for UI consumption (e.g. Map search bar).
     */
    fun searchRaw(
        query: String,
        terminal: String? = null,
        isAirside: Boolean? = null,
        isVegOnly: Boolean? = null,
        category: String? = null,
        nearGate: String? = null,
        maxWalkMin: Int? = null,
        openAt: String? = null,
        currentX: Double? = null,
        currentY: Double? = null,
        maxRadius: Double? = null,
        topK: Int = 5
    ): Pair<List<KbSearchResult>, List<Map<String, Any>>> {
        android.util.Log.d("SkyBuddy", "KB Tool: Searching for '$query' (terminal=$terminal, airside=$isAirside, cat=$category)")
        val k = topK.coerceIn(1, 10)
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            android.util.Log.w("SkyBuddy", "KB Tool: Empty query tokens for '$query'")
        }

        // 1. Score every POI
        val scoredPois = pois.map { poi -> poi to fuzzyScore(queryTokens, poi) }

        // 2. Score service nodes
        val scoredServices = services.map { svc ->
            svc to tokenFuzzyMatch(queryTokens, svc.tags + listOf(svc.label))
        }

        // 3. Hard-filter and rank POIs
        val filteredPois = scoredPois
            .filter { (_, score) -> score > 0.25 }
            .filter { (poi, _) -> terminal == null || terminal == "ALL" || poi.terminal == terminal }
            .filter { (poi, _) -> isAirside == null || poi.isAirside == isAirside }
            .filter { (poi, _) -> category == null || poi.category.equals(category, ignoreCase = true) }
            .filter { (poi, _) ->
                // Bug fix: an empty menu (e.g. retail/pharmacy) should NOT pass a veg-food filter
                if (isVegOnly == true) poi.menu.isNotEmpty() && poi.menu.any { it.isVeg }
                else true
            }
            .filter { (poi, _) ->
                if (nearGate != null && maxWalkMin != null) {
                    val walkStr = poi.walkingTimeFromGates[nearGate] ?: return@filter false
                    val minutes = walkStr.replace(" min", "").trim().toIntOrNull() ?: 99
                    minutes <= maxWalkMin
                } else true
            }
            .filter { (poi, _) ->
                if (openAt != null && poi.timings != null) isOpenAt(poi.timings, openAt)
                else true
            }
            .filter { (poi, _) ->
                if (currentX != null && currentY != null && maxRadius != null) {
                    val dist = kotlin.math.hypot(poi.mapX - currentX, poi.mapY - currentY)
                    dist <= maxRadius
                } else true
            }
            .sortedByDescending { (_, score) -> score }
            .take(k)

        // 4. Hard-filter and rank services
        val filteredServices = scoredServices
            .filter { (_, score) -> score > 0.30 }
            .filter { (svc, _) -> terminal == null || terminal == "ALL" || svc.terminal == terminal }
            .sortedByDescending { (_, score) -> score }
            .take(3)

        // 5. Shape POI results
        val poiResults = filteredPois.map { (poi, score) ->
            KbSearchResult(
                poiId = poi.poiId,
                name = poi.name,
                category = poi.category,
                cuisine = poi.cuisine,
                terminal = poi.terminal,
                isAirside = poi.isAirside,
                floor = poi.floor,
                mapX = poi.mapX,
                mapY = poi.mapY,
                timings = poi.timings,
                priceRange = poi.priceRange,
                rating = poi.rating,
                popularMenuItems = poi.menu.filter { it.popular }.take(5),
                walkingFromNearestGate = nearestGateWalk(poi, nearGate),
                notes = poi.notes,
                fuzzyScore = (score * 100).toInt() / 100.0
            )
        }

        // 6. Shape service results
        val serviceResults = filteredServices.map { (svc, score) ->
            mapOf(
                "type" to "SERVICE",
                "serviceId" to svc.serviceId,
                "label" to svc.label,
                "terminal" to svc.terminal,
                "floor" to svc.floor,
                "mapX" to svc.mapX,
                "mapY" to svc.mapY,
                "fuzzyScore" to (score * 100).toInt() / 100.0
            )
        }

        return Pair(poiResults, serviceResults)
    }

    /**
     * Fast search that returns unified lightweight nodes for the map search bar.
     */
    fun searchMapNodes(query: String, topK: Int = 8): List<KbNodeResult> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val scoredPois = pois.map { poi -> poi to fuzzyScore(queryTokens, poi) }
            .filter { (_, score) -> score > 0.25 }
            .map { (poi, score) ->
                KbNodeResult(poi.poiId, poi.name, poi.category.uppercase(), poi.mapX, poi.mapY, score)
            }

        val scoredServices = services.map { svc ->
            svc to tokenFuzzyMatch(queryTokens, svc.tags + listOf(svc.label))
        }
            .filter { (_, score) -> score > 0.30 }
            .map { (svc, score) ->
                KbNodeResult(svc.serviceId, svc.label, "SERVICE", svc.mapX, svc.mapY, score)
            }

        return (scoredPois + scoredServices)
            .sortedByDescending { it.score }
            .take(topK)
    }

    // ── Fuzzy scoring engine ──────────────────────────────────────────────────

    private fun fuzzyScore(queryTokens: List<String>, poi: PoiNode): Double {
        val tagScore     = tokenFuzzyMatch(queryTokens, poi.tags)                        * 1.8
        val nameScore    = tokenFuzzyMatch(queryTokens, tokenize(poi.name))              * 1.5
        val cuisineScore = tokenFuzzyMatch(queryTokens, poi.cuisine)                     * 1.3
        val menuScore    = menuFuzzyMatch(queryTokens, poi.menu)                         * 1.2
        val corpusScore  = tokenFuzzyMatch(queryTokens, buildCorpus(poi))                * 1.0
        return max(tagScore, max(nameScore, max(cuisineScore, max(menuScore, corpusScore))))
    }

    private fun tokenFuzzyMatch(queryTokens: List<String>, corpus: List<String>): Double {
        if (queryTokens.isEmpty() || corpus.isEmpty()) return 0.0
        val corpusStr = corpus.joinToString(" ").lowercase()
        var best = 0.0
        for (qToken in queryTokens) {
            if (corpusStr.contains(qToken)) { best = max(best, 0.95); continue }
            for (cToken in corpus.map { it.lowercase() }) {
                val sim = similarity.apply(qToken, cToken)
                best = max(best, sim)
            }
        }
        return best
    }

    private fun menuFuzzyMatch(queryTokens: List<String>, menu: List<MenuItem>): Double {
        if (menu.isEmpty()) return 0.0
        val menuText = menu.flatMap { tokenize(it.item) + tokenize(it.description) }
        return tokenFuzzyMatch(queryTokens, menuText)
    }

    private fun buildCorpus(poi: PoiNode): List<String> =
        poi.tags + poi.cuisine + tokenize(poi.name) +
        poi.menu.flatMap { tokenize(it.item) } +
        listOf(poi.category, poi.zone, poi.terminal)

    private fun tokenize(s: String): List<String> =
        s.lowercase().split(Regex("[\\s,/\\-]+")).filter { it.length > 1 }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isOpenAt(timings: Timings, timeStr: String): Boolean {
        val parts = timeStr.split(":")
        if (parts.size < 2) return true
        val (qH, qM) = parts.map { it.toIntOrNull() ?: 0 }
        val oParts = timings.open.split(":")
        val cParts = timings.close.split(":")
        val oH = oParts.getOrNull(0)?.toIntOrNull() ?: 0
        val oM = oParts.getOrNull(1)?.toIntOrNull() ?: 0
        val cH = cParts.getOrNull(0)?.toIntOrNull() ?: 23
        val cM = cParts.getOrNull(1)?.toIntOrNull() ?: 59
        val qMin = qH * 60 + qM
        val oMin = oH * 60 + oM
        val cMin = cH * 60 + cM
        return if (cMin > oMin) qMin in oMin..cMin
               else qMin >= oMin || qMin <= cMin  // handles overnight hours
    }

    private fun nearestGateWalk(poi: PoiNode, preferredGate: String?): String? {
        if (preferredGate != null) {
            // Return the preferred gate time if available, otherwise fall back to nearest known gate
            val exact = poi.walkingTimeFromGates[preferredGate]
            if (exact != null) return "$preferredGate: $exact"
        }
        // Fall back to the single nearest gate in the walking table
        return poi.walkingTimeFromGates.entries
            .minByOrNull { it.value.replace(" min", "").trim().toIntOrNull() ?: 99 }
            ?.let { "${it.key}: ${it.value}" }
    }
}
