package com.jaac.avoqado_tpv.features.remote_command.data.model

/**
 * ACK payload sent to server when command is received
 * @see avoqado-server event: tpv_command_ack
 */
data class CommandAckPayload(
    val commandId: String,
    val terminalId: String,
    val receivedAt: String // ISO 8601 format
)

/**
 * Started payload sent to server when command execution begins
 * @see avoqado-server event: tpv_command_started
 */
data class CommandStartedPayload(
    val commandId: String,
    val terminalId: String,
    val startedAt: String // ISO 8601 format
)

/**
 * Result payload sent to server after command execution
 * @see avoqado-server event: tpv_command_result
 */
data class CommandResultPayload(
    val commandId: String,
    val terminalId: String,
    val resultStatus: String, // TpvCommandResultStatus.name
    val message: String?,
    val resultData: Map<String, Any>?,
    val completedAt: String // ISO 8601 format
)
