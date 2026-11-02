# Moxlite

Cliente Android personal para Moxfield: consultar y gestionar mazos **con y sin conexión**, con imágenes y datos de carta de Scryfall.

> Cliente no oficial. Usa la API interna de Moxfield (`api2.moxfield.com`), que no está documentada y puede cambiar. Detalles en [`docs/moxfield-api.md`](docs/moxfield-api.md).

## Qué hace (v0.1)

| Función | Estado |
|---|---|
| Ver cualquier mazo público (por enlace, búsqueda o usuario) | ✅ |
| Buscar mazos (texto, formato, orden) con paginación | ✅ |
| Buscar usuarios, seguirlos y ver sus mazos públicos | ✅ |
| "Mis mazos": tus mazos públicos + los guardados en el móvil | ✅ |
| Guardar mazo sin conexión (cartas + imágenes en disco) | ✅ |
| Actualización automática de mazos guardados cada 12 h | ✅ |
| Editar en local: cantidades, mover entre boards, añadir cartas (Scryfall), renombrar | ✅ |
| Copiar cualquier mazo / crear mazo nuevo / importar lista de texto | ✅ (en local) |
| Exportar como texto (compartir) | ✅ |
| Iniciar sesión (WebView) y usar mazos privados | ✅ lectura |
| Abrir enlaces `moxfield.com/decks/...` y textos compartidos en la app | ✅ |
| **Subir cambios a Moxfield** | ⏳ falta capturar endpoints (cola preparada) |

## Cómo funciona

```
UI (Compose) → ViewModels → DeckRepository ──► Room (fuente de verdad, offline)
                                  │
                ┌─────────────────┼─────────────────┐
         MoxfieldService     CardRepository     Cola de cambios (pending_ops)
         (OkHttp + cookies     (Scryfall)        └─ SyncWorker / MoxfieldWriter
          del WebView)
```

- **Offline-first**: la UI solo lee de Room. Las ediciones se aplican en local al momento y se guardan como operaciones (`DeckOp`) en una cola.
- **Sin pisar cambios**: al actualizar un mazo con cambios locales no se sobrescribe; si Moxfield cambió entretanto, se marca conflicto.
- **Sesión**: el login de Moxfield exige captcha, así que se inicia sesión en un WebView y se captura el token Bearer que manda la propia web. OkHttp comparte cookies con el WebView (incluida `cf_clearance` de Cloudflare) y usa su mismo User-Agent.
- **Imágenes**: URL directa a `cards.scryfall.io` a partir del `scryfall_id` que da Moxfield, caché de 1,5 GB en `filesDir` (Android no la borra).

## Siguiente paso: escritura en Moxfield

1. Instala la app, inicia sesión y ve a **Ajustes → Moxfield web**.
2. Haz una vez cada acción: crear mazo, añadir carta, cambiar cantidad, mover a banquillo, quitar carta, renombrar, copiar un mazo y borrarlo.
3. **Ajustes → Registro de API → Compartir**. El registro no incluye tokens ni contraseñas.
4. Con eso se implementa `MoxfieldWriter` (ver `data/sync/DeckOp.kt`) y se cambia `PendingDiscoveryWriter` en `AppContainer`. La cola que ya tengas pendiente se subirá sola.

## Compilar

- **Android Studio**: abrir la carpeta. Usa Gradle 8.11.1 (definido en `gradle/wrapper/gradle-wrapper.properties`).
- **Terminal**: con Gradle instalado, `gradle wrapper` una vez y luego `./gradlew assembleDebug`.
- **GitHub Actions**: `.github/workflows/android.yml` pasa los tests y sube el APK debug y release como artefacto.

Requisitos: JDK 17, Android SDK 35. minSdk 26.

## Estructura

```
app/src/main/java/es/moxlite/
├── AppContainer.kt          # Dependencias, clientes HTTP, Coil
├── data/
│   ├── local/               # Room: mazos, cartas, cola, usuarios seguidos
│   ├── remote/moxfield/     # API de Moxfield (DTOs tolerantes)
│   ├── remote/scryfall/     # API de Scryfall + URLs de imagen
│   ├── repo/                # DeckRepository, CardRepository, mapeos
│   ├── session/             # Sesión, cookies compartidas, puente WebView, registro de API
│   └── sync/                # DeckOp, MoxfieldWriter, SyncWorker
├── domain/DeckText.kt       # Importar/exportar texto
└── ui/                      # Pantallas Compose
```

## Ideas pendientes

- Plan B contra Cloudflare: si OkHttp queda bloqueado, hacer las peticiones con `fetch` dentro de un WebView oculto.
- Bulk data de Scryfall para buscar cartas sin conexión.
- Estadísticas (curva de maná, colores) y validación de legalidad por formato.
- Pedir a Moxfield un User-Agent en lista blanca.
