# -*- coding: utf-8 -*-
"""
Controllo dei BUCHI fra config e documentazione, per tutti i plugin Magix.

Perche' esiste: la regola e' che ogni chiave del config sia spiegata (commento sopra la chiave, valori
accettati compresi) e che non restino chiavi morte o non documentate. Finche' il controllo era "mi
ricordo di guardare", i buchi ci sono arrivati lo stesso. Questo script li elenca in dieci secondi.

Uso:
    python plugins-src/controlla-config.py            # tutti i plugin
    python plugins-src/controlla-config.py MagixAuth  # uno solo

Cosa segnala, per ogni file .yml di configurazione:
  [1] chiave SENZA COMMENTO sopra                  -> nessuno sa cosa fa
  [2] chiave a stringa i cui ALTRI VALORI accettati compaiono nel codice ma non nel commento
  [3] chiave LETTA DAL CODICE ma assente dal file  -> il default e' invisibile a chi configura
  [4] chiave NEL FILE ma mai letta dal codice      -> residuo, o refuso nel nome
  [6] NUMERO SCRITTO A MANO in una guida che coincide con un valore del config
                                                   -> va messo il segnaposto, o la guida mentira'
  [7] MODALITA' (chiave che vale una parola fra piu' possibili) che il tutorial dei giocatori non
      racconta con un blocco {{se:chiave=valore}}  -> cambiando modalita' la guida resta a quella vecchia.
      Se la modalita' NON si vede in gioco, si scrive [solo staff] nel commento della chiave.

Esce con codice 1 se ha trovato qualcosa: si puo' agganciare a un hook o alla build.
"""
import os, re, sys

QUI = os.path.dirname(os.path.abspath(__file__))


def plugin_presenti():
    """
    I plugin da controllare, TROVATI da soli: ogni cartella qui dentro che ha un pom.xml e i suoi
    sorgenti. Niente elenco scritto a mano — un plugin Magix nuovo entra nel controllo dal momento in
    cui esiste, senza che nessuno debba ricordarsi di aggiungerlo qui.
    """
    fuori = []
    for nome in sorted(os.listdir(QUI)):
        cartella = os.path.join(QUI, nome)
        if os.path.isfile(os.path.join(cartella, "pom.xml")) \
                and os.path.isdir(os.path.join(cartella, "src", "main", "resources")):
            fuori.append(nome)
    return fuori


PLUGIN = plugin_presenti()
# Chiavi che si leggono da sole o non appartengono al plugin: non vanno segnalate.
IGNORA_NON_LETTE = re.compile(r"^(storage\.|mariadb\.|sqlite\.|ranks|leader|forbidden-words|messages)")


def chiavi_del_file(percorso):
    """Chiavi del .yml con: riga, percorso puntato, valore grezzo, e se hanno un commento sopra."""
    with open(percorso, encoding="utf-8") as f:
        righe = f.read().split("\n")
    fuori = []
    pila = []       # (indentazione, nome)
    commenti = {}   # percorso della sezione -> commento che ha sopra
    for i, riga in enumerate(righe):
        m = re.match(r"^(\s*)([A-Za-z0-9_\-]+):(.*)$", riga)
        if not m:
            continue
        ind, nome, resto = len(m.group(1)), m.group(2), m.group(3).strip()
        while pila and pila[-1][0] >= ind:
            pila.pop()
        percorso_chiave = ".".join([p[1] for p in pila] + [nome])
        antenati = [".".join([p[1] for p in pila][:k + 1]) for k in range(len(pila))]
        pila.append((ind, nome))
        # Commento nelle righe IMMEDIATAMENTE sopra, senza righe vuote in mezzo: una riga vuota
        # separa, e quel che sta piu' su parla d'altro. (Senza questa regola l'intestazione del file
        # veniva presa per il commento della prima chiave, e il buco non si vedeva.)
        commento, j = [], i - 1
        while j >= 0 and righe[j].strip().startswith("#"):
            commento.insert(0, righe[j].strip().lstrip("#").strip())
            j -= 1
        commento = " ".join(commento)
        # Vale anche il commento IN LINEA: "per: day   # hour | day | week" spiega la chiave benissimo.
        if "#" in resto:
            commento = (commento + " " + resto.split("#", 1)[1]).strip()
        if resto == "" or resto.startswith("#"):
            commenti[percorso_chiave] = commento   # e' una sezione: il commento copre cio' che contiene
            # La sezione entra comunque nell'elenco: il codice puo' leggerla intera
            # (getConfigurationSection("claims.cost")), e senza di questa non risulterebbe esistere.
            fuori.append({"riga": i + 1, "chiave": percorso_chiave, "valore": "",
                          "commento": commento, "sezione": True})
            continue
        if not commento:
            # Una chiave dentro una sezione gia' spiegata e' documentata: il commento sta sul blocco
            # (es. le stagioni, che sono quattro righe uguali sotto un solo cappello).
            for a in reversed(antenati):
                if commenti.get(a):
                    commento = commenti[a]
                    break
        fuori.append({"riga": i + 1, "chiave": percorso_chiave, "valore": resto,
                      "commento": commento, "sezione": False})
    return fuori


def codice_del_plugin(cartella):
    testo = []
    for radice, _, file in os.walk(os.path.join(cartella, "src", "main", "java")):
        for f in file:
            if f.endswith(".java"):
                with open(os.path.join(radice, f), encoding="utf-8", errors="ignore") as fh:
                    testo.append(fh.read())
    return "\n".join(testo)


# Testi che i giocatori e lo staff LEGGONO: il tutorial illustrato e le frasi scritte a mano nelle
# guide (sezione/intro/guasto/mai di GuidaStaff). E' qui che un numero copiato a mano fa danno.
TAG_HTML = re.compile(r"<[^>]+>", re.S)
STILE = re.compile(r"<style.*?</style>|<script.*?</script>", re.S)
NUMERO_CAPITOLO = re.compile(r'<span class="n">[^<]*</span>')
INDICE = re.compile(r'<div class="toc">.*?</div>', re.S)
# I riquadri che imitano lo schermo di gioco sono ESEMPI ("Membri: 3/5"): numeri inventati per
# far vedere com'e' fatta una schermata, non valori di config.
ESEMPI = re.compile(r'<div class="chat">.*?</div>', re.S)
# Anche i comandi di esempio hanno numeri loro ("/f help 3" e' la pagina dell'aiuto).
COMANDI = re.compile(r'<span class="cmd">.*?</span>', re.S)
NUMERO = re.compile(r"(?<![\w.&#])(\d+(?:[.,]\d+)?)(?![\w.])")


def testi_di_guida(cartella):
    """
    Il testo che i GIOCATORI leggono: il tutorial illustrato. E' l'unico posto dove restano frasi
    scritte a mano — la guida per lo staff non ha piu' numeri suoi (l'elenco delle impostazioni lo
    genera GuidaStaff dal config vivo, valore e commento compresi), quindi non ha senso frugarci.
    """
    tut = os.path.join(cartella, "docs", "build_tutorial.py")
    if not os.path.isfile(tut):
        return []
    with open(tut, encoding="utf-8") as f:
        html = f.read()
    html = STILE.sub(" ", html)
    html = NUMERO_CAPITOLO.sub(" ", html)   # "8" nel pallino del titolo non e' un valore di config
    html = INDICE.sub(" ", html)            # e nemmeno la numerazione dell'indice
    html = ESEMPI.sub(" ", html)            # ne' i numeri inventati delle schermate di esempio
    html = COMANDI.sub(" ", html)           # ne' quelli dentro ai comandi di esempio
    return [("docs/build_tutorial.py", TAG_HTML.sub(" ", html))]


def numeri_a_mano(cartella, chiavi):
    """
    [6] Un numero che compare in una guida e che e' ANCHE il valore di una chiave del config: quasi
    sempre e' stato copiato a mano, e alla prossima modifica del config la guida comincia a mentire.
    Si ignorano 0/1/2 e le percentuali fino a 2 cifre uguali per caso: fanno solo rumore.
    """
    valori = {}
    for c in chiavi:
        v = c["valore"].split("#")[0].strip().strip('"\'')
        if re.fullmatch(r"\d+(?:\.\d+)?", v or "") and float(v) >= 3:
            valori.setdefault(v.rstrip("0").rstrip(".") if "." in v else v, []).append(c["chiave"])
    problemi = []
    for nomefile, testo in testi_di_guida(cartella):
        # un numero gia' scritto come segnaposto non e' a mano: lo si toglie dal testo prima di guardare
        testo = re.sub(r"\{\{[^}]{1,80}\}\}", " ", testo)
        visti = set()
        for m in NUMERO.finditer(testo):
            n = m.group(1).replace(",", ".")
            n = n.rstrip("0").rstrip(".") if "." in n else n
            if n in valori and n not in visti:
                visti.add(n)
                problemi.append((nomefile, 0, "[6] numero scritto a mano (" + n + "), coincide con",
                                 ", ".join(valori[n][:3])))
    return problemi


def metodi_derivati(codice):
    """Il corpo dei metodi che costruiscono i testi derivati delle guide (quelli con .extra(...))."""
    fuori = []
    intestazione = re.compile(r"^    (?:private|public|protected)[^\n]*\{$", re.M)
    tagli = [m.start() for m in intestazione.finditer(codice)] + [len(codice)]
    for i in range(len(tagli) - 1):
        corpo = codice[tagli[i]:tagli[i + 1]]
        if ".extra(" in corpo:
            fuori.append(corpo)
    return "\n".join(fuori)


def modalita_non_raccontate(cartella, chiavi, codice):
    """
    [7] Il caso che i numeri non coprono: una chiave che vale una PAROLA fra piu' possibili (map.mode
    = chat | item) cambia quello che il giocatore vede, ma nessun numero cambia — quindi il controllo
    [6] non se ne accorge e il tutorial continua a raccontare la modalita' di ieri. E' successo davvero:
    map.mode messo su "chat" e il capitolo 8 che spiegava ancora la mappa-ITEM.

    La regola: se una modalita' si vede in gioco, il tutorial deve trattarla con un blocco
    {{se:chiave=valore}}. Se non si vede (storage, porte, roba di macchina), lo si dichiara scrivendo
    [solo staff] nel commento della chiave — che e' comunque documentazione utile a chi configura.
    """
    tut = os.path.join(cartella, "docs", "build_tutorial.py")
    if not os.path.isfile(tut):
        return []          # un plugin senza tutorial dei giocatori non ha niente da raccontare
    with open(tut, encoding="utf-8") as f:
        testo = f.read()
    problemi = []
    for c in chiavi:
        if c["sezione"]:
            continue
        valore = c["valore"].split("#")[0].strip().strip('"\'')
        if not re.fullmatch(r"[a-z][a-z0-9_\-]{1,24}", valore or "") or valore in ("true", "false"):
            continue
        if "[solo staff]" in c["commento"]:
            continue
        base = c["chiave"].split(".")[-1]
        vicino = re.findall(r'"' + re.escape(c["chiave"]) + r'"[^;]{0,200}', codice)
        vicino += re.findall(r'get\w+\(\s*"[^"]*' + re.escape(base) + r'"\)[^;]{0,200}', codice)
        alternativi = set()
        for pezzo in vicino:
            for lett in re.findall(r'(?:case\s+|equals(?:IgnoreCase)?\(\s*)"([a-z][a-z0-9_\-]{1,24})"', pezzo):
                if lett != valore and lett != c["chiave"] and not lett.endswith(base):
                    alternativi.add(lett)
        # Un altro modo LEGITTIMO di raccontarla: un testo DERIVATO costruito in Java (es. la frase
        # della perdita da offline, che cambia forma col periodo scelto). Vale SOLO se la chiave e'
        # letta DENTRO il metodo che costruisce quei testi: un primo tentativo guardava "li' attorno"
        # e bastava che la chiave comparisse in una riga vicina (map.mode compare nella tabella delle
        # impostazioni, poche righe sopra) per farla passare per raccontata. Provato: con la finestra
        # larga il controllo diceva "pulito" anche sul tutorial sbagliato di ieri.
        raccontata = ("{{se:" + c["chiave"] + "=") in testo or ('"' + c["chiave"] + '"') in metodi_derivati(codice)
        # Anche il RIPIEGO scritto nel codice e' un valore possibile: getString("map.mode", "item")
        # dice che esiste la modalita' "item" anche se nessun case/equals la nomina. Senza questo il
        # controllo restava muto proprio sul caso da cui e' nato (provato: diceva "pulito").
        for rip in re.findall(r'get\w+\(\s*"' + re.escape(c["chiave"]) + r'"\s*,\s*"([a-z][a-z0-9_\-]{1,24})"', codice):
            if rip != valore:
                alternativi.add(rip)
        if alternativi and not raccontata:
            problemi.append(("docs/build_tutorial.py", c["riga"],
                             "[7] modalita' non raccontata nel tutorial (" + "|".join(sorted(alternativi | {valore})) + ")",
                             c["chiave"]))
    return problemi


def controlla(nome):
    cartella = os.path.join(QUI, nome)
    risorse = os.path.join(cartella, "src", "main", "resources")
    if not os.path.isdir(risorse):
        return []
    codice = codice_del_plugin(cartella)
    lette = set(re.findall(r'get\w+\(\s*"([A-Za-z0-9_.\-]+)"', codice))
    problemi = []

    for f in sorted(os.listdir(risorse)):
        if not f.endswith(".yml") or f in ("plugin.yml", "messages.yml"):
            continue
        percorso = os.path.join(risorse, f)
        chiavi = chiavi_del_file(percorso)
        nomi = {c["chiave"] for c in chiavi}

        for c in chiavi:
            if c["sezione"]:
                continue   # una sezione la si giudica dalle chiavi che contiene
            if not c["commento"]:
                problemi.append((f, c["riga"], "[1] senza commento", c["chiave"]))
                continue
            # [2] valori alternativi: la chiave vale una parola, e nel codice c'e' un confronto con
            # un'ALTRA parola per la stessa chiave -> il commento deve nominarla.
            valore = c["valore"].split("#")[0].strip().strip('"\'')
            if re.fullmatch(r"[a-z][a-z0-9_\-]{1,24}", valore or "") and valore not in ("true", "false"):
                base = c["chiave"].split(".")[-1]
                vicino = re.findall(r'"' + re.escape(c["chiave"]) + r'"[^;]{0,200}', codice)
                vicino += re.findall(r'get\w+\(\s*"[^"]*' + re.escape(base) + r'"\)[^;]{0,200}', codice)
                alternativi = set()
                for pezzo in vicino:
                    # Solo confronti VERI con un letterale: case "x", equals("x"), equalsIgnoreCase("x").
                    # Senza questo filtro finivano nell'elenco i nomi delle colonne del database e delle
                    # chiavi vicine, che non sono affatto valori alternativi.
                    for lett in re.findall(r'(?:case\s+|equals(?:IgnoreCase)?\(\s*)"([a-z][a-z0-9_\-]{1,24})"', pezzo):
                        if lett != valore and lett != c["chiave"] and not lett.endswith(base):
                            alternativi.add(lett)
                # anche gli switch/case sulla stessa chiave, ovunque siano
                mancanti = sorted(a for a in alternativi if a not in c["commento"])
                if mancanti:
                    problemi.append((f, c["riga"], "[2] altri valori non spiegati: " + ", ".join(mancanti[:4]),
                                     c["chiave"]))

        if f == "config.yml":
            for k in sorted(lette):
                if k.endswith("."):
                    continue   # prefisso costruito nel codice ("map.colors." + nome), non una chiave
                if "." in k and k not in nomi and not IGNORA_NON_LETTE.match(k) \
                        and k.split(".")[0] in {n.split(".")[0] for n in nomi}:
                    problemi.append((f, 0, "[3] letta dal codice, assente dal file", k))
            for c in chiavi:
                k = c["chiave"]
                # Una chiave puo' essere letta con il percorso COSTRUITO nel codice
                # ("territory-titles." + node + ".title"): in quel caso nel sorgente compare il prefisso
                # del padre seguito da una concatenazione, e la chiave e' viva anche se il suo nome
                # per intero non si trova da nessuna parte.
                padre = k.rsplit(".", 1)[0]
                if padre != k and ('"' + padre + '." +') in codice:
                    continue
                if k not in lette and k.split(".")[-1] not in codice and not IGNORA_NON_LETTE.match(k):
                    problemi.append((f, c["riga"], "[4] nel file ma mai letta dal codice", k))
            problemi += numeri_a_mano(cartella, chiavi)
            problemi += modalita_non_raccontate(cartella, chiavi, codice)
    return problemi


# Classi che i plugin CONDIVIDONO: sono copie, non una libreria, quindi devono restare identiche
# (a parte la riga del package). Se divergono, una correzione fatta in un plugin non arriva agli altri.
CLASSI_COMUNI = ["ValoriConfig.java", "TestiDurate.java", "GuidaStaff.java", "Aiuto.java"]


def percorso_util(nome):
    return os.path.join(QUI, nome, "src", "main", "java", "com", "teolo", nome.lower(), "util")


def corpo(percorso):
    """Il file senza la riga del package: quella e' l'unica differenza ammessa fra le copie."""
    with open(percorso, encoding="utf-8") as f:
        return "\n".join(r for r in f.read().split("\n") if not r.startswith("package "))


def controlla_classi_comuni(elenco):
    """Segnala le classi comuni che sono diverse da un plugin all'altro (o che mancano)."""
    problemi = []
    for classe in CLASSI_COMUNI:
        versioni = {}
        for nome in elenco:
            p = os.path.join(percorso_util(nome), classe)
            if os.path.isfile(p):
                versioni.setdefault(corpo(p), []).append(nome)
        if len(versioni) <= 1:
            continue
        # La copia "buona" e' quella piu' diffusa; le altre sono da riallineare.
        gruppi = sorted(versioni.values(), key=len, reverse=True)
        riferimento = gruppi[0]
        for diverso in gruppi[1:]:
            problemi.append(("classi comuni", 0,
                             f"[5] {classe} DIVERSA da {', '.join(riferimento)}",
                             ", ".join(diverso)))
    return problemi


def main():
    elenco = sys.argv[1:] or PLUGIN
    totale = 0
    for nome in elenco:
        problemi = controlla(nome)
        print(f"\n=== {nome}: {len(problemi)} da sistemare" if problemi else f"\n=== {nome}: pulito")
        for f, riga, tipo, chiave in problemi:
            print(f"  {f}:{riga or '?'}  {tipo}  -> {chiave}")
        totale += len(problemi)

    if len(elenco) > 1:
        comuni = controlla_classi_comuni(elenco)
        print(f"\n=== classi comuni: {len(comuni)} disallineate" if comuni else "\n=== classi comuni: allineate")
        for _, _, tipo, chi in comuni:
            print(f"  {tipo}  -> in: {chi}")
        totale += len(comuni)

    print(f"\nTOTALE: {totale}")
    return 1 if totale else 0


if __name__ == "__main__":
    sys.exit(main())
