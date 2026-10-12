# Etiqueta Scanner V1 (Android NYX)

Proyecto Android Java. Cámara continua con CameraX, ML Kit Barcode Scanning y ML Kit Text Recognition (OCR latino), SQLite offline con HU UNIQUE.

## Compilar
Subir el contenido de esta carpeta a un repositorio GitHub, abrir Actions > Compilar Etiqueta Scanner V1 > Run workflow. Descargar el artefacto `EtiquetaScannerV1-APK` e instalar `app-debug.apk`. Requiere conexión a Internet durante la compilación para descargar dependencias, pero no durante el escaneo una vez instalado el modelo empaquetado.

## Flujo
Al detectar HU nueva (numérica que comienza con 5, o CFM), se limpian campos. OCR completa NP, dado, máquina, cantidad y fecha. Con seis campos completos se inserta en SQLite. HU UNIQUE impide duplicados. Se utiliza DATE, no INJ DATE, en etiqueta grande.

## Limitaciones y pruebas imprescindibles
- **Versión de prueba:** los patrones OCR son heurísticos; validar sobre etiquetas reales, especialmente posición de dado y cantidad.
- La HU debe verse en la misma imagen que los datos OCR; el sistema no asigna OCR sin HU.
- No se incluye exportación ni edición manual en V1. Historial muestra los últimos 30 registros.
- Para HUs numéricas diferentes del patrón `5` + 8 a 11 dígitos, adaptar `HU_NUM`.
- La fecha numérica se interpreta en formato MM/DD/YYYY antes de DD/MM/YYYY.
- Se conserva el proyecto base y la configuración de compilación, pero se excluyó el servicio de impresión del escáner V1.
- El código está preparado para compilar, pero no se ha validado una compilación APK ni probado en hardware NYX en este entorno.
