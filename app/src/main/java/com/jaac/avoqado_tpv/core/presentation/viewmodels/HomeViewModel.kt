package com.jaac.avoqado_tpv.core.presentation.viewmodels

import androidx.lifecycle.ViewModel
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

/**
 * HomeViewModel
 *
 * Handles home screen actions including logout.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    /**
     * Logout user
     *
     * Clears session and stops heartbeat worker.
     */
    fun logout() {
        Timber.d("🚪 User initiated logout")

        // Clear session from SecureStorage
        authRepository.logout()

        Timber.d("✅ Logout complete")
    }
}
