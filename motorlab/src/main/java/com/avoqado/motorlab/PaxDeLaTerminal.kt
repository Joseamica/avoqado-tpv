package com.avoqado.motorlab

import android.content.Context
import com.blumonpay.pax.shared_tools.manager.CountryConstants
import com.blumonpay.pax.utils.AppManager
import com.blumonpay.pax.utils.device_neptune.DeviceImplNeptune
import com.pax.dal.entity.EPiccType
import com.pax.dal.entity.EReaderType
import com.pax.dal.entity.PollingResult
import com.pax.jemv.clcommon.RetCode
import com.pax.jemv.device.DeviceManager
import com.paxsz.module.emv.param.EmvProcessParam
import com.paxsz.module.emv.param.EmvTransParam
import com.paxsz.module.emv.process.contactless.ClssProcess
import com.paxsz.module.emv.process.contactless.IContactlessCallback
import com.paxsz.module.emv.process.entity.OnlineResultWrapper
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.pax.jemv.clcommon.ByteArray as PaxBytes

/**
 * La PAX de verdad (bloque 4-L · L-c): la secuencia que ya leyó una tarjeta en esta PAX el 6-nov (el laboratorio viejo,
 * `46a3ba37`), con el importe REAL, sin la lista de AID de chip y sólo el lector sin contacto. Corre en el hilo de
 * [LectorPax], salvo [pararSondeo]. Los parámetros EMV salen de los assets del AAR de Blumon de sandbox que trae este APK
 * (`EmvParamsManager`), nunca de la app de la terminal. No escribe nada.
 *
 * Privacidad: en una `debug` (sin R8) las bibliotecas de PAX y de Blumon sí escriben datos de la tarjeta
 * (`ClssPayWaveProcess.getTlv`, `ClssPayPassProcess.getTlv`, `DeviceImplNeptune.iccCommand`, con `LogUtils.IS_DEBUG` fijo en
 * `true`): la lectura seca no descarga el registro del aparato y lo borra al terminar.
 */
internal class PaxDeLaTerminal(private val contexto: Context) : Pax {

    private var iniciada = false // sólo en el hilo del lector

    override fun preparar(importeCentavos: Long): Int {
        if (!iniciada) {
            AppManager.init(contexto) // las librerías de PAX, el DAL y los parámetros EMV
            iniciada = true
        }
        val p = AppManager.emvParams
        val config = p.getConfigParam(CountryConstants.MEX)
        val ahora = ZonedDateTime.now(MEXICO)
        val trans = EmvTransParam().apply {
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
        return ClssProcess.getInstance().preTransProcess(
            EmvProcessParam.Builder(trans, config, p.getCapkParams())
                .setPayPassParam(p.getPayPassParam(CountryConstants.MEX))
                .setPayWaveParam(p.getPayWaveParam(CountryConstants.MEX))
                .setExpressPayParam(p.getExpressPayParam(CountryConstants.MEX))
                .create(),
        )
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

    private companion object {
        val MEXICO: ZoneId = ZoneId.of("America/Mexico_City")
        val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

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
