# MagixPack

Il resource pack **UNICO** del server. Un client Minecraft applica un solo pacchetto risorse alla
volta: invece che ogni plugin (MagixFactions per lo shader della minimap/mappa e il logo del
tablist, MagixAuth per le schermate di accesso e il tastierino OTP...) ne spedisca uno proprio,
tutti registrano qui il loro contenuto e MagixPack li fonde in un unico zip, lo serve da solo via
un piccolo server HTTP integrato (nessun hosting esterno richiesto) e lo rende obbligatorio al
join.

Non ha comandi ne' permessi per i giocatori: e' infrastruttura.

## Come funziona

1. Ogni plugin contributore (MagixFactions, MagixAuth, e ogni plugin futuro che vuole aggiungere
   texture/font/suoni propri) si registra al proprio `onEnable`, passando una mappa
   `percorso-nello-zip -> bytes` gia' pronta (segnaposto propri gia' risolti, se ne aveva).
2. MagixPack aspetta che TUTTI i plugin abbiano finito il loro `onEnable` (evento
   `ServerLoadEvent`, che scatta una volta sola a fine avvio) prima di costruire davvero lo zip:
   cosi' l'ordine fra i vari contributori non conta.
3. Lo zip viene servito via un server HTTP integrato (`com.sun.net.httpserver.HttpServer`, gia'
   nella JDK) e inviato a ogni giocatore al join. Con `required: true` (default) chi non lo carica
   viene espulso — vedi `pack.PackListener`.
4. Un watchdog periodico verifica da solo che il pacchetto sia ancora scaricabile e riavvia il
   server HTTP se serve (rete di sicurezza contro un guasto reale gia' visto: dopo ore di uptime il
   server HTTP smetteva di rispondere).

## Registrarsi da un altro plugin

Nessuna dipendenza Maven: ogni plugin di questo repository si compila per conto suo (vedi
`.github/workflows/deploy-plugin.yml`), quindi due plugin non condividono un'interfaccia a
compile-time. Ci si parla per **riflessione** — stesso schema gia' usato per la chat live del sito
verso MagixFactions (vedi `MagixFactions.broadcastWebChat`) e per l'hook di Vault (`hook.Econ`):

```java
Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
if (mp != null && mp.isEnabled()) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("assets/miomod/textures/item/cosa.png", bytes);
    mp.getClass().getMethod("registerPack", Plugin.class, Map.class).invoke(mp, this, files);
}
```

Metodi pubblici esposti da `MagixPack` (tutti raggiungibili solo per riflessione):

| Metodo | Cosa fa |
| --- | --- |
| `registerPack(Plugin owner, Map<String,byte[]> files)` | Registra (o sostituisce) il contenuto di `owner`. |
| `unregisterPack(Plugin owner)` | Toglie il contenuto registrato da `owner` (da chiamare al suo `onDisable`). |
| `isPackAvailable()` | true se il pacchetto e' pronto e scaricabile. |
| `isPackRequired()` | true se il pacchetto e' obbligatorio (config `required`). |
| `sendPackTo(Player p)` | Manda il pacchetto al giocatore. |
| `packPublicUrl()` | URL pubblico dello zip (null finche' il servizio non e' partito). |

Il momento giusto per registrarsi e' il proprio `onEnable`, con `softdepend: [MagixPack]` nel
proprio `plugin.yml`: cosi' MagixPack e' gia' abilitato (il metodo esiste) quando il chiamante
prova a usarlo, anche se lo zip vero e proprio si costruisce solo dopo.

## Personalizzare a mano, senza scrivere un plugin (come Oraxen)

`plugins/MagixPack/overrides/` (creata vuota gia' al primo avvio) e' per chi vuole aggiungere o
sostituire un file del pacchetto senza codice: ogni file li' dentro entra nello zip allo stesso
percorso relativo a quella cartella —

```
plugins/MagixPack/overrides/assets/minecraft/textures/gui/container/inventory.png
```

diventa `assets/minecraft/textures/gui/container/inventory.png` nel pacchetto — e **vince sempre**
su qualunque contenuto gia' presente (file propri di MagixPack o registrato da un plugin). Basta un
file + `/mpack reload`, nessuna ricompilazione ne' redeploy: stesso principio di Oraxen, che tiene
le proprie risorse nella cartella dati del plugin, non nel jar.

Attenzione alle texture vanilla con un layout fisso (es. l'inventario e' 176x166 px con le caselle
in posizioni scritte nel CLIENT, non nell'immagine): un file con proporzioni diverse viene scalato
comunque a quella dimensione, e puo' venire illeggibile se non e' stato disegnato apposta per quel
formato.

## Config

- `public-host` / `port` — da dove i client scaricano lo zip (la porta va aperta sul firewall).
- `required` — pacchetto obbligatorio (default) o facoltativo.
- `send-delay-ticks` / `timeout-seconds` — tempistiche di invio ed espulsione.
- `watchdog-seconds` — ogni quanto si verifica che il pacchetto sia ancora scaricabile.
- `prompt` / `kick-messages.*` — testi mostrati al giocatore (colori `&` e `\n` per andare a capo).

## Comandi

- `/mpack reload` (permesso `magixpack.admin`) — rilegge config.yml e ricostruisce il pacchetto con
  le registrazioni gia' in mano. Non richiede di nuovo il contenuto agli altri plugin: se e'
  cambiato un LORO segnaposto, serve ricaricare (o riavviare) quel plugin.

## Permessi

- `magixpack.admin` (default op) — `/mpack reload`.
- `magixpack.bypass` (default op) — non viene mai espulso se il pacchetto obbligatorio non si
  carica (lo riceve comunque). Salvaguardia per non restare chiusi fuori dal proprio server.
