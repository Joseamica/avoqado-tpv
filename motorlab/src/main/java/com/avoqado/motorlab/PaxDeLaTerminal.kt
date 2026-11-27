package com.avoqado.motorlab

import android.content.Context
import com.blumonpay.pax.shared_tools.manager.CountryConstants
import com.blumonpay.pax.utils.AppManager
import com.blumonpay.pax.utils.device_neptune.DeviceImplNeptune
import com.pax.dal.entity.EPedType
import com.pax.dal.entity.EPiccType
import com.pax.dal.entity.EReaderType
import com.pax.dal.entity.PollingResult
import com.pax.jemv.clcommon.ACType
import com.pax.jemv.clcommon.RetCode
import com.pax.jemv.device.DeviceManager
import com.pax.jemv.emv.api.EMVApi
import com.paxsz.module.emv.param.EmvProcessParam
import com.paxsz.module.emv.param.EmvTransParam
import com.paxsz.module.emv.process.contact.EmvProcess
import com.paxsz.module.emv.process.contact.IEmvTransProcessListener
import com.paxsz.module.emv.process.contactless.ClssProcess
import com.paxsz.module.emv.process.contactless.IContactlessCallback
import com.paxsz.module.emv.process.entity.IssuerRspData
import com.paxsz.module.emv.process.entity.OnlineResultWrapper
import com.paxsz.module.emv.xmlparam.entity.common.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.pax.jemv.clcommon.ByteArray as PaxBytes

/**
 * La PAX de verdad (bloque 4-L · L-c): la secuencia que ya leyó una tarjeta en esta PAX el 6-nov (el laboratorio viejo,
 * `46a3ba37`), con el importe REAL y sólo el lector sin contacto. Corre en el hilo de [LectorPax], salvo [pararSondeo].
 * Los parámetros EMV salen de los assets del AAR de Blumon de sandbox que trae este APK (`EmvParamsManager`), nunca de la app de
 * la terminal. No escribe nada.
 *
 * También es el [ChipDePrueba] de la medición de chip (26-nov): el kernel de CONTACTO con la lista de AID de contacto, la
 * secuencia de `chip()` del laboratorio que leyó una Amex por chip en esta PAX el 6-nov (`a002b089`). Ésa corre en el hilo de la
 * medición; el folio de [LectorPax] impide que las dos usen la PAX a la vez.
 *
 * Privacidad: en una `debug` (sin R8) las bibliotecas de PAX y de Blumon sí escriben datos de la tarjeta
 * (`ClssPayWaveProcess.getTlv`, `ClssPayPassProcess.getTlv`, `DeviceImplNeptune.iccCommand`, y el kernel de contacto el 8A, el
 * 91 y el 9F10, con `LogUtils.IS_DEBUG` fijo en `true`): la lectura seca y la medición no descargan el registro del aparato y lo
 * borran al terminar.
 */
internal class PaxDeLaTerminal(private val contexto: Context) : Pax, ChipDePrueba {

    @Volatile private var iniciada = false

    /** `AppManager.init` (las librerías de PAX, el DAL y los parámetros EMV), una vez: la piden el lector y la medición. */
    @Synchronized private fun iniciar() {
        if (!iniciada) {
            AppManager.init(contexto)
            iniciada = true
        }
    }

    override fun preparar(importeCentavos: Long): Int {
        iniciar()
        val p = AppManager.emvParams
        val config = p.getConfigParam(CountryConstants.MEX)
        return ClssProcess.getInstance().preTransProcess(
            EmvProcessParam.Builder(venta(importeCentavos, config), config, p.getCapkParams())
                .setPayPassParam(p.getPayPassParam(CountryConstants.MEX))
                .setPayWaveParam(p.getPayWaveParam(CountryConstants.MEX))
                .setExpressPayParam(p.getExpressPayParam(CountryConstants.MEX))
                .create(),
        )
    }

    private fun venta(importeCentavos: Long, config: Config): EmvTransParam {
        val ahora = ZonedDateTime.now(MEXICO)
        return EmvTransParam().apply {
            transType = 0.toByte() // venta
            amount = importeCentavos.toString() // centavos: el kernel lo pone en el 9F02
            amountOther = "0"
            terminalID = "00000001"
            transCurrencyCode = config.terminalCountryCode
            transCurrencyExponent = config.transReferenceCurrencyExponent
            transDate = ahora.format(FECHA)
            transTime = ahora.format(HORA)
            transTraceNo = "0001"
        }
    }

    override fun sondear(esperaMs: Long): Sondeo {
        val r = AppManager.dal.cardReaderHelper.polling(EReaderType.PICC, esperaMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        return when (r.operationType) {
            PollingResult.EOperationType.OK -> if (r.readerType == EReaderType.PICC) Sondeo.TARJETA else Sondeo.OTRO
            PollingResult.EOperationType.TIMEOUT -> Sondeo.VENCIO
            PollingResult.EOperationType.CANCEL -> Sondeo.PARADO
            else -> Sondeo.OTRO
        }
    }

    override fun pararSondeo() = AppManager.dal.cardReaderHelper.stopPolling()

    override fun leerConElKernel(): Kernel {
        DeviceManager.getInstance().setIDevice(DeviceImplNeptune.getInstance())
        val clss = ClssProcess.getInstance()
        clss.registerClssStatusListener(SIN_PANTALLA)
        val r = clss.startTransProcess(false)
        return Kernel(clss.kernType?.kernType, r.resultCode, r.transResult, r.cvmResult)
    }

    override fun tag(tag: Int): ByteArray? {
        val b = PaxBytes()
        return if (ClssProcess.getInstance().getTlv(tag, b) == RetCode.EMV_OK && b.length > 0) b.data.copyOf(b.length) else null
    }

    override fun cerrarPicc() = AppManager.dal.getPicc(EPiccType.INTERNAL).close()

    // ── La medición de chip (ChipDePrueba) ──

    override fun tarjetaPuesta(): Boolean {
        iniciar()
        return AppManager.dal.icc.detect(RANURA)
    }

    /** Si `getEmvAidList` no estuviera en el AAR, esto no compila: ALTO (no se abre el AAR ni se arma la lista a mano). */
    override fun prepararContacto(importeCentavos: Long): Int {
        iniciar()
        val p = AppManager.emvParams
        val config = p.getConfigParam(CountryConstants.MEX)
        return EmvProcess.getInstance().preTransProcess(
            EmvProcessParam.Builder(venta(importeCentavos, config), config, p.getCapkParams())
                .setEmvAidList(p.getEmvAidList(CountryConstants.MEX))
                .create(),
        )
    }

    override fun prepararAntena(importeCentavos: Long): Int = preparar(importeCentavos)

    override fun sondearChip(conAntena: Boolean, esperaMs: Long): Gano {
        val r = AppManager.dal.cardReaderHelper.polling(
            if (conAntena) EReaderType.ICC_PICC else EReaderType.ICC,
            esperaMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
        return when (r.operationType) {
            PollingResult.EOperationType.OK -> when (r.readerType) {
                EReaderType.ICC -> Gano.CHIP
                EReaderType.PICC -> Gano.ANTENA
                else -> Gano.OTRO
            }
            PollingResult.EOperationType.TIMEOUT -> Gano.VENCIO
            PollingResult.EOperationType.CANCEL -> Gano.PARADO
            else -> Gano.OTRO
        }
    }

    override fun leerChip(oyente: IEmvTransProcessListener): Kernel {
        DeviceManager.getInstance().setIDevice(DeviceImplNeptune.getInstance())
        val emv = EmvProcess.getInstance()
        emv.registerEmvProcessListener(oyente)
        val r = emv.startTransProcess(false)
        return Kernel(null, r.resultCode, r.transResult, r.cvmResult)
    }

    override fun tagDelChip(tag: Int): ByteArray? {
        val b = PaxBytes()
        return if (EmvProcess.getInstance().getTlv(tag, b) == RetCode.EMV_OK && b.length > 0) b.data.copyOf(b.length) else null
    }

    /**
     * La secuencia de `EmvProcess.completeTransProcess` (8A, 89, el 91 con SU regla y `EMVCompleteTrans`), con una marca justo
     * antes y justo después de la llamada nativa, para medir sólo ésa. Sin sus líneas de log ni `EMVGetScriptResult` (sólo
     * alimentaba un log). El resultado se arma igual que el SDK ([resultadoDeLaSegunda]).
     */
    override fun completar(respuesta: IssuerRspData, alEntrar: () -> Unit, alSalir: () -> Unit): Completado {
        if (respuesta.respCode.isNotEmpty()) EMVApi.EMVSetTLVData(0x8A.toShort(), respuesta.respCode, respuesta.respCode.size)
        if (respuesta.authCode.isNotEmpty()) EMVApi.EMVSetTLVData(0x89.toShort(), respuesta.authCode, respuesta.authCode.size)
        val entrega91 = if (respuesta.authData.isEmpty()) null else ReglaDel91.pide(EmvProcess.getInstance())
        if (entrega91 == true) EMVApi.EMVSetTLVData(0x91.toShort(), respuesta.authData, respuesta.authData.size)
        val ac = ACType()
        alEntrar()
        val ret = try {
            EMVApi.EMVCompleteTrans(respuesta.onlineResult.toInt(), respuesta.script, respuesta.script.size, ac)
        } finally {
            alSalir()
        }
        return Completado(resultadoDeLaSegunda(ret, ac.type), tipoDeAc(ac.type), entrega91)
    }

    override fun cerrarIcc() = AppManager.dal.icc.close(RANURA)

    override fun cancelarNip() = AppManager.dal.getPed(EPedType.INTERNAL).cancelInput()

    private companion object {
        val MEXICO: ZoneId = ZoneId.of("America/Mexico_City")
        val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")
        const val RANURA: Byte = 0 // la ranura del chip del usuario

        /** Sin pantalla del kernel, como el laboratorio viejo: ni NIP ni «mira tu teléfono». La admisión niega lo que pida NIP. */
        val SIN_PANTALLA = object : IContactlessCallback {
            override fun onReadCardOk() = Unit
            override fun onRemoveCard() = Unit
            override fun needSeePhone(): Boolean = false
            override fun confirmCard(): Int = RetCode.EMV_OK
            override fun onCardHolderPwd(isOnlinePin: Boolean, supportPINByPass: Boolean, leftTimes: Int, pinData: ByteArray?): Int =
                RetCode.EMV_OK
            override fun showEnterTip(): Int = RetCode.EMV_OK
            override fun startOnlineProcess(result: Int): OnlineResultWrapper? = null // el envío al banco es del motor
            override fun onDetect2ndTap() = Unit
        }
    }
}
