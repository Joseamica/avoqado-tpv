package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.core.domain.models.ApiException
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.ordering.data.api.FloorElementApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateFloorElementRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateFloorElementRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toApiString
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDomain
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElement
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementRepository
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of FloorElementRepository
 *
 * **Responsibilities:**
 * 1. Fetch floor elements from backend API
 * 2. Create, update, delete floor elements
 * 3. Map DTOs to domain models
 * 4. Handle network errors with user-friendly messages
 */
@Singleton
class FloorElementRepositoryImpl @Inject constructor(
    private val apiService: FloorElementApiService
) : FloorElementRepository {

    override suspend fun getFloorElements(venueId: String): Result<List<FloorElement>> {
        return try {
            Timber.i("🎨 [FloorElementRepository] Fetching floor elements for venue: $venueId")

            val response = apiService.getFloorElements(venueId)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para ver los elementos del plano.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Venue no encontrado.\n\n" +
                        "Verifique la configuración del terminal."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al obtener los elementos del plano.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [FloorElementRepository] API error: ${response.code()} - ${response.message()}")
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
                Timber.e("❌ [FloorElementRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo obtener los elementos del plano.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            // Map DTOs to domain models
            val elements = body.data.map { it.toDomain() }

            Timber.i("✅ [FloorElementRepository] Fetched ${elements.size} floor elements")

            Result.Success(elements)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to fetch floor elements")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun createFloorElement(
        venueId: String,
        type: FloorElementType,
        positionX: Float,
        positionY: Float,
        width: Float?,
        height: Float?,
        rotation: Int,
        endX: Float?,
        endY: Float?,
        label: String?,
        color: String?,
        areaId: String?
    ): Result<FloorElement> {
        return try {
            Timber.i("🎨 [FloorElementRepository] Creating floor element type $type")

            val request = CreateFloorElementRequest(
                type = type.toApiString(),
                positionX = positionX.toDouble(),
                positionY = positionY.toDouble(),
                width = width?.toDouble(),
                height = height?.toDouble(),
                rotation = rotation,
                endX = endX?.toDouble(),
                endY = endY?.toDouble(),
                label = label,
                color = color,
                areaId = areaId
            )

            val response = apiService.createFloorElement(venueId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Datos inválidos.\n\n" +
                        "Verifique las coordenadas y dimensiones."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para crear elementos.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Venue o Área no encontrado.\n\n" +
                        "Verifique la configuración."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al crear el elemento.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [FloorElementRepository] API error: ${response.code()} - ${response.message()}")
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
                Timber.e("❌ [FloorElementRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo crear el elemento.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            val element = body.data.toDomain()

            Timber.i("✅ [FloorElementRepository] Floor element created: ${element.id}")

            Result.Success(element)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to create floor element")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun updateFloorElement(
        venueId: String,
        elementId: String,
        positionX: Float?,
        positionY: Float?,
        width: Float?,
        height: Float?,
        rotation: Int?,
        endX: Float?,
        endY: Float?,
        label: String?,
        color: String?,
        areaId: String?
    ): Result<FloorElement> {
        return try {
            Timber.i("🎨 [FloorElementRepository] Updating floor element: $elementId")

            val request = UpdateFloorElementRequest(
                positionX = positionX?.toDouble(),
                positionY = positionY?.toDouble(),
                width = width?.toDouble(),
                height = height?.toDouble(),
                rotation = rotation,
                endX = endX?.toDouble(),
                endY = endY?.toDouble(),
                label = label,
                color = color,
                areaId = areaId
            )

            val response = apiService.updateFloorElement(venueId, elementId, request)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    400 -> {
                        "Datos inválidos.\n\n" +
                        "Verifique las coordenadas."
                    }
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para editar elementos.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Elemento no encontrado.\n\n" +
                        "Verifique que el elemento existe."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al actualizar el elemento.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [FloorElementRepository] API error: ${response.code()} - ${response.message()}")
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
                Timber.e("❌ [FloorElementRepository] Empty or unsuccessful response")
                return Result.Error(
                    ApiException.Unknown(
                        Exception(
                            "No se pudo actualizar el elemento.\n\n" +
                            "Respuesta vacía del servidor."
                        )
                    )
                )
            }

            val element = body.data.toDomain()

            Timber.i("✅ [FloorElementRepository] Floor element updated: ${element.id}")

            Result.Success(element)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to update floor element")
            Result.Error(ApiException.NetworkError(e))
        }
    }

    override suspend fun deleteFloorElement(venueId: String, elementId: String): Result<Unit> {
        return try {
            Timber.i("🎨 [FloorElementRepository] Deleting floor element: $elementId")

            val response = apiService.deleteFloorElement(venueId, elementId)

            if (!response.isSuccessful) {
                val errorMessage = when (response.code()) {
                    401 -> {
                        "Sesión expirada.\n\n" +
                        "Por favor, inicie sesión nuevamente."
                    }
                    403 -> {
                        "Sin permisos para eliminar elementos.\n\n" +
                        "Contacte al administrador."
                    }
                    404 -> {
                        "Elemento no encontrado.\n\n" +
                        "Verifique que el elemento existe."
                    }
                    500, 502, 503 -> {
                        "Error del servidor.\n\n" +
                        "El servidor no está disponible. Intente más tarde."
                    }
                    else -> {
                        "Error al eliminar el elemento.\n\n" +
                        "Código: ${response.code()}\n" +
                        "Por favor, contacte a soporte."
                    }
                }

                Timber.e("❌ [FloorElementRepository] API error: ${response.code()} - ${response.message()}")
                return Result.Error(
                    ApiException.HttpError(
                        code = response.code(),
                        errorMessage = response.message(),
                        customUserMessage = errorMessage
                    )
                )
            }

            Timber.i("✅ [FloorElementRepository] Floor element deleted: $elementId")

            Result.Success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ [TECHNICAL] Failed to delete floor element")
            Result.Error(ApiException.NetworkError(e))
        }
    }
}
