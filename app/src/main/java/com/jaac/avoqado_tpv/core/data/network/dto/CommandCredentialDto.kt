package com.jaac.avoqado_tpv.core.data.network.dto

import com.google.gson.annotations.SerializedName

data class CommandCredentialDto(@SerializedName("commandToken") val commandToken: String)
data class CommandsReadyDto(@SerializedName("pendingCommands") val pendingCommands: List<PendingCommandDto>)
data class CommandPermitDto(@SerializedName("permitted") val permitted: Boolean)
