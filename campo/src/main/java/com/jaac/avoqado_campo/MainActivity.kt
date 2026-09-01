package com.jaac.avoqado_campo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.jaac.avoqado_campo.auth.LoginScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var venueId by remember { mutableStateOf<String?>(null) }
                    if (venueId == null) {
                        LoginScreen(alEntrar = { venueId = it })
                    } else {
                        Text("Dentro. Venue: $venueId")
                    }
                }
            }
        }
    }
}
