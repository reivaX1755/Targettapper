# 🎯 TargetTapper

App Android que vigila la pantalla de una app que elijas, detecta dianas **rojas y blancas** y pulsa en su centro. Cada toque muestra ~0,6 s un marcador cian con las coordenadas (`TAP x,y`) para comprobar que funciona.

## Compilar con GitHub Actions
1. Sube el contenido de esta carpeta a la raíz de un repo (rama `main`).
2. **Actions → Build APK** (se lanza con cada push o con *Run workflow*).
3. Descarga el artefacto **TargetTapper-debug** → `app-debug.apk` e instálalo.

## Primer uso
1. **Accesibilidad**: botón → TargetTapper → activar. Si sale bloqueado ("Ajuste restringido", Android 13+): *Abrir info de la app* → ⋮ → **Permitir ajustes restringidos** y vuelve a activarlo.
2. **Notificaciones**: concede el permiso.
3. **Seleccionar app a vigilar**: elige el juego (solo se pulsa cuando está en primer plano).
4. Con todo en ✅ verás **"Todo OK"**. Pulsa **Iniciar** → Android pide permiso de captura de pantalla (último permiso), se abre el juego y empieza la detección.
5. Parar: botón *Detener* o la notificación.

## Piezas
| Archivo | Función |
|---|---|
| `CaptureService.kt` | MediaProjection + ImageReader, analiza cada frame |
| `TargetDetector.kt` | Rojo/blanco → blobs rojos circulares con centro blanco y anillo blanco (descarta corazones y puntuación; ignora el 10 % superior) |
| `TapAccessibilityService.kt` | `dispatchGesture` en el centro de cada diana; detecta la app en primer plano |
| `TapOverlay.kt` | Marcador cian + `TAP x,y` en el punto pulsado |

## Ajustes (`TargetDetector.kt` / `CaptureService.kt`)
- Umbrales de color: bloque `when` de clasificación.
- `TOP_IGNORE_FRACTION`, `MIN_CELLS`, `WHITE_MIN_FRACTION`.
- `COOLDOWN_MS`: tiempo antes de volver a pulsar en el mismo punto.

## Limitaciones
- Apps con `FLAG_SECURE` dan captura negra.
- `adb logcat -s TargetTapper` muestra las dianas detectadas.
