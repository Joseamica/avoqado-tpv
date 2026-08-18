package com.jaac.avoqado_tpv.features.tables

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository.TableOwnership
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TableOwnershipSettingsDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TableViewerDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TablesResponse
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.presentation.checkoutButtonVisible
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

/**
 * 🔴 DINERO. Con "Solo el propietario puede modificar sus mesas" ENCENDIDO, la
 * caja tiene que poder liquidar el cheque que abrió un mesero — es su trabajo
 * literal, y en la PAX es el único aparato que pasa la tarjeta.
 *
 * **El defecto que cierran estos tests:** "Pagar" colgaba del MISMO candado que
 * editar. El sobre de `GET tpv/venues/{id}/tables` sólo trae
 * `viewer.canManageAllTables`, que el server calcula con
 * `DEFAULT_OWNERSHIP_OVERRIDES = ['tables:manage-all']` — el booleano de EDITAR.
 * El de COBRAR es otro (`PAYMENT_OWNERSHIP_OVERRIDES = ['tables:manage-all',
 * 'tables:pay-any']`) y sólo lo monta la ruta de cobro. Para un CASHIER llegaba
 * `canManageAll = false`, `readOnly` se encendía, y el botón "Pagar" **ni
 * siquiera se pintaba** (`if (canCheckout)` en `TableOrderBottomBar`).
 *
 * Es la forma CARA del bug: la llamada nunca sale, el 403 nunca llega, **no
 * queda rastro en el log del server**. Nadie se entera hasta que un cajero se
 * queda parado frente a un cliente. Y el server ya decía que sí: el reducer de
 * `PAY_CASH` —único camino de cobro en efectivo de Mesas en esta app— llama
 * `assertOwnership(..., PAYMENT_OWNERSHIP_OVERRIDES)`
 * (`sync.mobile.service.ts:998`). **El gate del cliente era lo ÚNICO que
 * bloqueaba.**
 *
 * **La premisa NO está supuesta — está medida** contra `GET /tpv/auth/permissions`,
 * que es de donde esta app saca sus permisos y que **expande los comodines**
 * antes de mandarlos (`tpv.routes.ts` → `expandWildcards`), así que los tamaños
 * NO son los del volcador que alimenta a Android/iOS. Reproducible con
 * `resolvePermissions(DEFAULT_PERMISSIONS[rol])` + `expandWildcards(...)` en
 * `avoqado-server` (medido 2026-08-18):
 *
 * | rol        | permisos | `tables:pay-any` | `tables:manage-all` |
 * |------------|---------:|:----------------:|:-------------------:|
 * | CASHIER    |       47 | **SÍ**           | no  ← el defecto    |
 * | WAITER     |       49 | no               | no                  |
 * | MANAGER    |      163 | SÍ               | SÍ                  |
 * | OWNER      |      227 | SÍ               | SÍ                  |
 * | SUPERADMIN |      233 | SÍ               | SÍ                  |
 *
 * Por eso aquí NO se construye un fixture de permisos: el único nombre que esta
 * app consulta es `tables:pay-any`, y la prueba que importa es que lo consulte
 * **literal y por el camino real** ([PermissionsRepository]) — eso lo asegura
 * `el permiso sale de PermissionsRepository...` de más abajo. Un fixture escrito
 * a mano sería una SEGUNDA fuente de verdad que envejece sola.
 *
 * Puerto de `avoqado-android` (`TableServiceRepository.isLockedForPayment` +
 * `CobrarMesaAjenaTest`) y de `avoqado-ios`
 * (`TableServiceRepository.swift:50`). Mismos nombres a propósito.
 */
class CobrarMesaAjenaTest {

    private val elDuenio = "el-mesero-que-la-abrio"
    private val yo = "yo"

    /** El estado real del salón: la regla encendida y el cheque es de OTRO. */
    private fun mesaDeOtro(canManageAll: Boolean, canPayAny: Boolean) = TableOwnership(
        enforced = true,
        canManageAll = canManageAll,
        staffId = yo,
        canPayAny = canPayAny,
    )

    /**
     * El botón real, no el booleano: `checkoutButtonVisible` es la MISMA función
     * que decide el `if (...)` que pinta "Pagar" en `TableOrderBottomBar`.
     * Un cheque con total > 0 y sin sesión provisional — lo único que separa
     * al cajero del botón es el candado.
     */
    private fun vePagar(ownership: TableOwnership) = checkoutButtonVisible(
        readOnlyForPayment = ownership.isLockedForPayment(elDuenio),
        isProvisional = false,
        checkTotal = BigDecimal("199.00"),
    )

    // region — El caso que estaba roto

    @Test
    fun el_CAJERO_VE_el_boton_Pagar_en_la_mesa_del_mesero() {
        // CASHIER real: tiene tables:pay-any y NO tiene tables:manage-all,
        // así que el server le manda canManageAllTables = false.
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = true)

        assertThat(vePagar(ownership)).isTrue()
    }

    @Test
    fun y_el_CAJERO_sigue_SIN_poder_editar_esa_mesa() {
        // 🔴 Si el arreglo abre las DOS cosas es peor que el defecto: le
        // regalaría editar, descontar, cortesiar, cancelar, mover y fusionar
        // CUALQUIER mesa. El permiso se elige por SIGNIFICADO.
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = true)

        assertThat(ownership.isLockedForMe(elDuenio)).isTrue()
        assertThat(ownership.isLockedForPayment(elDuenio)).isFalse()
    }

    // endregion

    // region — Contención: nadie más gana nada

    @Test
    fun el_MESERO_en_mesa_ajena_ni_cobra_ni_edita() {
        // WAITER real: 49 permisos, ninguno de los dos.
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = false)

        assertThat(ownership.isLockedForMe(elDuenio)).isTrue()
        assertThat(ownership.isLockedForPayment(elDuenio)).isTrue()
        assertThat(vePagar(ownership)).isFalse()
    }

    @Test
    fun el_MESERO_dueno_de_la_mesa_cobra_y_edita_como_siempre() {
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = false)

        assertThat(ownership.isLockedForMe(yo)).isFalse()
        assertThat(ownership.isLockedForPayment(yo)).isFalse()
        assertThat(
            checkoutButtonVisible(
                readOnlyForPayment = ownership.isLockedForPayment(yo),
                isProvisional = false,
                checkTotal = BigDecimal("199.00"),
            ),
        ).isTrue()
    }

    @Test
    fun el_GERENTE_no_pierde_nada_pasa_por_manage_all_como_siempre() {
        val ownership = mesaDeOtro(canManageAll = true, canPayAny = true)

        assertThat(ownership.isLockedForMe(elDuenio)).isFalse()
        assertThat(ownership.isLockedForPayment(elDuenio)).isFalse()
        assertThat(vePagar(ownership)).isTrue()
    }

    // endregion

    // region — Las ramas que NO deben cambiar

    @Test
    fun con_la_propiedad_de_mesa_APAGADA_nadie_se_bloquea() {
        val apagada = TableOwnership(enforced = false, canManageAll = false, staffId = yo, canPayAny = false)

        assertThat(apagada.isLockedForMe(elDuenio)).isFalse()
        assertThat(apagada.isLockedForPayment(elDuenio)).isFalse()
        assertThat(vePagar(apagada)).isTrue()
    }

    @Test
    fun una_cuenta_sin_dueno_conocido_no_se_bloquea() {
        // Offline / mesa provisional: el cheque todavía no sabe de quién es.
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = false)

        assertThat(ownership.isLockedForMe(null)).isFalse()
        assertThat(ownership.isLockedForPayment(null)).isFalse()
    }

    @Test
    fun el_boton_Pagar_sigue_escondido_sin_cheque_o_en_sesion_provisional() {
        // El arreglo NO puede aflojar las otras dos razones por las que "Pagar"
        // no se pinta: una mesa recién abierta sin nada mandado a cocina no
        // tiene cheque contra el cual cobrar.
        assertThat(
            checkoutButtonVisible(readOnlyForPayment = false, isProvisional = true, checkTotal = BigDecimal("199.00")),
        ).isFalse()
        assertThat(
            checkoutButtonVisible(readOnlyForPayment = false, isProvisional = false, checkTotal = BigDecimal.ZERO),
        ).isFalse()
        assertThat(
            checkoutButtonVisible(readOnlyForPayment = false, isProvisional = false, checkTotal = null),
        ).isFalse()
    }

    // endregion

    // region — El gate está ATADO al botón, no sólo declarado

    /**
     * 🔴 El agujero que este test tapa: los dos candados son `Boolean`, así que
     * pasar `readOnly` donde va `readOnlyForPayment` COMPILA — y reintroduce el
     * bug entero sin que nada más se ponga rojo. Todo lo de arriba prueba la
     * LÓGICA; esto prueba que la lógica está enchufada al `if` que pinta "Pagar".
     *
     * Se hace leyendo el archivo fuente, a propósito: esta app **no tiene
     * infraestructura de test de Compose en `test/`** — `ui-test-junit4` sólo
     * está en `androidTestImplementation`, Robolectric está declarado pero sin
     * usar, y `unitTests.isIncludeAndroidResources` está apagado. Montarla para
     * una pantalla de 25 parámetros era más riesgo que el que quita. Si algún
     * día existe, este test se reemplaza por uno que busque el nodo "Pagar".
     *
     * Si alguien reestructura la llamada y esto truena: **no lo relajes sin
     * verificar a mano de qué candado quedó colgando el botón.**
     */
    @Test
    fun el_boton_Pagar_cuelga_del_candado_de_COBRO_no_del_de_editar() {
        val src = java.io.File(
            "src/main/java/com/jaac/avoqado_tpv/features/tables/presentation/TableOrderScreen.kt",
        )
        assertThat(src.exists()).isTrue()
        val texto = src.readText()

        // 1) La visibilidad del botón sale de `checkoutButtonVisible`.
        val llamada = Regex(
            """val canCheckout = checkoutButtonVisible\(\s*readOnlyForPayment = (\w+),""",
        ).find(texto)
        assertThat(llamada).isNotNull()
        // 2) …y lo que se le pasa es el candado de COBRO, jamás `readOnly`.
        assertThat(llamada!!.groupValues[1]).isEqualTo("readOnlyForPayment")

        // 3) El `if` que pinta el botón consume ese mismo `canCheckout` — si
        //    alguien lo cambia por `readOnly`, esto truena.
        assertThat(texto).contains("if (canCheckout) {")

        // 4) Y el valor viene del ViewModel, no de un `false` cableado.
        assertThat(texto).contains("viewModel.readOnlyForPayment.collectAsStateWithLifecycle()")
    }

    // endregion

    // region — De dónde sale el permiso (el camino REAL de esta app)

    @Test
    fun el_permiso_sale_de_PermissionsRepository_con_el_nombre_literal() = runTest {
        // 🔴 La TPV NO lee la misma forma que Android/iOS: pide
        // `GET /tpv/auth/permissions`, que EXPANDE los comodines. Consultado por
        // NOMBRE funciona igual — `tables:pay-any` llega literal — pero el camino
        // tiene que ser [PermissionsRepository], que es el único que existe aquí.
        // No hay RoleManager en esta app: inventar un gate por rol sería una
        // SEGUNDA fuente de verdad que se desincroniza sola en cuanto el venue
        // use un Permission Set.
        val api = mockk<TablesApiService>()
        val outbox = mockk<SyncOutbox>()
        val permissions = mockk<PermissionsRepository>()
        val repo = TablesRepository(api, outbox, permissions)

        coEvery { api.getTables("venue-1") } returns Response.success(
            TablesResponse(
                success = true,
                data = emptyList(),
                settings = TableOwnershipSettingsDto(enforceTableOwnership = true),
                viewer = TableViewerDto(staffId = yo, canManageAllTables = false),
            ),
        )
        coEvery { permissions.hasPermission("tables:pay-any") } returns true

        repo.getTables("venue-1")

        coVerify(exactly = 1) { permissions.hasPermission("tables:pay-any") }
        assertThat(repo.ownership.value.canPayAny).isTrue()
        assertThat(repo.ownership.value.isLockedForPayment(elDuenio)).isFalse()
        assertThat(repo.ownership.value.isLockedForMe(elDuenio)).isTrue()
    }

    @Test
    fun sin_el_permiso_el_candado_de_cobro_sigue_puesto() {
        // Un venue con Permission Sets puede quitárselo a su cajero, y ahí la
        // app tiene que obedecer la LISTA, no el nombre del rol.
        val ownership = mesaDeOtro(canManageAll = false, canPayAny = false)

        assertThat(ownership.isLockedForPayment(elDuenio)).isTrue()
        assertThat(vePagar(ownership)).isFalse()
    }

    @Test
    fun si_no_se_pudo_resolver_el_permiso_se_degrada_al_comportamiento_viejo() = runTest {
        // Sin lista de permisos (sin red y sin cache) [PermissionsRepository]
        // devuelve `false`. Eso deja EXACTAMENTE el comportamiento anterior a
        // este arreglo — sólo `tables:manage-all` se salta el candado — nunca
        // uno MÁS abierto. Degradar, jamás regalar dinero.
        val api = mockk<TablesApiService>()
        val outbox = mockk<SyncOutbox>()
        val permissions = mockk<PermissionsRepository>()
        val repo = TablesRepository(api, outbox, permissions)

        coEvery { api.getTables("venue-1") } returns Response.success(
            TablesResponse(
                success = true,
                data = emptyList(),
                settings = TableOwnershipSettingsDto(enforceTableOwnership = true),
                viewer = TableViewerDto(staffId = yo, canManageAllTables = false),
            ),
        )
        coEvery { permissions.hasPermission("tables:pay-any") } returns false

        repo.getTables("venue-1")

        assertThat(repo.ownership.value.canPayAny).isFalse()
        assertThat(repo.ownership.value.isLockedForPayment(elDuenio)).isTrue()
    }

    // endregion
}
