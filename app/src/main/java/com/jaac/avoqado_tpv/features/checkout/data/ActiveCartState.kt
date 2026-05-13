package com.jaac.avoqado_tpv.features.checkout.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global singleton that lets non-checkout screens observe whether the user
 * has an active cart in progress.
 *
 * Ported from avoqado-android `pos/data/ActiveCartState.kt`. The home screen
 * uses this to nudge the operator if they navigate away while items are
 * pending — and to display a badge on the "Cobrar" button.
 *
 * The singleton lives outside `CheckoutViewModel` because the home screen
 * can't hold a reference to a screen-scoped ViewModel.
 */
@Singleton
class ActiveCartState @Inject constructor() {

    private val _itemCount = MutableStateFlow(0)
    val itemCount: StateFlow<Int> = _itemCount.asStateFlow()

    private val _totalDisplay = MutableStateFlow("$0.00")
    val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()

    /** Called by `CheckoutViewModel` whenever its cart state changes. */
    fun update(itemCount: Int, totalDisplay: String) {
        _itemCount.value = itemCount
        _totalDisplay.value = totalDisplay
    }

    fun clear() {
        _itemCount.value = 0
        _totalDisplay.value = "$0.00"
    }

    val hasItems: Boolean
        get() = _itemCount.value > 0
}
