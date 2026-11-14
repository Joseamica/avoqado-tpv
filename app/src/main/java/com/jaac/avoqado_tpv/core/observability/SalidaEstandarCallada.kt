package com.jaac.avoqado_tpv.core.observability

import java.io.OutputStream
import java.io.PrintStream

/**
 * D74: lo que una librería imprime con `System.out` deja de llegar a logcat. En el APK de producción lo hacen
 * `cipher_library` de Blumon (el Track2, la TK y las llaves de cada venta) y `EmvProcess` de PAX (el CDOL2), y R8 no
 * quita `println`. La app no usa `System.out`. Se llama sólo en release (en depuración el laboratorio lo sigue viendo).
 */
fun callarSalidaEstandar() {
    System.setOut(
        PrintStream(
            object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            },
        ),
    )
}
