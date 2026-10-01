package com.jaac.avoqado_tpv.features.remote_command.domain

import com.google.gson.*
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, encrypted receipts; commit before an effect, preserve through staff logout/wipe. */
@Singleton
class CommandInbox @Inject constructor(private val storage: SecureStorage) {
    private data class Receipt(val command: TpvCommand, var started: Boolean = false,
        var result: CommandResult? = null, var acknowledged: Boolean = false)
    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer<Instant> { value, _, _ -> Instant.parse(value.asString) })
        .create()
    private fun read(): MutableList<Receipt> = storage.getString(KEY)?.let {
        gson.fromJson(it, Array<Receipt>::class.java).toMutableList()
    } ?: mutableListOf()
    private fun save(rows: List<Receipt>) = storage.putStringDurably(KEY, gson.toJson(rows))
    @Synchronized fun remember(command: TpvCommand): Boolean {
        val rows = read()
        val existing = rows.find { it.command.commandId == command.commandId }
        if (existing != null) {
            check(existing.command.type == command.type && existing.command.payload == command.payload) { "Command identity changed" }
            return true
        }
        // ponytail: retain 100 receipts; only server-confirmed ones may be pruned.
        // Unacknowledged results are never discarded to make room for another command.
        if (rows.size >= 100) {
            val removable = rows.indexOfFirst { it.acknowledged }
            if (removable < 0) return false
            rows.removeAt(removable)
        }
        rows.add(Receipt(command)); save(rows); return true
    }
    @Synchronized fun pending(): List<TpvCommand> = read().filter { !it.acknowledged }.map { it.command }
    @Synchronized fun previousResult(id: String): CommandResult? = read().find { it.command.commandId == id }?.let {
        it.result ?: if (it.started) CommandResult.failed("La app se cerró durante el comando; no se repitió. Verifica la terminal.") else null
    }
    @Synchronized fun begin(id: String) { val rows = read(); rows.first { it.command.commandId == id }.started = true; save(rows) }
    @Synchronized fun complete(id: String, result: CommandResult) {
        val rows = read(); rows.first { it.command.commandId == id }.result = result; save(rows)
    }
    @Synchronized fun acknowledge(id: String) {
        val rows = read(); rows.find { it.command.commandId == id }?.acknowledged = true; save(rows)
    }
    companion object { private const val KEY = "tpv_command_inbox" }
}
