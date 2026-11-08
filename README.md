# Moxlite

Cliente Android personal para Moxfield: consultar y gestionar mazos **con y sin conexión**, con imágenes y datos de carta de Scryfall.

> Cliente no oficial. Usa la API interna de Moxfield (`api2.moxfield.com`), que no está documentada y puede cambiar. Detalles en [`docs/moxfield-api.md`](docs/moxfield-api.md).

## Qué hace

| Función | Estado |
|---|---|
| Iniciar sesión **una vez** (WebView) y quedarse con la sesión: se renueva sola | ✅ |
| Cuenta: ver estado de la sesión, cambios pendientes y cerrar sesión | ✅ |
| "Mis mazos" por pestañas (míos, guardados, recientes) con buscador, filtros y orden | ✅ |
| **Subir cambios a Moxfield**: crear, importar, añadir, cantidades, mover, quitar, renombrar, cambiar arte | ✅ (rutas de la web, sin probar en vivo, ver abajo) |
| Ver cualquier mazo público (por enlace, búsqueda o usuario), con miniatura en las listas | ✅ |
| Buscar mazos con filtros (formato, precon, colores, comandante, bracket, tipo) y orden (actualizados, nuevos, populares, vistos) | ✅ |
| Vistas del mazo: texto, lista, cuadrícula y carta completa; agrupar por tipo/coste/color y ordenar | ✅ |
| Añadir cartas con buscador con imágenes y en cualquier idioma ("rayo" o "lightning bolt") | ✅ |
| Elegir arte / edición de cada carta | ✅ |
| Compartir un mazo con un enlace `moxlite://` o un código que otra persona con Moxlite abre en su app | ✅ |
| Guardar mazos sin conexión (cartas + imágenes) y guardar los tuyos automáticamente | ✅ |
| Bloqueo de LaLiga a Cloudflare: aviso claro, cola que se sube sola después y proxy opcional | ✅ |
| Idioma de la app (español / inglés) e idioma de las cartas (imágenes y nombres) | ✅ |
| Copiar mazos ajenos, crear mazos, importar/exportar listas de texto | ✅ |
| Buscar usuarios, seguirlos (con su avatar) y buscar y filtrar en sus mazos públicos | ✅ |
| **Asistente EDHREC**: construir un mazo de Commander (o mejorar uno tuyo) con sugerencias, objetivos por función y autocompletado | ✅ (nuevo, ver abajo) |

## Cómo funciona

```
UI (Compose) → ViewModels → DeckRepository ──► Room (fuente de verdad, offline)
                                  │
                ┌─────────────────┼──────────────────┐
         MoxfieldService     CardRepository     Cola de cambios (pending_ops)
         (OkHttp + cookies     (Scryfall)        └─ SyncWorker → MoxfieldApiWriter
          del WebView + token)
```

- **Offline-first**: la UI solo lee de Room. Las ediciones se aplican en local al momento y se guardan como operaciones (`DeckOp`) en una cola que se sube sola.
- **Sesión**: el login de Moxfield exige captcha, así que se hace una vez en un WebView. Al entrar, la app pide un token a `POST /v1/startup/authenticated` con la cookie de sesión (lo mismo que hace la web al abrirse), lo guarda y vuelve sola. Cuando el token caduca o Moxfield responde 401, se renueva igual, sin volver a abrir el navegador. Solo si Moxfield cierra la sesión se pide entrar de nuevo.
- **Escritura**: `MoxfieldApiWriter` lee el mazo remoto antes de cada operación (versión `X-Deck-Version` e id de cada carta) y usa las mismas rutas que la web. Las operaciones son idempotentes (cantidades absolutas, mover lo que ya no está no hace nada), así que reintentar no duplica. Un mazo creado en el móvil se crea en Moxfield y se rellena con una importación de texto con las ediciones exactas.
- **Mazos ajenos**: no se editan; como en Moxfield, se copian.
- **Imágenes**: `cards.scryfall.io` a partir del `scryfall_id` (caché de 1,5 GB en `filesDir`, Android no la borra). Las miniaturas de las búsquedas usan `assets.moxfield.net` con el `mainCardId`. Con otro idioma de cartas, la misma impresión en ese idioma se busca en Scryfall por lotes y queda en caché.
- **Símbolos de maná**: costes y textos de reglas (`{2}{U}`, `{T}: añade {G}`) se dibujan como los iconos de las cartas, incluidos híbridos y pirexianos (`ui/common/ManaSymbols.kt`). Los trazados salen de la fuente [Mana](https://github.com/andrewgioia/mana) de Andrew Gioia (SIL OFL 1.1) y van dentro de la app, así que funcionan sin conexión. Los símbolos son propiedad de Wizards of the Coast.
- **Idiomas**: los textos están en Kotlin (`i18n/Es.kt`, `i18n/En.kt`), así que cambiar de idioma es instantáneo y una traducción que falte no compila.

### Fútbol y el bloqueo de LaLiga

Durante los partidos, las operadoras españolas bloquean IPs de Cloudflare por orden judicial, y Moxfield (web y API) está detrás de Cloudflare. **Ninguna app puede llegar a una IP bloqueada sin salir por otro sitio**, así que Moxlite hace lo posible para que no te enteres:

1. **Todo lo que abres queda en el móvil** y "Guardar mis mazos sin conexión" (activado por defecto) descarga tus mazos con imágenes en segundo plano.
2. **Los cambios no se pierden**: se aplican en local y se suben solos cuando Moxfield vuelve (WorkManager reintenta con espera creciente).
3. **El error dice lo que pasa** ("posible bloqueo de LaLiga") en vez de un fallo genérico.
4. **Enlaces y códigos compartidos que llevan el mazo dentro**: quien lo recibe lo ve aunque Moxfield no cargue.
5. **Proxy opcional** (HTTP o SOCKS, Ajustes → Proxy): si tienes un servidor fuera de España (un VPS, el router de otra casa…), todo el tráfico de la app (y el WebView) sale por ahí. Un proxy dentro de España no sirve.

### Asistente EDHREC

**Mis mazos → + → Asistente EDHREC** (mazo nuevo) o, en un mazo de Commander tuyo, **⋮ → Mejorar con EDHREC**.

1. **Comandante**: se busca en cualquier idioma. Si admite pareja (partner, "partner with", background, friends forever, doctor's companion) se ofrece añadirla.
2. **Enfoque**: tema de EDHREC (Infect, Tokens…), presupuesto (económico/caro) y bracket (1–5), con el número de mazos de cada opción. EDHREC combina tema + presupuesto y bracket + presupuesto, pero no tema + bracket.
3. **Empezar** con el mazo medio de EDHREC para ese enfoque, o solo con el comandante.
4. **Construir**:
   - **Sugerencias** "Para tu mazo": recomendaciones de EDHREC calculadas sobre el mazo tal como está (se recalculan al añadir o quitar), y las listas de EDHREC (Top, Nuevas, Criaturas, Tierras…) con su sinergia y % de inclusión.
   - **Objetivos por función**: tierras (media de EDHREC para el comandante), ramp 10, robo 10, interacción 8, barridos 3, ajustables. La función se deduce del texto de reglas (aproximado); tocar un contador filtra las sugerencias de esa función.
   - **Curva de maná** y coste medio.
   - **Autocompletar** hasta 100: primero lo que falta de cada función, luego las mejores sugerencias, y tierras (no básicas de EDHREC y básicas repartidas según los símbolos de maná).
   - **Sobran**: cartas del mazo que EDHREC ve poco con ese comandante.
5. **Crear mazo** (o **Guardar** en el mazo que se mejoraba): se guarda en el móvil y se sube a Moxfield como cualquier otro cambio.

Todo se guarda solo: se puede salir y seguir más tarde. Si el mazo que se estaba mejorando cambia entretanto, el asistente vuelve a empezar desde el mazo actual para no deshacer nada. Las páginas de EDHREC quedan en el móvil y valen sin conexión; EDHREC va por AWS, no por Cloudflare, así que el bloqueo de LaLiga no debería afectarle. Endpoints en [`docs/edhrec-api.md`](docs/edhrec-api.md).

### Compartir mazos

"Compartir enlace" genera `moxlite://deck/<código>`, donde el código es el mazo entero comprimido (no pasa por Moxfield ni por ninguna web):

- al tocarlo se abre directamente en Moxlite, aunque Moxfield esté bloqueado o el mazo sea privado;
- donde no se pueda tocar (WhatsApp no convierte en enlace los esquemas propios), se copia y se pega en **Mis mazos → + → Abrir enlace o código**: vale el enlace, el mensaje entero o solo el código, y si está en el portapapeles ya aparece puesto;
- **Copiar código** (menú del mazo) copia solo el código;
- si el mazo solo existe en el móvil de quien lo manda, Moxlite ofrece importarlo;
- los enlaces antiguos (`…#moxlite=<código>`) se siguen abriendo.

### Escritura: qué está verificado

Las rutas de escritura son las que usa la web de Moxfield (sacadas de su código por proyectos de la comunidad, ver [`docs/moxfield-api.md`](docs/moxfield-api.md)); PUT/DELETE de cantidades están confirmados por una extensión en uso. No se han podido probar contra una cuenta real desde aquí. Si una operación falla, queda en **Cuenta → Cambios pendientes** con el error, se puede descartar, y el **Registro de API** (Ajustes) enseña qué llamadas hace la web para corregir la ruta.

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

Además, cada 12 h (con conexión) se comprueba en segundo plano y, si hay versión nueva, llega una
**notificación**; al tocarla se abre la app con el aviso. En Android 13+ la app pide permiso de
notificaciones la primera vez. Se puede desactivar en **Ajustes → Avisar de versiones nuevas**.

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
| `data/repo/DeckMapperTest` | DTO → Room: cartas, carta destacada (también `mainCardId`), colores, estado local conservado |
| `data/repo/CardRepositoryTest` | Resolución de listas contra Scryfall, búsqueda multilingüe, impresiones, traducciones por lotes, con un servicio falso |
| `data/sync/DeckOpTest` | Serialización de la cola de cambios (también colas antiguas) y textos |
| `data/sync/WriteParsingTest` | Respuestas de búsqueda de cartas de Moxfield, nombres, errores de escritura |
| `data/session/AuthResultTest` | Respuesta de renovación de sesión (token, usuario, caducidad) |
| `domain/ShareLinkTest` | Enlaces para compartir: ida y vuelta, mazos locales, datos corruptos |
| `domain/DeckFiltersTest` | Filtros y orden de mazos (colores, comandante, bracket, tipo, precon), consulta a Moxfield, campos nuevos de la búsqueda |
| `ui/common/ManaSymbolsTest` | Símbolos de maná: separar costes y texto, colores, híbridos, pirexianos y símbolos desconocidos |
| `ui/deck/DeckLayoutTest` | Agrupar y ordenar cartas para las vistas, nombres e imágenes traducidos |
| `i18n/I18nTest` | Idioma de la app y textos en español/inglés |
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
│   ├── session/             # Sesión, renovación del token, cookies compartidas, puente WebView
│   ├── settings/            # Ajustes (idiomas, vista, proxy…)
│   └── sync/                # DeckOp, MoxfieldApiWriter, SyncWorker
├── domain/                  # Importar/exportar texto, enlaces para compartir
├── i18n/                    # Textos en español e inglés
└── ui/                      # Pantallas Compose
```

## Ideas pendientes

- Plan B contra Cloudflare: si OkHttp queda bloqueado por el reto (no por la IP), hacer las peticiones con `fetch` dentro de un WebView oculto.
- Bulk data de Scryfall para buscar cartas sin conexión.
- Estadísticas (curva de maná, colores) y validación de legalidad por formato.
- Pedir a Moxfield un User-Agent en lista blanca.
