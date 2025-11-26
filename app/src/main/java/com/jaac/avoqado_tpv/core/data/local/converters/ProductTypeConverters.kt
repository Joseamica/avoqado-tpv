package com.jaac.avoqado_tpv.core.data.local.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup

/**
 * Room TypeConverters for Product entities.
 *
 * **Purpose:** Serialize complex types (ModifierGroup list) to JSON strings.
 *
 * **Implementation:**
 * - Uses Gson for JSON serialization (already in dependencies)
 * - ModifierGroups: List<ModifierGroup> → JSON string
 *
 * **Usage:**
 * Add @TypeConverters(ProductTypeConverters::class) to AvoqadoDatabase
 */
class ProductTypeConverters {

    private val gson = Gson()

    /**
     * Convert JSON string to List<ModifierGroup>.
     *
     * @param value JSON string (from Room database)
     * @return Deserialized list of modifier groups
     */
    @TypeConverter
    fun fromModifierGroupsJson(value: String?): List<ModifierGroup> {
        if (value == null || value == "[]") return emptyList()

        val type = object : TypeToken<List<ModifierGroup>>() {}.type
        return gson.fromJson(value, type)
    }

    /**
     * Convert List<ModifierGroup> to JSON string.
     *
     * @param modifierGroups List of modifier groups (from domain model)
     * @return JSON string (for Room storage)
     */
    @TypeConverter
    fun toModifierGroupsJson(modifierGroups: List<ModifierGroup>): String {
        return gson.toJson(modifierGroups)
    }
}
