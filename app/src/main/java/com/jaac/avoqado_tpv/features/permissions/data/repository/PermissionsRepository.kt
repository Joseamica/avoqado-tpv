package com.jaac.avoqado_tpv.features.permissions.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permissions Repository
 *
 * Manages staff permissions with local caching for performance.
 * Permissions are fetched from backend and cached for 5 minutes.
 *
 * **Permission Sources (Backend merges these):**
 * 1. Base permissions: DEFAULT_PERMISSIONS by role
 * 2. Custom permissions: VenueRolePermission (dashboard-configured)
 * 3. Implicit permissions: Permission dependencies
 *
 * **Caching Strategy:**
 * - 5-minute TTL (Time-To-Live)
 * - Fetch on login → Cache
 * - Clear on logout
 * - Force refresh available for critical operations
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var permissionsRepository: PermissionsRepository
 *
 * // Check permission before showing UI
 * val canAccessSettings = permissionsRepository.hasPermission("tpv-terminal:settings")
 * if (canAccessSettings) {
 *     // Show settings button
 * }
 *
 * // Force refresh after permission change in dashboard
 * permissionsRepository.getPermissions(forceRefresh = true)
 * ```
 *
 * @param apiService API service for fetching permissions
 * @param secureStorage Secure storage for caching permissions
 */
@Singleton
class PermissionsRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L  // 5 minutes
        private const val CACHE_KEY_PERMISSIONS = "permissions_cache"
        private const val CACHE_KEY_TIMESTAMP = "permissions_cache_timestamp"
        /**
         * 🔴 Codex r9 (P2-8): el DUEÑO (`venueId|staffId`, tal como lo devolvió el servidor al calcular la lista) va en el MISMO
         * valor que la lista, antes de este separador. Iban en dos claves con dos escrituras: morir entre ellas dejaba la lista
         * de B con el dueño de A, y A la aceptaba sin red. Un valor sin separador (versión anterior) no tiene dueño: no vale.
         */
        private const val SEPARADOR_DUENO = '\n'

        private fun dueno(venueId: String?, staffId: String?): String? =
            if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) null else "$venueId|$staffId"

        /**
         * La lista guardada tal cual, SIN mirar su caducidad; null si no hay ninguna — o si es de OTRA persona u otro negocio.
         *
         * 🔴 Codex r8 (P1-3, 22-sep): la lista no tenía dueño. El gerente A la dejaba guardada, su sesión caducaba y fallaba el
         * refresh (`clearSession` no la borra), entraba el cajero B y fallaba la descarga de SUS permisos ⇒ se le devolvía la
         * lista de A, y B cerraba el respaldo SIN RED con el permiso del gerente. Una lista sin dueño (versión anterior) tampoco
         * vale: no se puede saber de quién es.
         */
        /**
         * 🔴 Codex r10 (P2-4): a quién le revocó el servidor la lista (un 403) EN ESTE PROCESO, por almacenamiento. Si borrarla del
         * disco falla, la lista seguiría valiendo en la llamada siguiente; esto la invalida aunque el borrado no haya ocurrido.
         * Sólo la levanta una descarga NUEVA y exitosa para la misma persona. Por instancia de `SecureStorage` (débil): en
         * producción es un singleton; en pruebas cada una trae el suyo.
         */
        private val revocadas = java.util.WeakHashMap<SecureStorage, MutableSet<String>>()

        /** 🔴 Codex r11 (P2-3): un CONJUNTO por almacenamiento — el 403 de B no puede levantar la revocación de A. */
        private fun estaRevocada(s: SecureStorage, dueno: String) = synchronized(revocadas) { revocadas[s]?.contains(dueno) == true }
        private fun marcarRevocada(s: SecureStorage, dueno: String) { synchronized(revocadas) { revocadas.getOrPut(s) { mutableSetOf() }.add(dueno) } }
        private fun levantarRevocacion(s: SecureStorage, dueno: String) { synchronized(revocadas) { revocadas[s]?.remove(dueno) } }

        private fun listaGuardada(secureStorage: SecureStorage): Set<String>? {
            val sesion = duenoDeLaSesion(secureStorage) ?: return null
            if (estaRevocada(secureStorage, sesion)) return null
            val guardado = secureStorage.getString(CACHE_KEY_PERMISSIONS, null) ?: return null
            val corte = guardado.indexOf(SEPARADOR_DUENO)
            if (corte < 0 || guardado.substring(0, corte) != sesion) return null
            return guardado.substring(corte + 1).split(",").filter { it.isNotBlank() }.toSet()
        }

        private fun duenoDeLaSesion(secureStorage: SecureStorage): String? = dueno(secureStorage.getVenueId(), secureStorage.getStaffId())

        /**
         * 🔴 Codex r7 (P2-1, 22-sep): ¿la ÚLTIMA lista de permisos EFECTIVOS que calculó el servidor trae [permission]?
         * Sin red y sin esperar — para lo que se decide justo cuando no hay conexión (el respaldo SIN RED de «no se
         * presentó tarjeta»). Ignora la caducidad a propósito: sin red no hay lista más nueva que pedir. Sin lista ⇒ false.
         *
         * Es la lista de `tpv/auth/permissions` (conjunto asignado, o rol + lo que el local agregó − lo que quitó), NO los
         * permisos crudos del login (`AuthRepository.hasPermission`, `StaffVenue.permissions`), que casi siempre vienen
         * vacíos: con ésos un MANAGER con el permiso por su rol quedaba fuera del botón.
         */
        fun enLaUltimaListaEfectiva(secureStorage: SecureStorage, permission: String): Boolean =
            listaGuardada(secureStorage)?.contains(permission) == true
    }

    /**
     * Get staff permissions with caching
     *
     * **Flow:**
     * 1. Check cache validity (< 5 min old)
     * 2. If valid → return cached permissions
     * 3. If invalid or forceRefresh → fetch from backend
     * 4. Cache new permissions
     * 5. Return permissions
     *
     * **Error Handling:**
     * - Network error → Return cached permissions if available (stale-while-revalidate)
     * - No cache + network error → Return failure
     *
     * @param forceRefresh Skip cache and fetch fresh data
     * @return Result with permissions set or error
     */
    /**
     * 🔴 Codex r11 (P1-1): UNA descarga a la vez, de la petición a la aplicación. Numerarlas (r10) no bastaba: el SERVIDOR puede
     * leer la segunda antes que la primera, y una respuesta atrasada rehabilitaba un permiso ya revocado (o borraba uno vigente).
     * Serializadas, el servidor lee en el orden en que salen y la que se lee después manda. `WelcomeScreen` lanza dos a la vez:
     * la segunda espera, y si mientras tanto quedó una lista fresca, la usa sin volver a pedir.
     */
    private val unaDescargaALaVez = Mutex()

    suspend fun getPermissions(forceRefresh: Boolean = false): Result<Set<String>> {
        // 1. Check cache if not forcing refresh
        if (!forceRefresh) {
            getCachedPermissions()?.let { cachedPerms ->
                Timber.d("🔐 Using cached permissions (${cachedPerms.size} permissions)")
                return Result.success(cachedPerms)
            }
        }
        return unaDescargaALaVez.withLock { descargar(forceRefresh) }
    }

    private suspend fun descargar(forceRefresh: Boolean): Result<Set<String>> {
        // La espera por el candado pudo dejar una lista recién guardada por la descarga anterior.
        if (!forceRefresh) getCachedPermissions()?.let { return Result.success(it) }

        // 2. Fetch from backend
        Timber.d("🔐 Fetching permissions from backend (forceRefresh=$forceRefresh)")
        val sesionAlPedir = duenoDeLaSesion(secureStorage)
        return try {
            val response = apiService.getStaffPermissions()

            if (response.isSuccessful && response.body()?.success == true) {
                val permissionsData = response.body()!!.data
                val permissions = permissionsData.permissions.toSet()
                val duenoDeLaLista = dueno(permissionsData.venueId, permissionsData.staffId)

                // 🔴 Codex r9 (P2-7): la respuesta es de la sesión que la PIDIÓ. Si mientras viajaba entró otra persona (u otro
                // negocio), o el servidor la calculó para alguien que no es la sesión de ahora, no se devuelve ni se guarda:
                // `hasPermission` consume este valor directo, y la caché con dueño no alcanzaba a proteger el resultado inmediato.
                val sesionAhora = duenoDeLaSesion(secureStorage)
                if (sesionAhora != sesionAlPedir || (duenoDeLaLista != null && sesionAhora != null && duenoDeLaLista != sesionAhora)) {
                    Timber.w("⚠️ La lista de permisos llegó para otra sesión: no se usa ni se guarda")
                    return getCachedPermissions(ignoreExpiry = true)?.let { Result.success(it) }
                        ?: Result.failure(IllegalStateException("La lista de permisos llegó para otra sesión"))
                }

                // Cache permissions — con su DUEÑO: la persona y el negocio para los que el servidor la calculó.
                try {
                    cachePermissions(permissions, duenoDeLaLista)
                } catch (e: Exception) {
                    // 🔴 Codex r11 (P2-2): la descarga SÍ trajo la lista vigente; lo que falló fue guardarla. No es «sin red»: la
                    // lista vieja del disco ya no vale (podría conservar un permiso que el servidor acaba de quitar).
                    Timber.e(e, "❌ No se pudo guardar la lista de permisos recién descargada: la anterior queda invalidada")
                    duenoDeLaLista?.let { revocar(it) }
                }

                Timber.i("✅ Permissions fetched successfully (${permissions.size} permissions, role: ${permissionsData.role})")
                Result.success(permissions)
            } else {
                val errorCode = response.code()
                val errorMsg = response.message()
                Timber.e("❌ Failed to fetch permissions: $errorCode $errorMsg")

                // 🔴 Codex r9 (P1-4): un 403 no es «sin red»: es el servidor diciendo que esta sesión ya no tiene acceso al
                // negocio (p. ej. se borró su membresía). La lista guardada se INVALIDA — sin esto seguía habilitando, sin red,
                // el respaldo de «no se presentó tarjeta» a quien el servidor ya rechaza.
                if (errorCode == 403) {
                    // 🔴 Codex r10 (P2-3): un 403 de OTRA sesión (llegó tarde, ya entró otra persona) no toca la lista vigente.
                    if (duenoDeLaSesion(secureStorage) != sesionAlPedir) {
                        Timber.w("⚠️ 403 de otra sesión: no toca la lista vigente")
                        return getCachedPermissions(ignoreExpiry = true)?.let { Result.success(it) }
                            ?: Result.failure(Exception("Failed to fetch permissions: $errorCode $errorMsg"))
                    }
                    sesionAlPedir?.let { revocar(it) }
                    return Result.failure(Exception("Sin acceso a este negocio: $errorCode $errorMsg"))
                }

                // Fallback to cached permissions if available (stale-while-revalidate)
                getCachedPermissions(ignoreExpiry = true)?.let { stalePerms ->
                    Timber.w("⚠️ Using stale cached permissions due to fetch failure")
                    Result.success(stalePerms)
                } ?: Result.failure(Exception("Failed to fetch permissions: $errorCode $errorMsg"))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "❌ Network error fetching permissions")

            // Fallback to cached permissions (even if stale)
            getCachedPermissions(ignoreExpiry = true)?.let { stalePerms ->
                Timber.w("⚠️ Using stale cached permissions due to network error")
                Result.success(stalePerms)
            } ?: Result.failure(e)
        }
    }

    /**
     * 🔴 Codex r10/r11 (P2-4, P2-2): la lista de [duenoRevocado] deja de valer. La marca va ANTES de borrar: si el borrado del
     * disco falla, `listaGuardada` igual la ignora. Sólo la levanta una descarga NUEVA guardada para esa persona.
     */
    private fun revocar(duenoRevocado: String) {
        marcarRevocada(secureStorage, duenoRevocado)
        runCatching { clearCache() }
    }

    /**
     * Check if staff has specific permission
     *
     * Convenience method for UI checks.
     * Uses cached permissions if available (doesn't trigger fetch).
     *
     * **Usage:**
     * ```kotlin
     * if (permissionsRepository.hasPermission("payments:refund")) {
     *     // Show refund button
     * }
     * ```
     *
     * @param permission Permission to check (e.g., "tpv-terminal:settings")
     * @return true if permission exists in cache, false otherwise
     */
    suspend fun hasPermission(permission: String): Boolean {
        val permissions = getPermissions(forceRefresh = false).getOrNull() ?: return false
        val hasIt = permissions.contains(permission)
        Timber.v("🔐 Permission check: $permission → $hasIt")
        return hasIt
    }

    /**
     * Clear permissions cache
     *
     * Called on logout to ensure next login fetches fresh permissions.
     */
    fun clearCache() {
        secureStorage.remove(CACHE_KEY_PERMISSIONS)
        secureStorage.remove(CACHE_KEY_TIMESTAMP)
        Timber.d("🗑️ Permissions cache cleared")
    }

    /**
     * Get cached permissions if valid
     *
     * @param ignoreExpiry Return cached permissions even if expired (for fallback)
     * @return Cached permissions set or null if cache invalid/expired
     */
    private fun getCachedPermissions(ignoreExpiry: Boolean = false): Set<String>? {
        val timestamp = secureStorage.getLong(CACHE_KEY_TIMESTAMP, 0L)
        val age = System.currentTimeMillis() - timestamp

        // Check expiry
        if (!ignoreExpiry && age > CACHE_EXPIRY_MS) {
            Timber.v("⏰ Permission cache expired (age: ${age}ms)")
            return null
        }

        // Get cached permissions
        return listaGuardada(secureStorage)?.also {
            Timber.v("📦 Cache hit (${it.size} permissions, age: ${age}ms)")
        }
    }

    /**
     * Cache permissions to secure storage
     *
     * Stores permissions as comma-separated string with timestamp.
     *
     * @param permissions Set of permissions to cache
     */
    private fun cachePermissions(permissions: Set<String>, duenoDeLaLista: String?) {
        val permissionsStr = permissions.joinToString(",")
        // UNA escritura con dueño y lista: o quedan los dos o ninguno. Sin dueño acreditado por el servidor la lista no se le
        // atribuye a nadie: no se guarda (`listaGuardada` tampoco la aceptaría).
        if (duenoDeLaLista != null) secureStorage.putString(CACHE_KEY_PERMISSIONS, "$duenoDeLaLista$SEPARADOR_DUENO$permissionsStr")
        else secureStorage.remove(CACHE_KEY_PERMISSIONS)
        // Codex r10 (P2-4): una lista NUEVA y guardada para esa persona levanta la revocación de un 403 anterior.
        if (duenoDeLaLista != null) levantarRevocacion(secureStorage, duenoDeLaLista)
        secureStorage.putLong(CACHE_KEY_TIMESTAMP, System.currentTimeMillis())
        Timber.d("💾 Permissions cached (${permissions.size} permissions)")
    }
}
