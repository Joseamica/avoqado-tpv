package com.jaac.avoqado_tpv.features.ordering.data.api

import com.jaac.avoqado_tpv.features.ordering.data.dto.ApiResponse
import com.jaac.avoqado_tpv.features.ordering.data.dto.CreateFloorElementRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.FloorElementDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.UpdateFloorElementRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit service interface for floor element management endpoints.
 *
 * **Base URL:** https://api.avoqado.io/api/v1/ (production)
 *              https://patchiest-noncommemorational-willia.ngrok-free.dev/api/v1/ (development)
 *
 * **Authentication:** All requests require Bearer token in header.
 * ```
 * Authorization: Bearer {access_token}
 * ```
 *
 * **Available Endpoints:**
 * 1. GET /tpv/venues/{venueId}/floor-elements - Get all floor elements
 * 2. POST /tpv/venues/{venueId}/floor-elements - Create a new floor element
 * 3. PUT /tpv/venues/{venueId}/floor-elements/{elementId} - Update a floor element
 * 4. DELETE /tpv/venues/{venueId}/floor-elements/{elementId} - Delete a floor element
 */
interface FloorElementApiService {
    /**
     * Get all floor elements for a venue.
     *
     * **Endpoint:** GET /tpv/venues/{venueId}/floor-elements
     *
     * **Backend Behavior:**
     * 1. Fetches all active floor elements for the venue
     * 2. Returns decorative elements (walls, bars, labels, service areas, doors)
     * 3. Sorted by creation time ascending
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": [
     *     {
     *       "id": "element_1",
     *       "type": "WALL",
     *       "positionX": 0.1,
     *       "positionY": 0.5,
     *       "width": null,
     *       "height": null,
     *       "rotation": 0,
     *       "endX": 0.9,
     *       "endY": 0.5,
     *       "label": null,
     *       "color": "#424242",
     *       "areaId": null,
     *       "active": true
     *     },
     *     {
     *       "id": "element_2",
     *       "type": "BAR_COUNTER",
     *       "positionX": 0.5,
     *       "positionY": 0.8,
     *       "width": 0.3,
     *       "height": 0.1,
     *       "rotation": 0,
     *       "endX": null,
     *       "endY": null,
     *       "label": "Barra",
     *       "color": "#8B4513",
     *       "areaId": null,
     *       "active": true
     *     }
     *   ]
     * }
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized (token missing or expired)
     * - 403: Forbidden (no permissions)
     * - 404: Venue not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @return Response with list of floor elements or HTTP error
     */
    @GET("tpv/venues/{venueId}/floor-elements")
    suspend fun getFloorElements(
        @Path("venueId") venueId: String
    ): Response<ApiResponse<List<FloorElementDto>>>

    /**
     * Create a new floor element.
     *
     * **Endpoint:** POST /tpv/venues/{venueId}/floor-elements
     *
     * **Backend Behavior:**
     * 1. Validates coordinates are in 0-1 range
     * 2. Validates required fields based on element type
     *    - WALL requires endX, endY
     *    - BAR_COUNTER, SERVICE_AREA require width, height
     * 3. Creates element in database
     * 4. Returns created element
     *
     * **Request Body:** CreateFloorElementRequest
     * ```json
     * {
     *   "type": "WALL",
     *   "positionX": 0.1,
     *   "positionY": 0.5,
     *   "endX": 0.9,
     *   "endY": 0.5,
     *   "color": "#424242"
     * }
     * ```
     *
     * **Success Response (201):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "element_123",
     *     "type": "WALL",
     *     "positionX": 0.1,
     *     "positionY": 0.5,
     *     "endX": 0.9,
     *     "endY": 0.5,
     *     "color": "#424242",
     *     "active": true
     *   },
     *   "message": "Floor element created successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid request data (missing required fields, invalid coordinates)
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Venue or Area not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param request Floor element creation data
     * @return Response with created floor element or HTTP error
     */
    @POST("tpv/venues/{venueId}/floor-elements")
    suspend fun createFloorElement(
        @Path("venueId") venueId: String,
        @Body request: CreateFloorElementRequest
    ): Response<ApiResponse<FloorElementDto>>

    /**
     * Update an existing floor element.
     *
     * **Endpoint:** PUT /tpv/venues/{venueId}/floor-elements/{elementId}
     *
     * **Backend Behavior:**
     * 1. Validates element exists and belongs to venue
     * 2. Validates coordinates if provided (0-1 range)
     * 3. Updates only provided fields
     * 4. Returns updated element
     *
     * **Request Body:** UpdateFloorElementRequest (all fields optional)
     * ```json
     * {
     *   "positionX": 0.2,
     *   "positionY": 0.6,
     *   "label": "Pared Principal"
     * }
     * ```
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "data": {
     *     "id": "element_123",
     *     "type": "WALL",
     *     "positionX": 0.2,
     *     "positionY": 0.6,
     *     "label": "Pared Principal",
     *     ...
     *   },
     *   "message": "Floor element updated successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 400: Invalid coordinates
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Element or Venue not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param elementId ID of the element to update
     * @param request Update data (only include fields to update)
     * @return Response with updated floor element or HTTP error
     */
    @PUT("tpv/venues/{venueId}/floor-elements/{elementId}")
    suspend fun updateFloorElement(
        @Path("venueId") venueId: String,
        @Path("elementId") elementId: String,
        @Body request: UpdateFloorElementRequest
    ): Response<ApiResponse<FloorElementDto>>

    /**
     * Delete a floor element (soft delete).
     *
     * **Endpoint:** DELETE /tpv/venues/{venueId}/floor-elements/{elementId}
     *
     * **Backend Behavior:**
     * 1. Validates element exists and belongs to venue
     * 2. Soft deletes by setting active: false
     * 3. Element is hidden from GET requests but remains in database
     *
     * **Success Response (200):**
     * ```json
     * {
     *   "success": true,
     *   "message": "Floor element deleted successfully"
     * }
     * ```
     *
     * **Error Responses:**
     * - 401: Unauthorized
     * - 403: Forbidden
     * - 404: Element or Venue not found
     * - 500: Internal server error
     *
     * @param venueId ID of the venue
     * @param elementId ID of the element to delete
     * @return Response indicating success or HTTP error
     */
    @DELETE("tpv/venues/{venueId}/floor-elements/{elementId}")
    suspend fun deleteFloorElement(
        @Path("venueId") venueId: String,
        @Path("elementId") elementId: String
    ): Response<ApiResponse<Unit>>
}
