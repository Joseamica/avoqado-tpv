package com.jaac.avoqado_tpv.features.remote_command.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class CommandExecutionWindowTest {
    @After fun reset() { CommandExecutionWindow.setForeground(false); CommandExecutionWindow.setRoute(null) }
    @Test fun `P1 only an idle foreground route permits commands`() {
        CommandExecutionWindow.setForeground(true)
        listOf("payment", "angelpay_payment", "refund", "checkout", "payments", "login", null).forEach {
            CommandExecutionWindow.setRoute(it)
            assertFalse(CommandExecutionWindow.isSafe())
        }
        listOf("home", "activation").forEach {
            CommandExecutionWindow.setRoute(it)
            assertTrue(CommandExecutionWindow.isSafe())
        }
        CommandExecutionWindow.setForeground(false)
        assertFalse(CommandExecutionWindow.isSafe())
    }
    @Test fun `P1 pending work wakes on a safe screen event without a timer`() = runTest {
        CommandExecutionWindow.setForeground(true)
        CommandExecutionWindow.setRoute("payment")
        val waiting = async { CommandExecutionWindow.awaitSafe() }
        testScheduler.runCurrent()
        assertFalse(waiting.isCompleted)
        CommandExecutionWindow.setRoute("home")
        testScheduler.runCurrent()
        assertTrue(waiting.isCompleted)
    }
    @Test fun `SDK preparation reserves the window before suspending`() = runTest {
        CommandExecutionWindow.setForeground(true); CommandExecutionWindow.setRoute("home")
        CommandExecutionWindow.beginAction()
        assertFalse(CommandExecutionWindow.tryBeginCommand())
        CommandExecutionWindow.endAction()
        assertTrue(CommandExecutionWindow.tryBeginCommand())
        CommandExecutionWindow.setExecuting(false)
    }
    @Test fun `overlapping home dialogs keep the window busy until both close`() {
        CommandExecutionWindow.setForeground(true); CommandExecutionWindow.setRoute("home")
        CommandExecutionWindow.reserveAction(); CommandExecutionWindow.reserveAction()
        assertFalse(CommandExecutionWindow.tryBeginCommand())
        CommandExecutionWindow.endAction()
        assertFalse(CommandExecutionWindow.tryBeginCommand())
        CommandExecutionWindow.endAction()
        assertTrue(CommandExecutionWindow.tryBeginCommand())
        CommandExecutionWindow.setExecuting(false)
    }

}
