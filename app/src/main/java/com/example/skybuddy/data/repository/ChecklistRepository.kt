package com.example.skybuddy.data.repository

import android.app.Application
import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class ChecklistItemEntity(
    val id: String,
    val text: String,
    val completed: Boolean
)

@Singleton
class ChecklistRepository @Inject constructor(
    private val application: Application
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, ChecklistItemEntity::class.java)
    private val adapter = moshi.adapter<List<ChecklistItemEntity>>(listType)

    // Store flows per flight
    private val flows = mutableMapOf<String, MutableStateFlow<List<ChecklistItemEntity>>>()

    private fun getPrefs(flightNumber: String) =
        application.getSharedPreferences("skybuddy_checklist_$flightNumber", Context.MODE_PRIVATE)

    fun observeItems(flightNumber: String): StateFlow<List<ChecklistItemEntity>> {
        if (!flows.containsKey(flightNumber)) {
            val prefs = getPrefs(flightNumber)
            val json = prefs.getString("items", null)
            val initial = if (json != null) {
                try {
                    adapter.fromJson(json) ?: defaultChecklist()
                } catch (e: Exception) {
                    defaultChecklist()
                }
            } else {
                defaultChecklist()
            }
            flows[flightNumber] = MutableStateFlow(initial)
        }
        return flows[flightNumber]!!.asStateFlow()
    }

    fun addItem(flightNumber: String, text: String): String {
        val id = "item_${System.currentTimeMillis()}"
        val item = ChecklistItemEntity(id, text, false)
        val flow = flows[flightNumber] ?: return id
        val current = flow.value.toMutableList()
        current.add(item)
        saveAndEmit(flightNumber, current)
        return id
    }

    fun removeItem(flightNumber: String, id: String) {
        val flow = flows[flightNumber] ?: return
        val current = flow.value.filter { it.id != id }
        saveAndEmit(flightNumber, current)
    }

    fun setCompleted(flightNumber: String, id: String, completed: Boolean) {
        val flow = flows[flightNumber] ?: return
        val current = flow.value.map { if (it.id == id) it.copy(completed = completed) else it }
        saveAndEmit(flightNumber, current)
    }

    private fun saveAndEmit(flightNumber: String, items: List<ChecklistItemEntity>) {
        val flow = flows[flightNumber] ?: return
        flow.value = items
        val json = adapter.toJson(items)
        getPrefs(flightNumber).edit().putString("items", json).apply()
    }

    private fun defaultChecklist(): List<ChecklistItemEntity> = listOf(
        ChecklistItemEntity("packBags", "Pack bags", false),
        ChecklistItemEntity("checkIn", "Check in online", false),
        ChecklistItemEntity("bringId", "Bring ID / Passport", false),
        ChecklistItemEntity("travelDocs", "Prepare travel docs (Visas, Boarding Pass)", false),
        ChecklistItemEntity("chargeDevices", "Charge devices & power banks", false),
        ChecklistItemEntity("downloadMedia", "Download offline media (Movies, Music)", false),
        ChecklistItemEntity("weighLuggage", "Weigh luggage", false),
        ChecklistItemEntity("lockHome", "Lock doors & windows", false)
    )
}
