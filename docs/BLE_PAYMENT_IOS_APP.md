# BLE Payment iOS App - Guía Completa

App iOS extremadamente sencilla para enviar montos de pago al PAX Terminal vía Bluetooth Low Energy.

**Tiempo estimado:** 30-60 minutos (primera vez) | **Dificultad:** Principiante

---

## 📋 Índice de Contenidos

**Configuración Inicial:**
- [Requisitos Previos](#requisitos-previos)
- [Paso 1: Instalar Xcode](#paso-1-instalar-xcode-si-no-lo-tienes)
- [Paso 2: Crear Proyecto en Xcode](#paso-2-crear-proyecto-en-xcode-detallado)
- [Paso 3: Configurar Permisos Bluetooth](#paso-3-configurar-permisos-bluetooth)
- [Paso 4: Crear Servicio Bluetooth](#paso-4-crear-servicio-bluetooth)
- [Paso 5: Crear UI](#paso-5-crear-ui-contentview)

**Instalación y Pruebas:**
- [Paso 6: Probar en Dispositivo Real](#paso-6-probar-en-dispositivo-real)
- [Paso 7: Usar la App](#paso-7-usar-la-app)

**Ayuda y Recursos:**
- [Troubleshooting](#troubleshooting-solución-de-problemas)
- [TPV Android - Comportamiento esperado](#tpv-android---comportamiento-esperado)
- [Resumen Visual](#resumen-visual-del-flujo-completo)
- [Checklist Rápida](#checklist-rápida)

---

## Requisitos Previos

- **Mac** con macOS 11+
- **Xcode 14+** (descarga desde App Store)
- **iPad/iPhone** con iOS 14+ para probar
- **Cable Lightning/USB-C** para conectar dispositivo

---

## TPV Android - Comportamiento Esperado

Cuando el iPad/iPhone envía un payload válido con `amount` (en centavos):

- El TPV **inicia el flujo de pago** automáticamente.
- El pago se trata como **FAST payment** (sin orden).
- **Respeta los TPV Settings** actuales:
  - Si propinas están desactivadas → salta propina.
  - Si calificación está desactivada → salta calificación.
- El monto recibido se interpreta como **centavos**.

Ejemplo de payload:
```json
{"amount": 1500}
```

Resultado en TPV: pago por **$15.00** MXN (según configuración y moneda).

### ✅ Enviar propina y calificación (opcional)

Si el iPad ya capturó propina y/o calificación, puede enviarlas para que el TPV
salte esas pantallas.

```json
{"amount": 1500, "tip": 200, "rating": 5}
```

- `tip` está en **centavos**.
- `rating` es de **1 a 5**.
- Si falta alguno y el TPV lo requiere, el TPV **pedirá ese dato en pantalla**.

### ⏭️ Forzar salto de pantallas

Si quieres forzar el salto (aunque falten datos), envía:

```json
{"amount": 1500, "skipReview": true}
```

Esto hará que el TPV **no muestre** calificación ni propina.

### 📛 Nombre del dispositivo (CLIENT_INFO)

El iOS app envía un payload de identificación después de conectarse para que el TPV
reconozca el dispositivo (usado internamente para logs).

Ejemplo de payload:
```json
{"type":"CLIENT_INFO","deviceName":"iPad de Ana","deviceModel":"iPad","systemVersion":"17.2"}
```

- `deviceName` es el nombre visible del iPad (Settings → General → About → Name).
- Este mensaje **no inicia pagos** y solo se usa para UI.

### 🧹 Auto-limpieza de dispositivos registrados

Para evitar listas gigantes de dispositivos:

- Se eliminan automáticamente dispositivos **sin conexión en 30 días**.
- Se mantienen máximo **30 dispositivos** (los más recientes).

Esto **no afecta** la conexión activa ni el emparejamiento del sistema.

---

## Paso 1: Instalar Xcode (Si no lo tienes)

1. Abre **App Store** en tu Mac
2. Busca **"Xcode"**
3. Click en **"Obtener"** o **"Descargar"** (es gratis, pero pesa ~15GB)
4. Espera a que se instale (puede tardar 30-60 minutos)
5. Una vez instalado, ábrelo desde **Launchpad** o **Applications**

---

## Paso 2: Crear Proyecto en Xcode (DETALLADO)

### 2.1 Pantalla de Bienvenida

Cuando abras Xcode por primera vez, verás una ventana con opciones:

```
┌────────────────────────────────────────┐
│  Welcome to Xcode                      │
├────────────────────────────────────────┤
│                                        │
│  ○  Create a new Xcode project  ← CLICK AQUÍ
│                                        │
│  ○  Clone an existing project         │
│                                        │
│  ○  Open a project or file            │
│                                        │
└────────────────────────────────────────┘
```

**Click en: "Create a new Xcode project"**

---

### 2.2 Seleccionar Plantilla

Verás una ventana con pestañas arriba y plantillas abajo:

```
Pestañas arriba:
┌──────────────────────────────────────────┐
│ [iOS] [watchOS] [tvOS] [macOS] [visionOS]│  ← Asegúrate que "iOS" esté seleccionado
└──────────────────────────────────────────┘

Plantillas (selecciona "App"):
┌─────────────────────────────────────────────────┐
│  Application                                     │
│  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐        │
│  │ App  │  │ Game │  │ Augm.│  │ Sticr│        │  ← SELECCIONA "App"
│  │  📱  │  │  🎮  │  │  AR  │  │  😀  │        │    (primer ícono)
│  └──────┘  └──────┘  └──────┘  └──────┘        │
└─────────────────────────────────────────────────┘
```

**Selecciona el ícono "App" (📱) → Click "Next"**

---

### 2.3 Configurar Proyecto

Ahora verás un formulario con varios campos. **COPIA EXACTAMENTE ESTOS VALORES:**

```
┌─────────────────────────────────────────────────┐
│  Choose options for your new project:           │
├─────────────────────────────────────────────────┤
│                                                  │
│  Product Name:    PAX Payment          ← ESCRIBE ESTO
│                                                  │
│  Team:            None                 ← Déjalo en "None"
│                   (o selecciona tu Apple ID      │
│                    si aparece)                   │
│                                                  │
│  Organization     com.avoqado          ← ESCRIBE ESTO
│  Identifier:                                     │
│                                                  │
│  Bundle          com.avoqado.PAX-Payment        │
│  Identifier:     (se genera automáticamente)    │
│                                                  │
│  Interface:       ☑ SwiftUI            ← IMPORTANTE: Selecciona "SwiftUI"
│                   ☐ Storyboard         │  (NO Storyboard)
│                                                  │
│  Language:        ☑ Swift              ← Debe estar en "Swift"
│                   ☐ Objective-C        │  (NO Objective-C)
│                                                  │
│  Storage:         ☐ Use Core Data      ← DESMARCADO (NO actives esto)
│                                                  │
│  ☐ Include Tests                       ← DESMARCADO (NO actives esto)
│                                                  │
└─────────────────────────────────────────────────┘
```

**VERIFICACIÓN FINAL antes de continuar:**
- ✅ Product Name: **PAX Payment**
- ✅ Organization Identifier: **com.avoqado**
- ✅ Interface: **SwiftUI** (NO Storyboard)
- ✅ Language: **Swift** (NO Objective-C)
- ❌ Use Core Data: **DESMARCADO**
- ❌ Include Tests: **DESMARCADO**

**Click "Next"**

---

### 2.4 Guardar Proyecto

Aparecerá un cuadro de diálogo para elegir dónde guardar el proyecto:

```
┌──────────────────────────────────────────┐
│  Save As: PAX Payment                    │
├──────────────────────────────────────────┤
│  Where:   [Documents ▼]                  │
│                                          │
│  ☐ Create Git repository on my Mac      │  ← OPCIONAL (puedes dejarlo marcado)
│                                          │
│  [Cancel]              [Create]          │  ← Click "Create"
└──────────────────────────────────────────┘
```

**Recomendación:** Guárdalo en **Documents** o **Desktop** para encontrarlo fácil.

**Click "Create"**

---

### 2.5 Pantalla Principal de Xcode

Ahora verás la pantalla principal de Xcode:

```
┌─────────────────────────────────────────────────────────────┐
│  PAX Payment - ContentView.swift                            │
├────┬────────────────────────────────────────────────────────┤
│    │                                                         │
│  📁 │  import SwiftUI                                        │
│    │                                                         │
│  ▼ PAX Payment  │  struct ContentView: View {              │
│    │ PAX PaymentApp.swift   │      var body: some View {   │
│    │ ContentView.swift      │          VStack {            │
│    │ Assets.xcassets        │              Image(...)       │
│    │ Preview Content        │              Text("Hello")    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**✅ PROYECTO CREADO EXITOSAMENTE**

Deberías ver:
- **Panel izquierdo:** Archivos del proyecto (Navigator)
- **Panel central:** Código de ContentView.swift (Editor)
- **Panel derecho:** Preview (vista previa del iPhone)

---

### 2.6 Verificar que Todo Funciona

Antes de continuar, vamos a verificar que el proyecto compila correctamente:

1. **Arriba a la izquierda**, verás algo como:
   ```
   PAX Payment > iPhone 15 Pro
   ```

2. **Click en el símbolo ▶️ (Play)** al lado de "PAX Payment"

3. Espera unos segundos... debería aparecer el **Simulador de iPhone**

4. En el simulador deberías ver:
   ```
   ┌────────────────┐
   │   📱           │
   │                │
   │   Hello, world!│
   │                │
   └────────────────┘
   ```

5. Si ves esto, **¡PERFECTO!** El proyecto funciona.

6. **Cierra el simulador** (Cmd+Q o click en X)

---

## Paso 3: Configurar Permisos Bluetooth

### 3.1 Abrir Info.plist

En el **panel izquierdo** (Navigator), verás una lista de archivos:

```
▼ PAX Payment
  ├─ PAX PaymentApp.swift
  ├─ ContentView.swift
  ├─ Assets.xcassets
  └─ ▼ Preview Content
```

**Necesitas encontrar Info.plist:**

1. Click en el **proyecto azul "PAX Payment"** (arriba de todo en el Navigator)
2. En el panel central verás pestañas arriba
3. Click en la pestaña **"Info"**
4. Ahí está Info.plist (en forma de tabla)

---

### 3.2 Ver Info.plist como Código

Necesitamos verlo como código XML, no como tabla:

1. Click derecho en cualquier parte del panel central
2. Selecciona **"Show Raw Keys/Values"** (si aparece)
3. Luego click derecho de nuevo → **"Open As"** → **"Source Code"**

Ahora verás código XML que se ve así:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC...>
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>$(DEVELOPMENT_LANGUAGE)</string>
    ...
</dict>
</plist>
```

---

### 3.3 Agregar Permisos Bluetooth

**IMPORTANTE:** Necesitas agregar 2 líneas ANTES del `</dict>` final.

Busca la línea que dice `</dict>` (está casi al final del archivo).

**JUSTO ANTES** de `</dict>`, agrega estas líneas:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Esta app necesita Bluetooth para enviar pagos al terminal PAX</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>Esta app necesita Bluetooth para conectarse al terminal PAX</string>
```

**Resultado final:** El archivo Info.plist debería verse así:

```xml
...
    <key>UIApplicationSupportsIndirectInputEvents</key>
    <true/>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>Esta app necesita Bluetooth para enviar pagos al terminal PAX</string>
    <key>NSBluetoothPeripheralUsageDescription</key>
    <string>Esta app necesita Bluetooth para conectarse al terminal PAX</string>
</dict>
</plist>
```

**Guarda el archivo:** Cmd+S

---

## Paso 4: Crear Servicio Bluetooth

### 4.1 Crear Nuevo Archivo Swift

1. En el **Navigator** (panel izquierdo), click derecho en la carpeta **"PAX Payment"** (la carpeta amarilla)

```
Click derecho aquí ↓
▼ PAX Payment  ← Click derecho aquí
  ├─ PAX PaymentApp.swift
  ├─ ContentView.swift
  └─ ...
```

2. En el menú que aparece, selecciona **"New File..."**

```
┌────────────────────────────────┐
│  New File...            ⌘N     │  ← Click aquí
│  New File from Template...     │
│  Add Files to "PAX Payment"... │
│  ───────────────────────────   │
│  Delete                        │
│  ...                           │
└────────────────────────────────┘
```

---

### 4.2 Seleccionar Plantilla de Archivo

Aparecerá una ventana con plantillas:

```
Pestañas arriba:
┌────────────────────────────────────┐
│ [iOS] [watchOS] [tvOS] [...] [Other]│
└────────────────────────────────────┘

En la sección "Source" (izquierda):
┌───────────────────────────────────────┐
│ Source                                 │
│  ┌──────────┐  ┌──────────┐           │
│  │ Swift    │  │ SwiftUI  │           │  ← SELECCIONA "Swift File"
│  │ File     │  │ View     │           │
│  └──────────┘  └──────────┘           │
│  ┌──────────┐  ┌──────────┐           │
│  │ Objective│  │ Header   │           │
│  │ -C File  │  │ File     │           │
│  └──────────┘  └──────────┘           │
└───────────────────────────────────────┘
```

**Selecciona "Swift File" → Click "Next"**

---

### 4.3 Nombrar el Archivo

Aparecerá un campo para el nombre:

```
┌─────────────────────────────────────┐
│  Save As:  File                     │  ← Borra "File" y escribe:
│                                     │     BluetoothService.swift
│  Where:    PAX Payment              │
│            [PAX Payment ▼]          │
│                                     │
│  Group:    PAX Payment              │
│                                     │
│  Targets:  ☑ PAX Payment            │
│                                     │
│  [Cancel]              [Create]     │  ← Click "Create"
└─────────────────────────────────────┘
```

**Escribe:** `BluetoothService.swift`

**Click "Create"**

---

### 4.4 Reemplazar Contenido del Archivo

Ahora verás un archivo casi vacío:

```swift
//
//  BluetoothService.swift
//  PAX Payment
//
//  Created by ...
//

import Foundation
```

**SELECCIONA TODO** (Cmd+A) y **BORRA TODO**.

Luego **COPIA Y PEGA** este código completo:

```swift
import Foundation
import CoreBluetooth
import Combine

class BluetoothService: NSObject, ObservableObject {
    // UUIDs del PAX Terminal
    private let SERVICE_UUID = CBUUID(string: "00001234-0000-1000-8000-00805f9b34fb")
    private let CHARACTERISTIC_UUID = CBUUID(string: "00001235-0000-1000-8000-00805f9b34fb")

    // Published properties para UI
    @Published var isScanning = false
    @Published var isConnected = false
    @Published var statusMessage = "Desconectado"
    @Published var devices: [CBPeripheral] = []

    // Core Bluetooth
    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristic: CBGattCharacteristic?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: - Public Methods

    func startScanning() {
        devices.removeAll()
        isScanning = true
        statusMessage = "Buscando terminales..."
        centralManager.scanForPeripherals(withServices: [SERVICE_UUID], options: nil)

        // Auto-detener escaneo después de 10 segundos
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.stopScanning()
        }
    }

    func stopScanning() {
        isScanning = false
        centralManager.stopScan()
        if devices.isEmpty {
            statusMessage = "No se encontraron terminales"
        }
    }

    func connect(to peripheral: CBPeripheral) {
        self.peripheral = peripheral
        peripheral.delegate = self
        centralManager.connect(peripheral, options: nil)
        statusMessage = "Conectando..."
    }

    func disconnect() {
        if let peripheral = peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    func sendPayment(amount: Int) {
        guard let characteristic = characteristic,
              let peripheral = peripheral,
              isConnected else {
            statusMessage = "Error: No conectado al terminal"
            return
        }

        // Crear JSON
        let json = "{\"amount\": \(amount)}"
        guard let data = json.data(using: .utf8) else {
            statusMessage = "Error: No se pudo crear mensaje"
            return
        }

        // Enviar vía BLE
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
        statusMessage = "Enviando $\(Double(amount)/100)..."
    }
}

// MARK: - CBCentralManagerDelegate

extension BluetoothService: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            statusMessage = "Bluetooth activado"
        case .poweredOff:
            statusMessage = "Bluetooth desactivado"
        case .unauthorized:
            statusMessage = "Bluetooth no autorizado"
        case .unsupported:
            statusMessage = "Bluetooth no soportado"
        default:
            statusMessage = "Bluetooth no disponible"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        if !devices.contains(where: { $0.identifier == peripheral.identifier }) {
            devices.append(peripheral)
            statusMessage = "Encontrado: \(peripheral.name ?? "Desconocido")"
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        isConnected = true
        statusMessage = "Conectado a \(peripheral.name ?? "Terminal")"
        peripheral.discoverServices([SERVICE_UUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        statusMessage = "Desconectado"
        self.peripheral = nil
        self.characteristic = nil
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        statusMessage = "Error de conexión: \(error?.localizedDescription ?? "Desconocido")"
    }
}

// MARK: - CBPeripheralDelegate

extension BluetoothService: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }

        for service in services {
            if service.uuid == SERVICE_UUID {
                peripheral.discoverCharacteristics([CHARACTERISTIC_UUID], for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for characteristic in characteristics {
            if characteristic.uuid == CHARACTERISTIC_UUID {
                self.characteristic = characteristic
                statusMessage = "Listo para enviar pagos"

                // Habilitar notificaciones
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            statusMessage = "Error al enviar: \(error.localizedDescription)"
        } else {
            statusMessage = "Pago enviado exitosamente ✅"
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        // Recibir respuesta del PAX (notificación)
        guard let data = characteristic.value,
              let response = String(data: data, encoding: .utf8) else { return }

        statusMessage = "Respuesta: \(response)"
    }
}
```

**Guarda el archivo:** Cmd+S

Ahora deberías ver en el Navigator:

```
▼ PAX Payment
  ├─ PAX PaymentApp.swift
  ├─ BluetoothService.swift  ← NUEVO ARCHIVO
  ├─ ContentView.swift
  └─ ...
```

**✅ Archivo BluetoothService.swift creado correctamente**

---

## Paso 5: Crear UI (ContentView)

### 5.1 Abrir ContentView.swift

En el **Navigator** (panel izquierdo), click en **`ContentView.swift`**

```
▼ PAX Payment
  ├─ PAX PaymentApp.swift
  ├─ BluetoothService.swift
  ├─ ContentView.swift  ← Click aquí
  └─ ...
```

Verás código que se ve así:

```swift
import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack {
            Image(systemName: "globe")
                .imageScale(.large)
                .foregroundStyle(.tint)
            Text("Hello, world!")
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
```

---

### 5.2 Reemplazar Contenido

**SELECCIONA TODO** (Cmd+A) y **BORRA TODO**.

Luego **COPIA Y PEGA** este código completo:

```swift
import SwiftUI

struct ContentView: View {
    @StateObject private var bluetooth = BluetoothService()

    // Montos predefinidos (en centavos)
    let quickAmounts = [
        1000,   // $10
        2000,   // $20
        5000,   // $50
        10000,  // $100
        15000,  // $150
        20000   // $200
    ]

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                // Estado de conexión
                VStack(spacing: 8) {
                    Image(systemName: bluetooth.isConnected ? "checkmark.circle.fill" : "wifi.slash")
                        .font(.system(size: 50))
                        .foregroundColor(bluetooth.isConnected ? .green : .gray)

                    Text(bluetooth.statusMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()

                Divider()

                // Botones de escaneo/conexión
                if !bluetooth.isConnected {
                    VStack(spacing: 15) {
                        Button(action: {
                            bluetooth.startScanning()
                        }) {
                            HStack {
                                Image(systemName: "magnifyingglass")
                                Text(bluetooth.isScanning ? "Buscando..." : "Buscar Terminal PAX")
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(bluetooth.isScanning ? Color.gray : Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(10)
                        }
                        .disabled(bluetooth.isScanning)

                        // Lista de dispositivos encontrados
                        if !bluetooth.devices.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Terminales encontrados:")
                                    .font(.headline)

                                ForEach(bluetooth.devices, id: \.identifier) { device in
                                    Button(action: {
                                        bluetooth.connect(to: device)
                                    }) {
                                        HStack {
                                            Image(systemName: "antenna.radiowaves.left.and.right")
                                            Text(device.name ?? "PAX Terminal")
                                            Spacer()
                                            Image(systemName: "chevron.right")
                                        }
                                        .padding()
                                        .background(Color.gray.opacity(0.1))
                                        .cornerRadius(8)
                                    }
                                }
                            }
                        }
                    }
                    .padding()
                } else {
                    // Botones de monto (solo si conectado)
                    VStack(spacing: 15) {
                        Text("Selecciona Monto")
                            .font(.headline)

                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 15) {
                            ForEach(quickAmounts, id: \.self) { amount in
                                Button(action: {
                                    bluetooth.sendPayment(amount: amount)
                                }) {
                                    VStack {
                                        Text("$\(formatAmount(amount))")
                                            .font(.title2)
                                            .fontWeight(.bold)
                                    }
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 80)
                                    .background(Color.green)
                                    .foregroundColor(.white)
                                    .cornerRadius(12)
                                }
                            }
                        }

                        // Botón de desconectar
                        Button(action: {
                            bluetooth.disconnect()
                        }) {
                            HStack {
                                Image(systemName: "xmark.circle")
                                Text("Desconectar")
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.red)
                            .foregroundColor(.white)
                            .cornerRadius(10)
                        }
                    }
                    .padding()
                }

                Spacer()
            }
            .navigationTitle("PAX Payment")
        }
    }

    private func formatAmount(_ cents: Int) -> String {
        let dollars = Double(cents) / 100.0
        return String(format: "%.2f", dollars)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
```

**Guarda el archivo:** Cmd+S

**✅ UI creada correctamente**

Deberías ver el código de la UI con botones de monto.

---

## Paso 6: Probar en Dispositivo Real

### ⚠️ IMPORTANTE: BLE NO funciona en simulador

Bluetooth Low Energy requiere **hardware real**. Necesitas un iPad o iPhone físico conectado a tu Mac.

---

### 6.1 Conectar iPad/iPhone al Mac

1. **Conecta tu iPad/iPhone** al Mac con cable Lightning o USB-C
2. Si es la primera vez, aparecerá en el iPad:
   ```
   ┌────────────────────────────┐
   │  Trust This Computer?      │
   │                            │
   │  Your settings will be     │
   │  accessible from this      │
   │  computer when connected.  │
   │                            │
   │  [Don't Trust]  [Trust]    │  ← Click "Trust"
   └────────────────────────────┘
   ```
3. Ingresa el PIN del iPad si te lo pide

---

### 6.2 Seleccionar Dispositivo en Xcode

En la parte superior de Xcode, verás algo como:

```
┌──────────────────────────────────────┐
│ PAX Payment > iPhone 15 Pro    [▶️] │
└──────────────────────────────────────┘
        ↑           ↑
     Proyecto   Destino (click aquí)
```

**Click en "iPhone 15 Pro"** (o lo que diga ahí)

Aparecerá un menú desplegable:

```
┌───────────────────────────────┐
│ iOS Simulators                │
│  ○ iPhone 15 Pro              │
│  ○ iPhone 15                  │
│  ○ iPad Pro 12.9"             │
│ ──────────────────────────    │
│ iOS Devices                   │
│  ● iPad de Usuario            │  ← SELECCIONA TU DISPOSITIVO REAL
│                               │     (Aparece con nombre del dispositivo)
│ ──────────────────────────    │
│ Designed for iPad             │
│  ○ My Mac (Designed for iPad) │
└───────────────────────────────┘
```

**Selecciona tu iPad/iPhone real** (aparece con ● negro cuando está conectado)

---

### 6.3 Activar Developer Mode en iPad (iOS 16+)

Si tu iPad tiene iOS 16 o más nuevo, necesitas activar Developer Mode:

1. En el **iPad**, abre **Settings** (Ajustes)
2. Ve a **Privacy & Security** (Privacidad y Seguridad)
3. Scroll hasta el final → **Developer Mode**
4. Actívalo (switch a verde)
5. Te pedirá **reiniciar el iPad** → Acepta
6. Después de reiniciar, aparecerá un mensaje pidiendo confirmación → **Confirma**

**Nota:** Si tu iPad tiene iOS 15 o anterior, omite este paso.

---

### 6.4 Configurar Firma de Código (Code Signing)

Antes de poder instalar la app en tu dispositivo, necesitas configurar "Code Signing":

1. En el **Navigator** (panel izquierdo), click en el **proyecto azul "PAX Payment"** (arriba de todo)

2. En el panel central, verás **TARGETS**. Click en **"PAX Payment"** (el ícono de app)

```
PROJECT
  PAX Payment

TARGETS
  PAX Payment  ← Click aquí
```

3. Arriba verás varias pestañas. Click en **"Signing & Capabilities"**

```
[General] [Signing & Capabilities] [Resource Tags] [Info] [Build Settings] [Build Phases]
            ↑ Click aquí
```

4. Verás una sección que dice:

```
┌────────────────────────────────────────────┐
│ Automatically manage signing  ☑            │  ← Asegúrate que esté MARCADO
│                                            │
│ Team:  [None ▼]                            │  ← Click en "None"
└────────────────────────────────────────────┘
```

---

### 6.5 Agregar Apple ID

Si el menú **Team** dice "None":

1. Click en el menú **Team**
2. Selecciona **"Add an Account..."**

```
┌──────────────────────────────┐
│ None                         │
│ ─────────────────────────    │
│ Add an Account...            │  ← Click aquí
└──────────────────────────────┘
```

3. Ingresa tu **Apple ID** (el mismo de iCloud/App Store)
4. Ingresa tu **contraseña**
5. Click **"Next"** y espera a que verifique

Una vez agregado, verás tu Apple ID en el menú **Team**:

```
Team:  [Tu Nombre (Personal Team) ▼]
```

---

### 6.6 Resolver Error de Bundle Identifier (si aparece)

Si aparece un error rojo que dice:

```
❌ Failed to register bundle identifier.
   The app identifier "com.avoqado.PAX-Payment" cannot be registered...
```

**Solución:** Cambia el Bundle Identifier a algo único:

1. En la misma pantalla **"Signing & Capabilities"**, busca:

```
Bundle Identifier:  com.avoqado.PAX-Payment
                    ↑ Cambia esto
```

2. Reemplázalo con tu apellido o iniciales:

```
Bundle Identifier:  com.tuapellido.PAXPayment
                    (ejemplo: com.garcia.PAXPayment)
```

3. El error rojo debería desaparecer y aparecer un ✅ verde

---

### 6.7 Ejecutar en iPad

Ahora sí, todo listo:

1. Asegúrate que arriba diga:
   ```
   PAX Payment > iPad de Usuario
   ```

2. Click en el botón **▶️ Play** (o presiona Cmd+R)

3. Espera unos segundos... Xcode compilará y:
   - Mostrará progreso: "Building PAX Payment..."
   - Luego: "Running PAX Payment..."

4. En tu **iPad**, aparecerá la app **"PAX Payment"** 🎉

---

### 6.8 Si Aparece Error de "Untrusted Developer"

La primera vez que instales una app de desarrollo, el iPad dirá:

```
┌────────────────────────────────────┐
│  Untrusted Developer               │
│                                    │
│  Your device management settings   │
│  do not allow apps from            │
│  "Apple Development: tu@email.com" │
│  to run on this iPad.              │
│                                    │
│  [Cancel]              [OK]        │
└────────────────────────────────────┘
```

**Solución:**

1. En el **iPad**, abre **Settings** (Ajustes)
2. Ve a **General** → **VPN & Device Management**
3. En la sección **"Developer App"**, verás tu email
4. Click en tu email
5. Click en **"Trust Apple Development: tu@email.com"**
6. Confirma: **"Trust"**
7. Regresa a la Home Screen y abre la app **"PAX Payment"**

**✅ La app debería abrir correctamente**

---

## Paso 7: Usar la App

### 7.1 Preparar PAX Terminal

1. **En PAX Terminal:**
   - Abre la app Avoqado TPV
   - Login con tu usuario
   - Ve a **SuperAdmin** (menú lateral)
   - Toca **"Start BLE Payment Server"**
   - Acepta **todos los permisos Bluetooth** cuando aparezcan

2. **Verifica que esté corriendo:**
   - El botón debería cambiar a **"Stop BLE Payment Server"**
   - Debería decir: "✅ BLE Payment Server started successfully"

---

### 7.2 Conectar desde iPad

1. **En iPad**, abre la app **"PAX Payment"**

2. Deberías ver:
   ```
   ┌─────────────────────┐
   │  PAX Payment        │
   ├─────────────────────┤
   │   📡                │
   │   Desconectado      │
   ├─────────────────────┤
   │                     │
   │  [Buscar Terminal   │
   │   PAX]              │
   │                     │
   └─────────────────────┘
   ```

3. Toca el botón **"Buscar Terminal PAX"**

4. El botón cambiará a "Buscando..." (gris)

5. Espera **5-10 segundos**

6. Aparecerá una lista de dispositivos encontrados:
   ```
   Terminales encontrados:
   ┌──────────────────────────┐
   │  📡 A910S-2941548417  >  │  ← Toca aquí
   └──────────────────────────┘
   ```

7. **Toca el terminal** para conectar

---

### 7.3 Enlace Bluetooth (Con PIN)

Este flujo **requiere emparejamiento**. La primera vez verás un PIN en iOS
y el TPV mostrará su diálogo de vínculo.

**Importante:**
- Ingresa el PIN en el TPV cuando se muestre el diálogo del sistema.
- Una vez emparejado, **no debería volver a pedir PIN** (a menos que olvides el vínculo).
- En Android puede aparecer “emparejar con null” — es normal (iOS no envía el nombre durante el enlace).

**Si el prompt se repite:**
- iPad: **Ajustes → Bluetooth → (i) → Olvidar**
- Reinicia el TPV para limpiar el cache de dispositivos BLE
- Vuelve a conectar desde la app iOS.

---

### 7.4 Enviar Pagos

Una vez conectado, la app cambiará a:

```
┌─────────────────────────┐
│  PAX Payment            │
├─────────────────────────┤
│   ✅                    │
│   Conectado a A910S     │
│   Listo para enviar     │
├─────────────────────────┤
│  Selecciona Monto       │
│                         │
│  [$10.00]    [$20.00]   │
│  [$50.00]    [$100.00]  │
│  [$150.00]   [$200.00]  │
│                         │
│  [🔴 Desconectar]       │
└─────────────────────────┘
```

**Toca cualquier botón de monto** (ej: $10.00)

El botón se presionará y abajo verás:
```
Enviando $10.00...
```

Luego:
```
Pago enviado exitosamente ✅
```

---

### 7.5 Verificar en PAX

En tu **Mac**, abre Terminal y ejecuta:

```bash
adb logcat -s BluetoothPaymentServer:* | grep "BLE-Server"
```

Deberías ver:

```
10:07:29.578  I  📥 [BLE-Server] Received payment data: {"amount": 1000}
10:07:29.585  I  💰 [BLE-Server] Payment amount: 1000 cents
10:07:29.586  I  💰 [SuperAdmin] Received payment request: 1000 cents
```

**✅ ¡FUNCIONÓ!** El monto llegó al PAX correctamente.

---

## Troubleshooting (Solución de Problemas)

### ❌ Error: "Bluetooth desactivado"

**Causa:** Bluetooth está apagado en el iPad

**Solución:**
1. En iPad: **Settings** → **Bluetooth** → Actívalo (switch verde)
2. Cierra y reabre la app "PAX Payment"

---

### ❌ Error: "No se encontraron terminales"

**Causa:** BLE Payment Server no está corriendo en PAX, o está muy lejos

**Solución:**
1. En PAX: Verifica que **"BLE Payment Server"** esté **Started** (botón rojo)
2. Acércate más al PAX (máximo **10 metros**, sin paredes gruesas)
3. En iPad: Toca **"Buscar Terminal PAX"** de nuevo
4. Espera 10 segundos completos

**Verificación adicional en PAX:**
```bash
adb logcat -s BluetoothPaymentServer:* | grep "Advertising"
# Deberías ver: ✅ [BLE-Server] Advertising started successfully
```

---

### ❌ Error: "Error de conexión"

**Causa:** El emparejamiento no se completó (PIN no ingresado o diálogo cancelado).

**Solución rápida (con PIN):**
1. Reintenta y **ingresa el PIN** en el TPV cuando aparezca el diálogo.
2. Si el diálogo no aparece en TPV, reinicia la app del TPV y vuelve a intentar.
3. Si sigue fallando: iPad **Ajustes → Bluetooth → (i) → Olvidar**, y reinicia el TPV.

---

### ❌ Error: "Connection timed out" o se desconecta constantemente

**Causa:** Interferencia Bluetooth o distancia

**Solución:**
1. **Acércate más** - Máximo 3 metros para conexión estable
2. **Elimina interferencias:**
   - Apaga WiFi del PAX si no lo necesitas
   - Aleja otros dispositivos Bluetooth
   - Evita áreas con muchos dispositivos WiFi
3. **Reinicia Bluetooth:**
   - En PAX: Settings → Bluetooth → OFF → espera 5s → ON
   - En iPad: Settings → Bluetooth → OFF → espera 5s → ON

---

### ❌ Error: Xcode no compila

**Síntomas:**
```
Build Failed
❌ Errors (multiple)
```

**Solución 1 - Limpiar Build:**
1. En Xcode: **Product** → **Clean Build Folder** (Cmd+Shift+K)
2. Espera a que termine
3. **Product** → **Build** (Cmd+B)

**Solución 2 - Verificar código:**
1. Asegúrate de haber **copiado TODO** el código de `BluetoothService.swift`
2. Asegúrate de haber **copiado TODO** el código de `ContentView.swift`
3. Verifica que no haya errores de tipeo

**Solución 3 - Reiniciar Xcode:**
1. Cierra Xcode completamente (Cmd+Q)
2. Reabre el proyecto
3. Espera a que indexe los archivos (barra de progreso arriba)
4. Intenta compilar de nuevo

---

### ❌ Error: "Failed to register bundle identifier"

**Causa:** El Bundle Identifier ya está en uso

**Solución:**
1. En Xcode: Click proyecto azul → **Signing & Capabilities**
2. Cambia **Bundle Identifier** a:
   ```
   com.tuapellido.PAXPayment
   (reemplaza "tuapellido" con tu apellido real)
   ```
3. El error rojo ❌ debería cambiar a verde ✅

---

### ❌ Error: "No iPad connected" o dispositivo no aparece

**Causa:** iPad no reconocido por Xcode

**Solución:**
1. **Desconecta** el cable del iPad
2. Espera 5 segundos
3. **Reconecta** el cable
4. En iPad, acepta **"Trust This Computer"**
5. En Xcode, el iPad debería aparecer en el menú de dispositivos

**Si sigue sin aparecer:**
1. Intenta con otro cable USB (puede estar dañado)
2. Intenta otro puerto USB del Mac
3. Reinicia el iPad
4. Reinicia Xcode

---

### ❌ Error: App no aparece en iPad después de instalar

**Causa:** Developer Mode no activado (iOS 16+)

**Solución:**
1. En iPad: **Settings** → **Privacy & Security** → **Developer Mode** → ON
2. **Reinicia el iPad**
3. Después del reinicio, confirma activación
4. Vuelve a ejecutar la app desde Xcode

---

### ❌ Error: "Untrusted Developer"

**Causa:** Certificado de desarrollo no confiado

**Solución:**
1. En iPad: **Settings** → **General** → **VPN & Device Management**
2. En **"Developer App"**, toca tu email
3. Toca **"Trust Apple Development: ..."**
4. Confirma **"Trust"**
5. Intenta abrir la app de nuevo

---

## Personalización Rápida

### Cambiar Montos

En `ContentView.swift`, línea ~11:
```swift
let quickAmounts = [
    500,    // $5
    1000,   // $10
    2500,   // $25
    5000,   // $50
]
```

### Cambiar Colores

Busca `.background(Color.green)` y cámbialo a:
- `.background(Color.blue)`
- `.background(Color.orange)`
- `.background(Color.purple)`

---

## Siguiente Paso: Procesar Pago en PAX

Actualmente el PAX solo recibe el monto y lo muestra en logs. Para procesar el pago real, necesitas integrar el callback del BLE Payment Server con PaymentViewModel.

Ver: `docs/BLE_PAYMENT_INTEGRATION.md` (próximo documento)

---

## Archivos del Proyecto

```
PAX Payment/
├── PAX_PaymentApp.swift          (generado automáticamente)
├── ContentView.swift              (UI - paso 4)
├── BluetoothService.swift         (BLE logic - paso 3)
├── Info.plist                     (permisos - paso 2)
└── Assets.xcassets/               (iconos/imágenes)
```

---

## Resumen Visual del Flujo Completo

```
┌─────────────────────────────────────────────────────────────┐
│                     FLUJO COMPLETO                          │
└─────────────────────────────────────────────────────────────┘

1. CREAR PROYECTO
   ┌──────────────┐
   │   Xcode      │ → "Create new project"
   │   📱 iOS App │ → SwiftUI + Swift
   └──────────────┘

2. CONFIGURAR PERMISOS
   ┌────────────────┐
   │  Info.plist    │ → Agregar NSBluetoothAlwaysUsageDescription
   │  🔐 Bluetooth  │ → Agregar NSBluetoothPeripheralUsageDescription
   └────────────────┘

3. AGREGAR LÓGICA BLE
   ┌──────────────────────┐
   │ BluetoothService.swift│ → Copiar código completo
   │ 📡 BLE Manager        │ → Scan, Connect, Write
   └──────────────────────┘

4. CREAR UI
   ┌──────────────────┐
   │ ContentView.swift│ → Copiar código completo
   │ 🎨 Botones       │ → $10, $20, $50, etc.
   └──────────────────┘

5. PROBAR EN IPAD
   ┌────────────────┐
   │  iPad + Cable  │ → Conectar al Mac
   │  ⚙️ Developer  │ → Activar Developer Mode
   │     Mode       │ → Trust Computer
   └────────────────┘

6. EJECUTAR
   ┌────────────────┐
   │  Xcode ▶️      │ → Compilar e instalar
   │  📲 Instalar   │ → Trust Developer
   └────────────────┘

7. USAR APP
   PAX Terminal              iPad App
   ┌──────────────┐        ┌──────────────┐
   │ Start BLE    │        │ Buscar PAX   │
   │ Server       │ ←──────│ Conectar     │
   └──────────────┘  BLE   └──────────────┘
         ↓                        ↓
   ┌──────────────┐        ┌──────────────┐
   │ Enlace BLE   │        │ Selecciona   │
   │ (auto-acepta)│        │ Monto: $10   │
   └──────────────┘        └──────────────┘
         ↓                        ↓
   ┌──────────────────────────────────────┐
   │ 📥 Received: {"amount": 1000}        │
   │ 💰 Payment amount: 1000 cents        │
   └──────────────────────────────────────┘
```

---

## Checklist Rápida

Antes de empezar, verifica que tienes:

- [ ] **Mac** con macOS 11+
- [ ] **Xcode** instalado (desde App Store)
- [ ] **iPad/iPhone** con iOS 14+ (hardware real, NO simulador)
- [ ] **Cable** Lightning o USB-C para conectar dispositivo
- [ ] **Apple ID** (para firmar la app)
- [ ] **PAX Terminal** con app Avoqado TPV instalada
- [ ] **Terminal/ADB** configurado en el Mac (para ver logs)

---

## Pasos Completados

A medida que sigas la guía, marca cada paso:

**Configuración Inicial:**
- [ ] Paso 1: Xcode instalado
- [ ] Paso 2: Proyecto creado y compilando
- [ ] Paso 3: Permisos Bluetooth agregados a Info.plist
- [ ] Paso 4: BluetoothService.swift creado
- [ ] Paso 5: ContentView.swift actualizado

**Instalación en iPad:**
- [ ] Paso 6.1: iPad conectado y reconocido
- [ ] Paso 6.2: Dispositivo seleccionado en Xcode
- [ ] Paso 6.3: Developer Mode activado (iOS 16+)
- [ ] Paso 6.4-6.6: Code Signing configurado (sin errores rojos)
- [ ] Paso 6.7: App instalada exitosamente en iPad
- [ ] Paso 6.8: Developer confiado (si fue necesario)

**Prueba de Conexión:**
- [ ] Paso 7.1: BLE Server corriendo en PAX
- [ ] Paso 7.2: iPad encuentra el terminal PAX
- [ ] Paso 7.3: Enlace BLE completado (si aparece prompt en iOS)
- [ ] Paso 7.4: Monto enviado y recibido correctamente
- [ ] Paso 7.5: Logs confirman recepción en PAX

**✅ Si todos los pasos están marcados, la app está funcionando correctamente**

---

## Notas Importantes

1. **No puedes probar en Simulador** - Core Bluetooth requiere hardware real (iPad/iPhone físico)
2. **Distancia máxima:** ~10 metros en espacio abierto, ~3-5 metros con paredes
3. **Enlace BLE (pairing):** Puede aparecer en iOS y es opcional; el TPV lo acepta automáticamente
4. **Background mode:** Esta app simple NO funciona en background, debe estar abierta en foreground
5. **Batería:** BLE consume poca batería, pero si dejas la app abierta todo el día, puede drenar
6. **Seguridad:** El emparejamiento Bluetooth es seguro (encriptación AES), pero usa distancias cortas

---

## Siguiente Paso: Procesar Pago en PAX

Actualmente el PAX solo **recibe el monto** y lo muestra en logs:

```
💰 [BLE-Server] Payment amount: 1500 cents
💰 [SuperAdmin] Received payment request: 1500 cents
```

Para **procesar el pago real** (abrir PaymentScreen, procesar con Blumon, etc.), necesitas:

1. **Integrar callback** del BLE Payment Server con `PaymentViewModel`
2. **Navegar** automáticamente a `PaymentScreen` cuando llegue monto
3. **Enviar resultado** de vuelta al iPad (success/error)

**Próximo documento:** `docs/BLE_PAYMENT_INTEGRATION.md` (integración con flujo de pagos)

---

## Archivos del Proyecto Final

```
PAX Payment/
├── PAX PaymentApp.swift          (generado automáticamente - no tocar)
├── ContentView.swift              (UI - paso 5)
├── BluetoothService.swift         (BLE logic - paso 4)
├── Info.plist                     (permisos - paso 3)
├── Assets.xcassets/               (iconos/imágenes - generado)
└── Preview Content/               (generado - no tocar)
    └── Preview Assets.xcassets
```

**Total de líneas de código que escribiste:** ~450 líneas (copiadas de esta guía)

**Tiempo estimado:** 30-60 minutos (primera vez), 15 minutos (después)

---

**Última actualización:** Enero 2026
**Autor:** Avoqado Development Team
**Versión:** 1.0
