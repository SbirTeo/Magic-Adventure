# Riepilogo sessione — Item "Mappa Fazioni" (MagixFactions)

**Data:** 2026-07-05
**Plugin:** MagixFactions (server MAGICADVENTURE, Paper 26.1.2 su VPS OVH `141.94.123.249`, screen `mc`)
**Versioni rilasciate in questa sessione:** `0.10.0` → `0.10.1` → `0.10.2`

---

## 1. Obiettivo

Sostituire il vecchio `/f map` (mappa disegnata in **chat**) con un **item mappa dinamico** in gioco
(`filled_map`): tenendolo in mano il giocatore vede, sopra il terreno reale, i **territori delle fazioni**
colorati in base alla propria relazione (tua / alleata / nemica / altrui), stile mappa web Dynmap.

Scelta confermata dall'utente: **"Item mappa in gioco"** (non una dynmap web).

---

## 2. Come funziona la Mappa Fazioni

- `/f map` (aperto a **tutti**) consegna un item `FILLED_MAP` chiamato *Mappa Fazioni*.
- Tenendolo in mano, un **renderer custom** disegna sopra il terreno:
  - ogni territorio = **area a tinta piena** (verde = tua, viola = alleata, rosso = nemica, giallo = altrui);
  - **bordo più scuro** che fa da cornice e separa territori adiacenti;
  - la tinta è semitrasparente sul terreno (fusione software pixel per pixel).
- È **dinamica**: si ricentra sul giocatore mentre cammina; la freccia bianca è il giocatore.
- I chunk liberi restano terreno normale; le zone non esplorate sono grigie (comportamento vanilla della mappa).

### Config rilevante (`config.yml → map`)
```yaml
map:
  item:
    scale: "CLOSE"     # CLOSEST | CLOSE | NORMAL | FAR | FARTHEST (più vicino = chunk più grandi/leggibili)
    fill-alpha: 78     # opacità % della tinta sul terreno (0 = invisibile, 100 = colore pieno)
  symbols:
    own-color: "&a"    # tua fazione
    ally-color: "&d"   # alleata
    enemy-color: "&c"  # nemica
    other-color: "&e"  # altrui (se non hai fazione)
```
Messaggi (`messages.yml → map`): `item-name`, `given`.

---

## 3. Changelog dettagliato

### v0.10.0 — Introduzione dell'item mappa
- Nuovo `map/FactionMapRenderer.java` (renderer per-giocatore: overlay territori su terreno).
- `FCommand.map()` ora crea una `MapView`, aggancia il renderer e dà l'item al giocatore.
- **Rimosso** tutto ciò che serviva solo alla vecchia mappa in chat:
  - comando `/mf admin setmap` + messaggi `admin.setmap-*` + riga in `help-admin`;
  - helper morti in `FCommand` (`mapRows`, `mapSym`, `mapColor`, `relColorRaw`);
  - chiavi config `map.default-rows`, `map.symbols.{you,home,neutral}`, messaggi `map.header`/`map.legend*`.
- Colonna DB `players.map_rows` lasciata **dormiente** (non rimossa per evitare rebuild tabella SQLite).
- README + tutorial HTML aggiornati (l'item sostituisce la mappa in chat).

### v0.10.1 — Leggibilità
Primo test in gioco: la mappa sembrava un "groviglio di contorni" (interni troppo trasparenti sull'acqua,
bordi dello stesso colore pieno che dominavano). Correzioni:
- interno quasi pieno: `fill-alpha` default **40 → 78**;
- bordo = **tonalità più scura** del colore fazione (`darken(fac, 48)`), fa da cornice;
- zoom default `NORMAL → CLOSE` (chunk più grandi e leggibili).

### v0.10.2 — Persistenza del renderer (fix importante)
**Problema:** Bukkit **non salva** i renderer aggiunti a runtime. Dopo un riavvio, l'item mappa esistente
perde il `FactionMapRenderer` e mostra solo il terreno (nessun overlay).
Questo ha causato il falso allarme "non vedo il mio claim": l'utente teneva una mappa **precedente al riavvio**
(il claim era regolarmente nel DB).

**Soluzione:**
- Nuovo `map/MapService.java`: costruisce l'item, lo **marchia** con un dato persistente
  (`NamespacedKey(plugin,"faction_map")`) e riaggancia il renderer solo se assente (`ensureRenderer`, idempotente).
- Nuovo `listener/MapListener.java`: riaggancia il renderer quando il giocatore tiene in mano una Mappa Fazioni
  — su **login** (con 1 tick di ritardo), **cambio slot hotbar**, **scambio mani**, **cambio mondo**.
- In `onEnable`: `MapService` + `MapListener` registrati; riaggancio anche per i giocatori già online (dopo `/reload`).

Da adesso le mappe **sopravvivono ai riavvii** senza rifare `/f map`.
> Nota: gli item mappa creati **prima** di 0.10.2 non hanno il tag → non si risvegliano. Serve un ultimo `/f map`
> per ottenere una mappa taggata e persistente.

---

## 4. File creati / modificati

**Creati**
- `src/main/java/com/teolo/magixfactions/map/FactionMapRenderer.java`
- `src/main/java/com/teolo/magixfactions/map/MapService.java`
- `src/main/java/com/teolo/magixfactions/listener/MapListener.java`

**Modificati**
- `command/FCommand.java` — `map()` ora usa `MapService`; costruttore con nuovo parametro `MapService`; rimozione codice morto e `/mf admin setmap`.
- `MagixFactions.java` — crea `MapService`, registra `MapListener`, passa il servizio a `FCommand`.
- `resources/config.yml` — sezione `map.item` (scale, fill-alpha); rimosse chiavi vecchie.
- `resources/messages.yml` — `map.item-name`, `map.given`; rimossi `admin.setmap-*`, `map.header/legend*`.
- `README.md` + `docs/build_tutorial.py` (→ `docs/tutorial.html`) — sezione mappa riscritta.
- `pom.xml` — versione.

---

## 5. Come funziona il rendering (tecnico)

`FactionMapRenderer.render(view, canvas, player)`:
1. si ricentra sul giocatore quando si sposta ≥ 8 blocchi (mappa dinamica); throttle ~700 ms;
2. `scale = 1 << view.getScale().getValue()` = blocchi per pixel;
3. **Pass 1** — per ogni pixel (128×128) calcola il chunk e legge il proprietario da
   `ClaimManager.owner(world, chunkX, chunkZ)` (cache in memoria, aggiornata sui claim);
4. **Pass 2** — pixel di bordo → colore scuro (cornice); pixel interni → blend del colore fazione sul terreno.

Il colore dipende dalla relazione di chi guarda (`FactionManager.effectiveRelation`) e viene letto da
`map.symbols.*-color` (codici `&`, convertiti in RGB Minecraft).

---

## 6. Deploy (workflow usato)

1. `mvn -DskipTests package` → `target/MagixFactions-<ver>.jar`
2. Copia jar nel mirror locale `magicadventure/server/plugins/` (rimuovi versione vecchia)
3. `scp` jar (+ `config.yml`/`messages.yml` se cambiati) → `ubuntu@141.94.123.249:/home/ubuntu/magicadventure/plugins/…`
4. Rimozione jar vecchio sul VPS
5. Riavvio: `screen -S mc -X stuff "stop\r"` → `start.sh` riavvia in loop → attesa di `Done (`

Ultimo stato: **0.10.2 live, avvio pulito** (`Done (17.076s)!`, 2 fazioni caricate, nessun errore).

---

## 7. Dati di test attuali (DB)

- Fazioni: `Beta` (id 2), `gamers` (id 3).
- Membri: `DorinoJ` → gamers (id 3).
- Claim di gamers: chunk `(32,-2)` e `(35,-15)` nel mondo `world` → **NON adiacenti** (~13 chunk di distanza in Z),
  quindi sulla mappa appaiono come **due quadrati verdi separati**. (16×16 blocchi = **1 chunk** = 1 quadratino.)

---

## 8. Da fare / verificare

- **Test in gioco:** rifare `/f map` (una volta, per la mappa taggata), tenerla in mano e verificare la resa
  grafica delle aree piene con bordo scuro; regolare eventualmente `fill-alpha` / `scale`.
- **Nota:** il sorgente di `MapService.create(...)` è in evoluzione verso una **scala per-giocatore**
  (firma `create(Player, MapView.Scale, String)`): se si adotta, va aggiornata di conseguenza la chiamata in
  `FCommand.map()` e ripristinato un modo per impostare lo zoom per giocatore.
- Possibile pulizia futura: rimuovere la colonna dormiente `players.map_rows` e i metodi `PowerManager.getMapRows/setMapRows`.
