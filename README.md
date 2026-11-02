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

## Descargar

Cada merge en `main` publica automáticamente una versión nueva en
[Releases](../../releases). El enlace a la última versión es siempre el mismo:

**https://github.com/katarem/moxlite/releases/latest/download/moxlite.apk**

La versión es `<moxlite.version>.<nº de commits>` (p. ej. `0.1.42`); la base está en `gradle.properties`.

### Actualizaciones desde la app

Al abrir la app (como mucho cada 6 h) se consulta la última Release de GitHub. Si hay una versión
más nueva, aparece un aviso con las novedades y la opción de **Actualizar**: la app descarga el APK
y abre el instalador de Android, que pide confirmación (y la primera vez, permiso para instalar
desde Moxlite). También se puede buscar a mano en **Ajustes → Versión**. "Omitir" deja de avisar
de esa versión concreta.

## Compilar

- **Android Studio**: abrir la carpeta. Usa Gradle 8.11.1 (wrapper incluido).
- **Terminal**: `./gradlew assembleDebug`. Tests: `./gradlew testDebugUnitTest`.
- **GitHub Actions** (`.github/workflows/android.yml`):
  - En cada PR: tests unitarios + APK debug y release como artefacto.
  - En cada push a `main`: lo mismo y, si pasa, crea la Release `vX.Y.N` con `moxlite.apk`.

Requisitos: JDK 17, Android SDK 35. minSdk 26.

## Tests

Tests unitarios JVM en `app/src/test` (JUnit 4 + `kotlinx-coroutines-test`), sin emulador:

| Fichero | Qué cubre |
|---|---|
| `DeckTextTest`, `domain/DeckTextFormatTest` | Importar/exportar listas de texto (cabeceras, ediciones, foil, MTGO, ida y vuelta) |
| `MoxfieldDtoTest`, `data/remote/*` | Parseo tolerante de la API de Moxfield y Scryfall, parámetros de búsqueda, URLs de imagen, detección de Cloudflare, mensajes de error |
| `data/repo/DeckMapperTest` | DTO → Room: cartas, carta destacada, colores, estado local conservado |
| `data/repo/CardRepositoryTest` | Resolución de listas contra Scryfall (lotes de 75, impresión vs. nombre, cartas partidas) con un servicio falso |
| `data/sync/DeckOpTest` | Serialización de la cola de cambios y textos |
| `data/session/ApiTrafficLogTest` | Registro de API: filtrado, redacción de secretos, duplicados, límite |
| `data/local/EntitiesTest` | Tipo principal de carta y estado de carga |
| `data/update/UpdateCheckerTest` | Comparación de versiones, elección del APK y notas de la Release |

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
