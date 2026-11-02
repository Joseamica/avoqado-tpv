package com.jaac.avoqado_tpv.features.tables.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository
import com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository
import java.lang.reflect.Proxy

/** Configuración explícita OFF en prefs propias; nunca toca sesión/llaves/obligaciones del aparato. */
internal fun shiftFixture(db: AvoqadoDatabase): Pair<SecureStorage, ShiftRepository> {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("task5-tables-" + name, mode)
        override fun deleteSharedPreferences(name: String) = super.deleteSharedPreferences("task5-tables-" + name)
    }
    val storage = SecureStorage(context).apply {
        saveVenueId("venue-a"); saveStaffId("fixture-staff"); saveToken("fixture-token")
        putBoolean("enable_shifts", false)
        putString("permissions_cache", "venue-a|fixture-staff\npayments:create")
    }
    val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ -> error("No HTTP in outbox fixture") } as ApiService
    val refunds = Proxy.newProxyInstance(RefundQueueRepository::class.java.classLoader, arrayOf(RefundQueueRepository::class.java)) { _, _, _ -> error("No refund in outbox fixture") } as RefundQueueRepository
    return storage to ShiftRepository(api, db, storage, db.cachedShiftDao(), refunds, appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext())
}
