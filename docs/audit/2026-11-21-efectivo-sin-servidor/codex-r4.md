Audité `dd482755` contra `c15de497`. Encontré dos bloqueantes en el worker, uno introducido en esta ronda.

1. **ROMPE-LA-PROMESA · Una venta aún puede esperar la pasada de 15 minutos tras reconectar.** Si el worker ya tomó una tanda vacía y sigue ocupado cuando entra la venta y vuelve el servidor, `KEEP` descarta ambos avisos. Antes la tanda corta no agendaba otra pasada. Ahora sí puede hacerlo cuando subió un pago, pero en este caso `paymentProgress` queda en cero y la venta sigue esperando. **Certeza alta por código.**
2. **ROMPE-LA-PROMESA · Una fila que falla puede reintentarse de corrido.** Si cada tanda corta sube una venta nueva y la misma fila sigue recibiendo un error transitorio, cada éxito agenda otra pasada inmediata. Antes, esas tandas cortas no encadenaban reintentos por pagos. Ahora pueden gastar antes los intentos de esa fila, incluso si es de tarjeta. Una fila sola que falla o una fila `MANAGED` que espera su caja aporta cero avance y no sostiene el ciclo por sí misma. **Certeza alta por código, condicionada a que haya un pago exitoso en cada pasada.**

```text
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/workers/PaymentSyncWorker.kt
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/util/PaymentSyncScheduler.kt
```

**Pruebas del worker.** Las verificaciones de `continuarDespuesDeEstaPasada` conservan sus resultados esperados por inspección. La prueba «si contar falla el worker igual termina en success» también conserva su resultado porque el doble de `markSynced` devuelve cero. Con un cierre exitoso, una excepción del conteo nuevo sí devolvería `retry`, porque la consulta quedó fuera de `runCatching`. El repositorio real absorbe los errores de Room en esa consulta.

**PAX y referido.** En sandbox y producción, sólo el efectivo encolado pasa `true`. Con la franja activa, toma la copia local antes de mostrar el éxito. Los demás llamadores conservan la consulta habitual. Las dos pruebas nuevas del referido tienen firmas compatibles por inspección y comprueban la diferencia que faltaba en r3. Ambas preparan un referido válido y exigen `Fast`. Con servidor verifican una captura y con la franja verifican cero. La compilación y la ejecución quedan sin confirmar.

```text
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/test/java/com/jaac/avoqado_tpv/core/data/workers/PaymentSyncWorkerTest.kt
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt
/home/nube/Avoqado/wt/8905ac/avoqado-tpv/app/src/test/java/com/jaac/avoqado_tpv/features/checkout/presentation/CheckoutViewModelReferralTest.kt
```

En corto, PAX y el referido se ven bien por código. La cola todavía puede tardar en despertar al volver el servidor o reintentar demasiado rápido un pago que falla. La auditoría queda entregada en solo lectura.

**🟢🚪 YA PUEDES CERRAR ESTA CONVERSACIÓN**  
🧪 Probado ❌ · 🔀 Unido ➖ · 🚀 Producción ➖ · 📋 Pizarrón ✅ (foto del 20 nov., 16:59 UTC−08)

RESUMEN: bloqueantes=2 regresiones=1 ajenos=0 veredicto=CON-HALLAZGOS