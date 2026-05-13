package com.jaac.avoqado_tpv.features.checkout.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-backed `SavedCartsRepository`. Lives outside
 * EncryptedSharedPreferences because saved carts are not sensitive — they
 * only contain product references and locally-computed totals.
 *
 * Carts are stored as a JSON array under [KEY_ALL]. We keep a backing
 * MutableStateFlow to expose a reactive [observeAll] for the UI list.
 */
@Singleton
class SavedCartsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
) : SavedCartsRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()
    private val listType = object : TypeToken<List<SavedCart>>() {}.type

    private val state = MutableStateFlow(loadFromDisk())

    override fun observeAll(): Flow<List<SavedCart>> = state.asStateFlow()

    override suspend fun getAll(): List<SavedCart> = state.value

    override suspend fun save(cart: SavedCart) = withContext(Dispatchers.IO) {
        val updated = (listOf(cart) + state.value.filterNot { it.id == cart.id })
            .sortedByDescending { it.createdAt }
        persist(updated)
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        persist(state.value.filterNot { it.id == id })
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        persist(emptyList())
    }

    private fun persist(carts: List<SavedCart>) {
        prefs.edit().putString(KEY_ALL, gson.toJson(carts)).apply()
        state.value = carts
    }

    private fun loadFromDisk(): List<SavedCart> {
        val raw = prefs.getString(KEY_ALL, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<SavedCart>>(raw, listType) ?: emptyList() }
            .getOrElse { emptyList() }
    }

    companion object {
        private const val PREFS_NAME = "avoqado_checkout_saved_carts"
        private const val KEY_ALL = "saved_carts"
    }
}
