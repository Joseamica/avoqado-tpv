package com.jaac.avoqado_tpv.core.data.network.dto

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the server-owned cash-reconciliation capability.
 *
 * The effective flag belongs at terminal-config `data` root. It is copied to
 * the local read model, but it must never be emitted by the mutable terminal
 * settings PUT DTO.
 */
class TerminalConfigCashReconciliationTest {

    private val gson = Gson()
    private lateinit var preferences: InMemorySharedPreferences
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        preferences = InMemorySharedPreferences()
        secureStorage = secureStorageUsing(preferences)
    }

    @Test
    fun `terminal config parses explicit root capability`() {
        val response = gson.fromJson(
            terminalConfigJson("\"cashReconciliationEnabled\":true,"),
            TerminalConfigResponse::class.java,
        )

        assertThat(response.data.cashReconciliationEnabled).isTrue()
    }

    @Test
    fun `old server payload leaves root capability absent`() {
        val response = gson.fromJson(
            terminalConfigJson(),
            TerminalConfigResponse::class.java,
        )

        assertThat(response.data.cashReconciliationEnabled).isNull()
    }

    @Test
    fun `domain capability defaults disabled`() {
        assertThat(TpvSettings.DEFAULT.cashReconciliationEnabled).isFalse()
    }

    @Test
    fun `mutable settings payload never serializes server-owned capability`() {
        val json = gson.toJson(
            TpvSettings(cashReconciliationEnabled = true).toDto(),
        )

        assertThat(json).doesNotContain("cashReconciliationEnabled")
    }

    @Test
    fun `unset secure cache keeps capability disabled`() {
        assertThat(secureStorage.isCashReconciliationEnabled()).isFalse()
        assertThat(secureStorage.getTpvSettings().cashReconciliationEnabled).isFalse()
    }

    @Test
    fun `secure cache persists capability across storage instances`() {
        secureStorage.saveTpvSettings(TpvSettings(cashReconciliationEnabled = true))

        val afterRestart = secureStorageUsing(preferences)

        assertThat(afterRestart.isCashReconciliationEnabled()).isTrue()
        assertThat(afterRestart.getTpvSettings().cashReconciliationEnabled).isTrue()
    }

    @Test
    fun `clearing TPV settings removes cached capability`() {
        secureStorage.saveTpvSettings(TpvSettings(cashReconciliationEnabled = true))

        secureStorage.clearTpvSettings()

        assertThat(secureStorage.isCashReconciliationEnabled()).isFalse()
    }

    private fun terminalConfigJson(rootCapability: String = ""): String =
        """
        {
          "success": true,
          "data": {
            $rootCapability
            "terminal": {
              "id": "term-1",
              "serialNumber": "SN-001",
              "name": "Terminal 1",
              "type": "TPV_ANDROID",
              "status": "ACTIVE",
              "venueId": "venue-1",
              "venue": null
            },
            "merchantAccounts": [],
            "tpvSettings": null
          }
        }
        """.trimIndent()

    private fun secureStorageUsing(preferences: SharedPreferences): SecureStorage {
        val storage = SecureStorage(mockk<Context>(relaxed = true))
        val delegate = SecureStorage::class.java.getDeclaredField("encryptedPrefs\$delegate")
        delegate.isAccessible = true
        delegate.set(storage, lazyOf(preferences))
        return storage
    }

    /** Minimal in-memory boundary double; SecureStorage itself remains real. */
    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putStringSet(
                key: String,
                values: Set<String>?,
            ): SharedPreferences.Editor = apply { this.values[key] = values?.toSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { values[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { values.remove(key) }

            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
