package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.ordering.data.api.TableApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.AssignTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateTablePositionRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateTableRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDomain
import com.jaac.avoqado_tpv.features.ordering.domain.AssignTableResult
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TableRepository
 *
 * **Responsibilities:**
 * 1. Fetch tables from backend API
 * 2. Assign tables and create orders
 * 3. Map DTOs to domain models
 * 4. Handle network errors with user-friendly messages
 *
 * **Error Handling:**
 * - 401: Unauthorized (token expired)
 * - 403: Forbidden (no permissions)
 * - 404: Venue or Table not found
 * - 429: Rate limit exceeded
 * - 500: Server error
 * - Network errors: Connection issues
 */
@Singleton
class TableRepositoryImpl @Inject constructor(
    private val apiService: TableApiService
) : TableRepository {

    override suspend fun createTable(
        venueId: String,
        number: String,
        capacity: Int,
        shape: TableShape,
        rotation: Int,
        positionX: Float?,
        positionY: Float?,
        areaId: String?
    ): Result<Table> {
        return try {
            Timber.i("➕ [TableRepository] Creating table: $number")

            val request = CreateTableRequest(
                number = number,
                capacity = capacity,
                shape = shape.name,
                rotation = rotation,
                positionX = positionX?.toDouble(),
                positionY = positionY?.toDouble(),
                areaId = areaId
            )

            val response = apiService.createTable(venueId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Datos inválidos.\n\n" +
                        "Verifique el número de mesa, capacidad y forma."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para crear mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Venue o Área no encontrada.\n\n" +
                        "Verifique que existen."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al crear la mesa.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TableRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo crear la mesa.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            val table = body.data.toDomain()

            Timber.i("✅ [TableRepository] Table ${table.number} created successfully")

            Result.Success(table)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to create table")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun getTables(venueId: String): Result<List<Table>> {
        return try {
            Timber.i("🪑 [TableRepository] Fetching tables for venue: $venueId")

            val response = apiService.getTables(venueId)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para ver las mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Venue no encontrado.\n\n" +
                        "Verifique la configuración del terminal."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al obtener las mesas.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TableRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo obtener las mesas.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            // Map DTOs to domain models (access data field from wrapped response)
            val tables = body.data.map { it.toDomain() }

            Timber.i("✅ [TableRepository] Fetched ${tables.size} tables")
            Timber.i("   Available: ${tables.count { it.isAvailable }}")
            Timber.i("   Occupied: ${tables.count { it.isOccupied }}")
            Timber.i("   Reserved: ${tables.count { it.isReserved }}")

            Result.Success(tables)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to fetch tables")

            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun assignTable(
        venueId: String,
        tableId: String,
        staffId: String,
        covers: Int
    ): Result<AssignTableResult> {
        return try {
            Timber.i("🪑 [TableRepository] Assigning table $tableId with $covers covers (staff: $staffId)")

            val request = AssignTableRequest(
                staffId = staffId,
                covers = covers
            )

            val response = apiService.assignTable(venueId, tableId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Datos inválidos.\n\n" +
                        "Verifique el número de comensales."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para asignar mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Mesa no encontrada.\n\n" +
                        "Verifique que la mesa existe."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al asignar la mesa.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TableRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo asignar la mesa.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            // Access data field from wrapped response
            val responseData = body.data
            val result = AssignTableResult(
                orderId = responseData.order.id,
                orderNumber = responseData.order.orderNumber,
                isNewOrder = responseData.isNewOrder
            )

            if (result.isNewOrder) {
                Timber.i("✅ [TableRepository] New order created: ${result.orderNumber}")
            } else {
                Timber.i("✅ [TableRepository] Existing order returned: ${result.orderNumber}")
            }

            Result.Success(result)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to assign table")

            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun updateTablePosition(
        venueId: String,
        tableId: String,
        positionX: Float,
        positionY: Float
    ): Result<Unit> {
        return try {
            Timber.i("📍 [TableRepository] Updating table position: $tableId to ($positionX, $positionY)")

            val request = UpdateTablePositionRequest(
                positionX = positionX.toDouble(),
                positionY = positionY.toDouble()
            )

            val response = apiService.updateTablePosition(venueId, tableId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Coordenadas inválidas.\n\n" +
                        "Las posiciones deben estar entre 0 y 1."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para editar mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Mesa no encontrada.\n\n" +
                        "Verifique que la mesa existe."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al actualizar la posición.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TableRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo actualizar la posición.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            Timber.i("✅ [TableRepository] Table ${body.data.number} position updated successfully")

            Result.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to update table position")

            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun updateTable(
        venueId: String,
        tableId: String,
        number: String?,
        capacity: Int?,
        shape: TableShape?,
        rotation: Int?,
        areaId: String?
    ): Result<Table> {
        return try {
            Timber.i("🔧 [TableRepository] Updating table: $tableId")

            val request = UpdateTableRequest(
                number = number,
                capacity = capacity,
                shape = shape?.name,
                rotation = rotation,
                areaId = areaId
            )

            val response = apiService.updateTable(venueId, tableId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Datos inválidos.\n\n" +
                        "Verifique el número de mesa, capacidad y forma."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para editar mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Mesa o Área no encontrada.\n\n" +
                        "Verifique que existen."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al actualizar la mesa.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            val body = response.body()
            if (body == null || !body.success) {
                Timber.e("❌ [TableRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo actualizar la mesa.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            val table = body.data.toDomain()

            Timber.i("✅ [TableRepository] Table ${table.number} updated successfully")

            Result.Success(table)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to update table")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun deleteTable(venueId: String, tableId: String): Result<Unit> {
        return try {
            Timber.i("🗑️ [TableRepository] Deleting table: $tableId")

            val response = apiService.deleteTable(venueId, tableId)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "No se puede eliminar la mesa.\n\n" +
                        "La mesa tiene una orden activa sin pagar."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para eliminar mesas.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Mesa no encontrada.\n\n" +
                        "Verifique que la mesa existe."
                    }
                    429 -> {
                        "Demasiadas solicitudes.\n\n" +
                        "Por favor, espere un momento e intente nuevamente."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al eliminar la mesa.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [TableRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            Timber.i("✅ [TableRepository] Table deleted successfully")

            Result.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to delete table")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}
