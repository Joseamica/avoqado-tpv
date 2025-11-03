package com.jaac.avoqado_tpv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jaac.avoqado_tpv.presentation.payment.PaymentFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity principal de Avoqado TPV
 *
 * Contiene el PaymentFragment para procesar pagos
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Cargar PaymentFragment si no está cargado
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PaymentFragment())
                .commit()
        }
    }
}