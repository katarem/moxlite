# Moxfield: investigación de la API interna

> Fecha: 2026-11-02. Moxfield **no tiene API pública documentada**. Todo lo de abajo es la API interna que usa su propio frontend (`api2.moxfield.com`), reconstruida a partir de librerías y proyectos de la comunidad. Puede cambiar sin aviso.

## 1. Lo que está confirmado (lectura, sin login)

Base: `https://api2.moxfield.com`

| Método | Ruta | Para qué |
|---|---|---|
| GET | `/v3/decks/all/{publicId}` | Mazo completo (versión actual). `publicId` es el trozo final de `moxfield.com/decks/{publicId}` |
| GET | `/v2/decks/all/{publicId}` | Versión antigua del mismo endpoint (todavía la usan varios scripts) |
| GET | `/v2/decks/search-sfw` | Búsqueda de mazos públicos (filtrada SFW) |
| GET | `/v2/decks/search` | Igual, sin filtro SFW |
| GET | `/v2/cards/search?q=` | Búsqueda de cartas en la BD de Moxfield |
| GET | `/v3/cards/named?q=&count=` | Búsqueda difusa por nombre |
| GET | `/v2/users/search-sfw?filter=` | Búsqueda de usuarios (`data[]` con `userName`, `displayName`, `badges`) |

### Parámetros de `/v2/decks/search(-sfw)`

`pageNumber`, `pageSize`, `fmt` (commander, standard…), `q`, `deckName`, `hubName`, `authorUserNames` (lista separada por comas), `commanderCardId`, `cardId`, `partnerCardId`, `companionCardId`, `sortType` (`colors|comments|created|deckBracket|format|likes|updated|views`), `sortDirection` (`ascending|descending`), `minBracket`, `maxBracket`.

**"Ver usuarios" = `authorUserNames=<usuario>`**: así se listan los mazos públicos de cualquier usuario.

### Forma del mazo (`/v3/decks/all/{id}`)

Campos clave: `id`, `publicId`, `name`, `format`, `description`, `visibility` (`public|unlisted|private|deleted`), `authors[]`, `createdByUser`, `createdAtUtc`, `lastUpdatedAtUtc`, `version`, `likeCount`, `viewCount`, `colorIdentity`, `bracket`, `tokens[]`, `originalDeck` (si es copia), `boards`.

`boards` es un mapa `tipoDeBoard → { count, cards: { <clave>: BoardCard } }`.

Tipos de board: `mainboard`, `sideboard`, `maybeboard`, `commanders`, `companions`, `partners`, `signatureSpells`, `attractions`, `stickers`, `contraptions`, `planes`, `schemes`, `tokens`.

`BoardCard`: `quantity`, `boardType`, `finish`, `isFoil`, `isProxy`, `isAlter`, `card` (con overrides opcionales de CMC/coste/identidad).

`card` trae **`scryfall_id`** (UUID) → enlace directo con Scryfall para imágenes. También `uniqueCardId`, `name`, `set`, `type_line`, `rarity`, precios por proveedor, etc.

### Cabeceras que se usan para pasar Cloudflare

```
User-Agent: <UA de navegador real>
Accept: application/json
Origin: https://www.moxfield.com
Referer: https://www.moxfield.com/
```

Funciona de forma intermitente: si Cloudflare endurece la regla, falla.

## 2. Sesión y escritura (lo que usa la app)

> Actualizado 2026-11-03. Rutas sacadas del código de la web de Moxfield por proyectos de la
> comunidad ([commander-lab](https://github.com/andrewgari/commander-lab/pull/56),
> [solring-extension](https://github.com/mashb1t/solring-extension),
> [moxfield-enhancement-suite](https://github.com/w-geissinger/moxfield-enhancement-suite),
> [is-in-deck](https://github.com/vankus4/is-in-deck), [moxtui](https://github.com/apaz-cli/moxtui)).
> PUT/DELETE de cantidades están confirmados por una extensión en uso; el resto no se ha probado
> desde la app contra una cuenta real.

### Login y renovación

- `POST /v2/account/token` exige un token de Cloudflare Turnstile: no se puede automatizar. Se inicia sesión en un WebView.
- `POST /v1/startup/authenticated` con `{"ignoreCookie":false,"isAppLogin":false}` y la cookie de sesión (HttpOnly) devuelve
  `{ "refresh": { "access_token", "refresh_token", "user_name", "user_id", "expiration", "expires_in_minutes", … } }`.
  Es lo que hace la web al abrirse: la app lo usa al terminar el login, al caducar el token y tras un 401.
- Peticiones autenticadas: `Authorization: Bearer <access_token>`.

### Escritura

Todas usan el **id interno** del mazo (`deck.id`, no el publicId) y llevan `X-Public-Deck-ID: <publicId>` y
`X-Deck-Version: <version>` (la del último GET; el servidor la incrementa en cada cambio). `cardId` es el id de
impresión de Moxfield (`card.id`), no el de Scryfall.

| Acción | Petición | Cuerpo |
|---|---|---|
| Crear mazo | `POST /v2/decks` | `{ name, format, visibility, description }` → mazo con `publicId` e `id` |
| Datos del mazo (nombre…) | `PUT /v2/decks/{id}` | `{ name, format, description, visibility }` |
| Importar texto | `POST /v3/decks/{id}/import` | `{ importText, replaceAll, usePrefPrintings }` |
| Añadir carta | `POST /v2/decks/{id}/cards/{board}` | `{ cardId, quantity, usePrefPrinting }` |
| Cantidad absoluta | `PUT /v2/decks/{id}/cards/{board}/{cardId}` | `{ quantity }` |
| Quitar carta | `DELETE /v2/decks/{id}/cards/{board}/{cardId}` | — |
| Mover de board | `POST /v4/decks/{id}/{from}/cards/{cardId}/move-to/{to}?quantity=n` | `{}` (la app cae a añadir + quitar si falla) |
| Cambiar edición | `PUT /v2/decks/{id}/cards/{cardId}/edition` | `{ newEditionCardId, finish, isAlter }` |
| Borrar mazo | `DELETE /v1/decks/{id}` | — (no usado) |
| Clonar | `POST /v2/decks/{publicId}/clone` | `{ name, includePrimer, includeTags }` (no usado: la copia se crea + importa) |

### Lectura con sesión y cartas

| Petición | Para qué |
|---|---|
| `GET /v2/decks/personal/search?pageNumber&pageSize&sortType&sortDirection&filter` | Mis mazos, incluidos privados |
| `GET /v2/cards/lookup?name=&set=&cn=` | Id de impresión de una carta (exacta) |
| `GET /v2/cards/search?q=` | Búsqueda con sintaxis de Scryfall |
| `GET /v3/cards/editions/{uniqueCardId}` | Todas las impresiones (no usado: se usa Scryfall) |

Imagen de una carta por id de Moxfield: `https://assets.moxfield.net/cards/card-{cardId}-art_crop.webp` (también `-normal.webp`).
Las búsquedas de mazos traen `mainCardId`, que sirve para la miniatura.

## 3. Aviso sobre las condiciones de Moxfield

Moxfield da acceso a su API caso por caso: hay que escribirles y te asignan un **User-Agent en lista blanca**. Usar la API interna sin eso, imitando un navegador, va contra lo que ellos esperan y puede suponer bloqueo de IP o de cuenta. Para uso personal con tu propia cuenta el riesgo es bajo, pero recomiendo:

- Pedirles un User-Agent (email a soporte explicando que es un cliente personal).
- Cachear agresivamente y no hacer polling.
- Respetar `429` y `Retry-After`.

## 4. Scryfall (imágenes y datos de carta)

- Imagen por id: `https://api.scryfall.com/cards/{scryfall_id}?format=image&version=normal` (redirige a `cards.scryfall.io`). Mejor: pedir el JSON una vez, guardar `image_uris.normal` / `art_crop` y cargar desde `cards.scryfall.io` (sin rate limit de la API).
- Cartas de doble cara: `card_faces[i].image_uris`.
- Offline: **Bulk Data** (`/bulk-data`, fichero "Oracle Cards" o "Default Cards") para tener toda la BD de cartas en local y buscar sin red.
- Scryfall exige `User-Agent` identificativo y `Accept`, y ~10 peticiones/s como máximo.

## 5. Arquitectura propuesta para la app

```
UI (Jetpack Compose)
   │
ViewModels
   │
Repositorios  ← única fuente de verdad: Room
   │      │
   │      └─ SyncEngine (WorkManager): cola de cambios pendientes
   │
 ┌─┴───────────────┬──────────────────────┐
MoxfieldApi      MoxfieldWebSession      ScryfallApi + bulk
(OkHttp/Ktor)    (WebView: login,         (imágenes con Coil,
                  cf_clearance, token)     caché en disco)
```

Claves del diseño offline:

- **Offline-first**: la UI siempre lee de Room. La red solo rellena/actualiza.
- **Cola de operaciones**: cada edición offline se guarda como operación (`AddCard`, `SetQuantity`, `MoveBoard`, `Rename`…) y se reenvía al volver la conexión.
- **Conflictos**: el mazo tiene `version` y `lastUpdatedAtUtc`. Si al sincronizar la versión remota cambió, se avisa y se elige (mantener local / remoto / fusionar).
- **Plan B si Cloudflare bloquea la API**: hacer las peticiones *desde dentro* del WebView (fetch en el origen moxfield.com) mediante un puente JS, que es lo que menos se distingue de un navegador real.
- **Exportar/importar** en texto plano (`1 Sol Ring (C21) 263`), que también sirve de copia de seguridad independiente de Moxfield.

## Fuentes

- [MarioMH8/moxfield-api](https://github.com/MarioMH8/moxfield-api) — endpoints de lectura y esquemas Zod
- [Raunak1571/moxfield-mcp](https://github.com/Raunak1571/moxfield-mcp) — cabeceras para Cloudflare
- [j4th/mtg-mcp-server](https://github.com/j4th/mtg-mcp-server) — búsqueda de usuarios `/v2/users/search-sfw`
- [cosi14/moxfield-api](https://github.com/cosi14/moxfield-api) — búsqueda de cartas y mazos por comandante
- [moxfield-public #143](https://github.com/moxfield/moxfield-public/issues/143) — login `/v2/account/token` y bloqueo por captcha
- [Moxfield feedback: Open API](https://moxfield.nolt.io/1431) — petición de API pública (aún no existe)
