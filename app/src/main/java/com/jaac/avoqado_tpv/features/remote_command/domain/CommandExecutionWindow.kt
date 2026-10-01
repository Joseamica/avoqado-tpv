package com.jaac.avoqado_tpv.features.remote_command.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Fail closed: navigation and activity lifecycle both have to report a safe idle screen. */
object CommandExecutionWindow {
    private var foreground = false
    private var route: String? = null
    private val revision = MutableStateFlow(0L)
    val events = revision.asStateFlow()
    private var activeActions = 0
    private val running = MutableStateFlow(false)
    val executing = running.asStateFlow()
    @Synchronized fun setForeground(value: Boolean) { foreground = value; revision.value++ }
    @Synchronized fun setRoute(value: String?) {
        if (route != value) { route = value; revision.value++ }
    }
    @Synchronized fun isSafe() = foreground && activeActions == 0 && route in setOf("home", "activation")
    @Synchronized fun tryBeginCommand(): Boolean {
        if (!isSafe() || running.value) return false
        running.value = true
        return true
    }
    @Synchronized fun setExecuting(value: Boolean) { running.value = value }
    @Synchronized fun reserveAction() { activeActions++; revision.value++ }
    // Reserve before SDK preparation suspends. Payments have priority over new commands.
    suspend fun beginAction() {
        reserveAction()
        try { executing.first { !it } } catch (e: Throwable) { endAction(); throw e }
    }
    @Synchronized fun endAction() { activeActions--; revision.value++ }
    suspend fun awaitSafe() { events.first { isSafe() } }
}
