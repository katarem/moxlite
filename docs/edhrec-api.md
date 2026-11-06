# EDHREC: datos que usa el asistente

> Fecha: 2026-11-06. EDHREC **no tiene API pública documentada**: son los JSON que carga su propia web. Comprobado a mano contra el servicio real; puede cambiar sin aviso. El código está en `data/remote/edhrec/`.

## Páginas (GET, `https://json.edhrec.com/pages/…`)

Servidas desde S3 + CloudFront (`server: AmazonS3`, `cache-control: max-age=1620`). Una página que no existe responde **403** con un XML `AccessDenied`.

| Ruta | Contenido |
|---|---|
| `commanders/<slug>.json` | Página del comandante |
| `commanders/<slug>/<tema>.json` | Con un tema (`infect`, `tokens`…: los `slug` de `tag_counts`) |
| `commanders/<slug>/<bracket>.json` | Bracket: `exhibition` (1), `core` (2), `upgraded` (3), `optimized` (4), `cedh` (5) |
| `commanders/<slug>[/<tema>\|/<bracket>]/budget.json` / `expensive.json` | Presupuesto. **Tema + bracket no existe** (403) |
| `average-decks/<slug>[/…].json` | Mazo medio, mismas variantes |

### Slug

Cara frontal del nombre, sin tildes ni signos, en minúsculas y con guiones: `Lim-Dûl the Necromancer` → `lim-dul-the-necromancer`, `Y'shtola, Night's Blessed` → `yshtola-nights-blessed`, `Esika, God of the Tree // The Prismatic Bridge` → `esika-god-of-the-tree`.

Parejas: los dos slugs unidos con `-`. Partners en orden alfabético (`kraum-ludevics-opus-tymna-the-weaver`); background y doctor's companion al final (`wilson-refined-grizzly-raised-by-giants`, `the-fourteenth-doctor-rose-tyler`). En el orden equivocado responde `{"redirect": "/commanders/<slug-bueno>"}`.

### Campos usados

```jsonc
{
  "header": "Atraxa, Praetors' Voice (Commander)",
  "creature": 24, "instant": 10, "land": 36, "basic": 13, "nonbasic": 23,   // distribución media
  "tag_counts": [{ "slug": "infect", "value": "Infect", "count": 3581 }],   // temas
  "budget_counts": { "budget": 4618, "expensive": 4618 },
  "bracket_counts": { "1": 153, "2": 3161, "3": 4118, "4": 2587, "5": 190 },
  "panels": { "mana_curve": { "1": 8, "2": 18 } },
  "container": { "json_dict": {
    "card": { "name": "…", "num_decks": 46181, "color_identity": ["W","U","B","G"] },
    "cardlists": [{
      "header": "Top Cards", "tag": "topcards",   // newcards, highsynergycards, gamechangers, creatures, instants,
                                                  // sorceries, utilityartifacts, manaartifacts, enchantments,
                                                  // planeswalkers, utilitylands, lands…
      "cardviews": [{ "id": "<scryfall id>", "name": "Tekuthal, Inquiry Dominus",
                      "synergy": 0.27, "num_decks": 30927, "potential_decks": 46181 }]
    }]
  }},
  // Solo en average-decks:
  "archidekt": [{ "c": "c|m", "q": 1, "u": "<scryfall id>" }],
  "deck": { "commander": ["…"], "cards": { "Artifact": [["Sol Ring", 1]], "Land": [["Forest", 4]] } }
}
```

Inclusión = `num_decks / potential_decks`. La sinergia es una fracción (0,27 = +27 %).

## Recomendaciones (POST `https://edhrec.com/api/recs/`)

```json
{ "commanders": ["Atraxa, Praetors' Voice"], "cards": ["Sol Ring", "Arcane Signet"], "name": "" }
```

Respuesta: `inRecs` (hasta 100 cartas para añadir, con `name`, `primary_type`, `salt` y `score` 0–100) y `outRecs` (cartas del mazo, con `score` más alto cuanto menos habituales son con ese comandante). `cards` vacío también vale.
