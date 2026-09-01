#!/usr/bin/env python3
"""
Estrae le icone degli item di Minecraft per l'editor dei menu del gestionale.

    python3 icone-item.py [versione]        (senza argomento: l'ultima release)

Cosa fa, in fila:
  1. chiede a Mojang l'elenco delle versioni e scarica il jar del CLIENT (quello del server
     non contiene le texture: le texture sono roba che disegna il client);
  2. ne tira fuori le immagini degli item e dei blocchi;
  3. le rinomina come si chiamano gli item nel gioco (DIAMOND_SWORD.png) e le mette in
     public/assets/img/item/ del sito;
  4. scrive un elenco.json con quelle trovate, cosi' l'editor sa quali icone esistono senza
     provare a caricarne millecinquecento a vuoto.

Perche' file separati e non un atlante unico: comporre un atlante vorrebbe dire una libreria
grafica in piu' sul server, e per farci cosa — il catalogo dell'editor ne mostra al massimo
centoventi per volta, e il browser le tiene in cache. I file sono minuscoli (16x16 png).

Il jar scaricato viene buttato alla fine: serve solo il suo contenuto.

NOTA sul pacchetto risorse del server: i modelli custom (item_model / custom_model_data) NON
sono qui dentro. Il pacchetto lo genera MagixFactions a runtime e lo serve lui: quando ci sara'
una cartella stabile da cui pescarlo, basta aggiungere un secondo giro di estrazione sotto,
perche' la struttura e' la stessa (assets/<spazio>/textures/item/*.png).
"""
import io
import json
import os
import shutil
import sys
import time
import urllib.request
import zipfile

MANIFESTO = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
DESTINAZIONE = '/var/www/magicadventure/public/assets/img/item'

# Le cartelle di texture da guardare, in ordine di preferenza: l'icona di un item vale piu'
# della faccia del blocco corrispondente (DIAMOND ha una sua icona; STONE no, e allora si
# usa la faccia del blocco, che nel gioco e' comunque quello che si vede).
CARTELLE = ['item', 'block']


def scarica(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def versione_scelta(chiesta):
    manifesto = json.loads(scarica(MANIFESTO))
    if not chiesta:
        chiesta = manifesto['latest']['release']
    for v in manifesto['versions']:
        if v['id'] == chiesta:
            return v['id'], v['url']
    raise SystemExit('Versione "%s" non trovata nel manifesto di Mojang.' % chiesta)


# Da dove leggere l'elenco degli item che servono davvero (lo pubblica il plugin).
CATALOGO = '/home/ubuntu/magicadventure/plugins/MagixMenus/menus.json'

# I pezzi di blocco non hanno una texture propria: nel gioco riusano quella del blocco da cui
# sono fatti. Una scala di acacia e' fatta di assi di acacia, e mostrare quelle e' molto meglio
# che mostrare un quadrato col nome scritto sopra.
SUFFISSI = ['_STAIRS', '_SLAB', '_WALL', '_FENCE_GATE', '_FENCE', '_BUTTON', '_PRESSURE_PLATE',
            '_TRAPDOOR', '_DOOR', '_SIGN', '_HANGING_SIGN', '_WALL_SIGN', '_CARPET',
            # I vetri-lastra: la loro texture "_pane_top" e' la striscia sottile del bordo, non
            # quello che si vede nell'inventario. L'icona giusta e' il vetro intero, quindi il
            # nome base va provato PRIMA che entrino in gioco i ripieghi _SIDE/_TOP piu' sotto.
            '_PANE']


def candidati(nome):
    """I nomi di texture da provare per un item che non ne ha una sua."""
    fuori = []
    for s in SUFFISSI:
        if nome.endswith(s) and len(nome) > len(s):
            base = nome[:-len(s)]
            fuori.append(base)
            fuori.append(base + '_PLANKS')     # le scale di legno sono fatte di assi
            fuori.append(base + '_BLOCK')
            break
    if nome.endswith('_WOOD'):
        fuori.append(nome[:-5] + '_LOG')
    if nome.startswith('POTTED_'):
        fuori.append(nome[7:])
    if nome.startswith('WALL_'):
        fuori.append(nome[5:])
    if nome.endswith('_CAKE') or nome.startswith('CANDLE'):
        fuori.append('CAKE')

    # I blocchi con le facce diverse (un barile, il basalto) non hanno una texture "intera":
    # ne hanno una per lato. Il fianco e' quello che si riconosce meglio.
    fuori += [nome + '_SIDE', nome + '_FRONT', nome + '_TOP', nome + '_SIDE0']

    # Gli item animati (orologio, bussola) sono una serie numerata: il primo fotogramma basta.
    fuori += [nome + '_00', nome + '_0']

    # Ultimo tentativo: la prima parola. INFESTED_STONE -> STONE
    if '_' in nome:
        fuori.append(nome.split('_', 1)[1])
    return fuori


def solo_varianti():
    """Ricalcola solo le varianti sulle texture gia' estratte, senza toccare la rete.

    Serve quando si aggiustano le regole in SUFFISSI/candidati(): il jar e' gia' stato aperto
    una volta, e riscaricare quaranta megabyte per copiare qualche file non ha senso. Lo usa
    anche chi ha visto la connessione cadere a meta' download.
    """
    trovate = {}
    for nome in os.listdir(DESTINAZIONE):
        if nome.endswith('.png'):
            trovate[nome[:-4]] = True
    print('Texture gia\' presenti:', len(trovate))
    return trovate


def main():
    chiesta = sys.argv[1] if len(sys.argv) > 1 else ''
    if chiesta == '--solo-varianti':
        trovate = solo_varianti()
        finisci(trovate, 'gia-estratte')
        return

    versione, indirizzo = versione_scelta(chiesta)
    print('Versione:', versione)

    dettagli = json.loads(scarica(indirizzo))
    url_client = dettagli['downloads']['client']['url']
    peso = dettagli['downloads']['client']['size']
    # Il jar resta in /tmp fra un giro e l'altro: rieseguire lo script per aggiustare le regole
    # non deve costare quaranta megabyte ogni volta.
    cache = '/tmp/minecraft-client-%s.jar' % versione
    if os.path.exists(cache) and os.path.getsize(cache) == peso:
        print('Uso il client gia\' scaricato (%s).' % cache)
        with open(cache, 'rb') as f:
            jar = io.BytesIO(f.read())
    else:
        print('Scarico il client (%.1f MB)…' % (peso / 1048576.0))
        dati = scarica(url_client)
        with open(cache, 'wb') as f:
            f.write(dati)
        jar = io.BytesIO(dati)

    os.makedirs(DESTINAZIONE, exist_ok=True)
    trovate = {}
    with zipfile.ZipFile(jar) as z:
        nomi = z.namelist()
        for cartella in CARTELLE:
            prefisso = 'assets/minecraft/textures/%s/' % cartella
            for n in nomi:
                if not n.startswith(prefisso) or not n.endswith('.png'):
                    continue
                # Solo le texture "piatte": le sotto-cartelle sono animazioni e pezzi di
                # modelli, che come icona non dicono niente.
                resto = n[len(prefisso):]
                if '/' in resto:
                    continue
                nome = resto[:-4].upper()
                if nome in trovate:
                    continue     # gia' presa da "item": ha la precedenza
                dati = z.read(n)
                # Le texture animate (fuoco, acqua) sono strisce verticali: si tiene solo il
                # primo fotogramma? Serve una libreria per tagliarle, e sono poche: si saltano.
                if len(dati) > 20000:
                    continue
                with open(os.path.join(DESTINAZIONE, nome + '.png'), 'wb') as f:
                    f.write(dati)
                trovate[nome] = True

    finisci(trovate, versione)


def finisci(trovate, versione):
    print('Texture disponibili:', len(trovate))

    # --- le varianti: scale, lastre, staccionate e compagnia ---
    aggiunte = 0
    if os.path.exists(CATALOGO):
        catalogo = json.load(open(CATALOGO))['catalogo']['item']
        for nome in catalogo:
            if nome in trovate:
                continue
            for c in candidati(nome):
                if c in trovate:
                    shutil.copyfile(os.path.join(DESTINAZIONE, c + '.png'),
                                    os.path.join(DESTINAZIONE, nome + '.png'))
                    trovate[nome] = True
                    aggiunte += 1
                    break
        senza = [n for n in catalogo if n not in trovate]
        print('Varianti risolte con la texture del blocco base:', aggiunte)
        print('Item del gioco coperti: %d su %d (%.0f%%)'
              % (len(catalogo) - len(senza), len(catalogo),
                 100.0 * (len(catalogo) - len(senza)) / len(catalogo)))
        if senza:
            print('Restano senza icona (compariranno come piastrella col nome):', len(senza))
            print('  ', ', '.join(senza[:12]) + ('...' if len(senza) > 12 else ''))
    else:
        print('Non trovo %s: salto le varianti (il plugin non e\' mai partito?).' % CATALOGO)

    elenco = sorted(trovate)
    # "generato": il momento di questa estrazione. Il sito lo appende in coda alle URL delle
    # icone (?v=...): senza, quando una texture cambia (un vetro che prima aveva quella
    # sbagliata) il browser resterebbe attaccato alla vecchia, che tiene in cache.
    with open(os.path.join(DESTINAZIONE, 'elenco.json'), 'w') as f:
        json.dump({'versione': versione, 'generato': int(time.time()), 'icone': elenco},
                  f, separators=(',', ':'))

    for nome in os.listdir(DESTINAZIONE):
        percorso = os.path.join(DESTINAZIONE, nome)
        os.chmod(percorso, 0o644)
        shutil.chown(percorso, 'www-data', 'www-data')
    shutil.chown(DESTINAZIONE, 'www-data', 'www-data')

    print('Icone in elenco:', len(elenco))
    print('In:', DESTINAZIONE)


if __name__ == '__main__':
    main()
