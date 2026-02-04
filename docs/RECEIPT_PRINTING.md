# Receipt Printing (TPV)

## Overview

The TPV prints a fiscal-style receipt for each payment. The printed layout is optimized for PAX thermal printers and mirrors common POS receipts in Mexico.

## Header

- Logo: Venue logo URL when available, otherwise the Avoqado logo.
- Names:
  - Legal name (razon social) if present.
  - Trade name (venue name) if different.
- RFC.
- "LUGAR DE EXPEDICION" with address line (address + city/state/zip).

## Transaction Info

- FOLIO (order number when available).
- FECHA (date + time).
- CAJERO (staff name).

## Items Table

- Printed for **order payments** (always). Columns:
  - CANT
  - DESCRIPCION
  - IMPORTE (line total)
- Fast payments (no order) do not include items.

## Totals

- Subtotal (only when tip or discount exists).
- Propina (if present).
- "DESC. C/IMP." aligned to the right.
- "TOTAL" aligned to the right.
- "SON: {LETRAS} PESOS xx/100 M.N" line below totals.

## QR / Digital Receipt

- QR uses the existing digital receipt URL.
- Text: "Escanea para recibo digital".

## Footer

- "Gracias por su compra".
- Build tag: "AVOQADO TPV vX.X.X".

## Kiosk Receipt

Kiosk receipts reuse the header and footer (logo + venue info + version), but keep a simplified body (order number, totals, QR).

## Refunds

Refund receipts show "TOTAL REEMBOLSO" and do not print the IVA breakdown or the "SON" line.
