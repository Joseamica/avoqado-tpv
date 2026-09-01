# Línea base de los variants de terminal

Cumple la condición del founder (2026-08-31): el trabajo de `:campo` no puede cambiar
`production`, `sandbox`, `nexgo` ni `nexgoProd`.

## Uso
    ./baseline/capturar.sh antes           # una vez, antes de empezar
    ./baseline/capturar.sh despues-taskN   # después de cada tarea
    diff -r baseline/artefactos/antes baseline/artefactos/despues-taskN

En este plan `:app` NO se toca en ninguna tarea, así que el diff debe salir **VACÍO SIEMPRE**.
Cualquier diferencia es un error, no algo que explicar.

## Qué NO prueba
Que la app funcione. Sólo que no cambió. El cobro real en una PAX física sigue siendo obligatorio
antes de entregar. Tampoco ve: contenido del dex, recursos compilados, efectos de R8, o fallos de
inyección en tiempo de ejecución.

## Pendiente: los APK de referencia

Los 12 archivos de `baseline/artefactos/antes` (deps + manifest + BuildConfig de los 4 variants)
ya están generados y verificados. **Los `apk-*.txt` (listado de contenido del APK release de cada
variant) NO se capturaron todavía** — se pospusieron a la comprobación final por saturación de la
máquina (varios builds de R8 pesados de otras sesiones corriendo a la vez agotaban el swap y hacían
fallar `assembleRelease` con errores genéricos, no por código). Se generan así:

    for V in production sandbox nexgo nexgoProd; do
      unzip -l app/build/outputs/apk/$V/release/*.apk | awk '{print $4}' | sort \
        > baseline/artefactos/antes/apk-$V.txt
    done
