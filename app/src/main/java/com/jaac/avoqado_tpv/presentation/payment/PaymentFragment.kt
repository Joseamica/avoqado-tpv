package com.jaac.avoqado_tpv.presentation.payment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.jaac.avoqado_tpv.databinding.FragmentPaymentBinding
import com.jaac.avoqado_tpv.domain.models.PaymentRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * Fragment para procesar pagos con BlumonPay
 *
 * Características:
 * - Input de monto con validación
 * - Estados visuales del flujo de pago
 * - Instrucciones dinámicas al usuario
 * - Manejo de errores user-friendly
 */
@AndroidEntryPoint
class PaymentFragment : Fragment() {

    private var _binding: FragmentPaymentBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PaymentViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModelStates()
        observeViewModelEvents()
    }

    private fun setupUI() {
        // Botón de procesar pago
        binding.btnProcessPayment.setOnClickListener {
            val amountText = binding.etAmount.text.toString()
            if (amountText.isNotBlank()) {
                val amount = try {
                    BigDecimal(amountText)
                } catch (e: Exception) {
                    showToast("Monto inválido")
                    return@setOnClickListener
                }

                val paymentRequest = PaymentRequest(amount = amount)
                viewModel.startPayment(paymentRequest)
            } else {
                binding.tilAmount.error = "Ingrese un monto"
            }
        }

        // Botón de cancelar
        binding.btnCancel.setOnClickListener {
            viewModel.cancelTransaction()
        }

        // Limpiar error cuando el usuario escribe
        binding.etAmount.addTextChangedListener {
            binding.tilAmount.error = null
        }
    }

    private fun observeViewModelStates() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.paymentState.collect { state ->
                    updateUIForState(state)
                }
            }
        }
    }

    private fun observeViewModelEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.paymentEvents.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    private fun updateUIForState(state: PaymentState) {
        when (state) {
            is PaymentState.Idle -> {
                showIdleState()
            }

            is PaymentState.ConfiguringKernel -> {
                showLoadingState("Configurando...", "Preparando terminal")
            }

            is PaymentState.WaitingForCard -> {
                showWaitingForCardState(state.instructions)
            }

            is PaymentState.ProcessingCard -> {
                showLoadingState("Procesando", "Leyendo tarjeta ${state.cardType}")
            }

            is PaymentState.RequiresPin -> {
                showPinRequiredState()
            }

            is PaymentState.RequiresSignature -> {
                showSignatureRequiredState()
            }

            is PaymentState.ConnectingToProcessor -> {
                showLoadingState("Conectando", "Autorizando transacción")
            }

            is PaymentState.Success -> {
                showSuccessState(state.result.authorizationCode)
            }

            is PaymentState.Error -> {
                showErrorState(state.error.userMessage)
            }
        }
    }

    private fun showIdleState() {
        binding.progressIndicator.visibility = View.GONE
        binding.ivStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Listo para procesar"
        binding.tvInstructions.text = "Ingrese el monto y presione Procesar Pago"
        binding.btnProcessPayment.isEnabled = true
        binding.btnProcessPayment.visibility = View.VISIBLE
        binding.btnCancel.visibility = View.GONE
        binding.etAmount.isEnabled = true
    }

    private fun showLoadingState(status: String, instructions: String) {
        binding.progressIndicator.visibility = View.VISIBLE
        binding.ivStatus.visibility = View.GONE
        binding.tvStatus.text = status
        binding.tvInstructions.text = instructions
        binding.btnProcessPayment.visibility = View.GONE
        binding.btnCancel.visibility = View.VISIBLE
        binding.etAmount.isEnabled = false
    }

    private fun showWaitingForCardState(instructions: String) {
        binding.progressIndicator.visibility = View.VISIBLE
        binding.ivStatus.visibility = View.GONE
        binding.tvStatus.text = "Esperando tarjeta"
        binding.tvInstructions.text = instructions
        binding.btnProcessPayment.visibility = View.GONE
        binding.btnCancel.visibility = View.VISIBLE
        binding.etAmount.isEnabled = false
    }

    private fun showPinRequiredState() {
        binding.progressIndicator.visibility = View.GONE
        binding.ivStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "PIN requerido"
        binding.tvInstructions.text = "Por favor ingrese su PIN"
        binding.btnCancel.visibility = View.VISIBLE
    }

    private fun showSignatureRequiredState() {
        binding.progressIndicator.visibility = View.GONE
        binding.ivStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Firma requerida"
        binding.tvInstructions.text = "Por favor firme el comprobante"
        binding.btnCancel.visibility = View.VISIBLE
    }

    private fun showSuccessState(authCode: String) {
        binding.progressIndicator.visibility = View.GONE
        binding.ivStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "¡Pago aprobado!"
        binding.tvInstructions.text = "Código de autorización: $authCode"
        binding.btnProcessPayment.visibility = View.VISIBLE
        binding.btnProcessPayment.text = "Nuevo Pago"
        binding.btnProcessPayment.setOnClickListener {
            viewModel.resetState()
            binding.etAmount.text?.clear()
            binding.btnProcessPayment.text = "Procesar Pago"
            setupUI() // Reconfigure button click
        }
        binding.btnCancel.visibility = View.GONE
        binding.etAmount.isEnabled = true
    }

    private fun showErrorState(errorMessage: String) {
        binding.progressIndicator.visibility = View.GONE
        binding.ivStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Error"
        binding.tvInstructions.text = errorMessage
        binding.btnProcessPayment.visibility = View.VISIBLE
        binding.btnProcessPayment.text = "Reintentar"
        binding.btnProcessPayment.setOnClickListener {
            viewModel.resetState()
            binding.btnProcessPayment.text = "Procesar Pago"
            setupUI() // Reconfigure button click
        }
        binding.btnCancel.visibility = View.GONE
        binding.etAmount.isEnabled = true
    }

    private fun handleEvent(event: PaymentEvent) {
        when (event) {
            is PaymentEvent.ShowToast -> {
                showToast(event.message)
            }

            is PaymentEvent.NavigateToReceipt -> {
                // TODO: Navigate to receipt screen
            }

            is PaymentEvent.PlaySuccessSound -> {
                // TODO: Play success sound
            }

            is PaymentEvent.PlayErrorSound -> {
                // TODO: Play error sound
            }

            is PaymentEvent.Vibrate -> {
                // TODO: Vibrate device
            }

            is PaymentEvent.RequestPinInput -> {
                // TODO: Show PIN input dialog
            }

            is PaymentEvent.RequestSignatureCapture -> {
                // TODO: Show signature capture screen
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
