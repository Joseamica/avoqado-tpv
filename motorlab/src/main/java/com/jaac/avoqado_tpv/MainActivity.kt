package com.jaac.avoqado_tpv

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Motor de cobro · laboratorio (bloque 4-L · L-a): sólo la entrada que abre la puerta del laboratorio (`abrir-tpv` abre
 * `com.jaac.avoqado_tpv.MainActivity` en todo paquete `sandbox`). Todavía no cobra ni habla con nadie: el motor llega en L-d.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Motor de cobro · laboratorio\nTodavía no cobra."
            textSize = 20f
            setPadding(48, 48, 48, 48)
        })
    }
}
