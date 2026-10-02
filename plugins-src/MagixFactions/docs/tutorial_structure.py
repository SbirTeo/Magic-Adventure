# -*- coding: utf-8 -*-
"""Struttura del tutorial: numeri dei capitoli, id dei sottocapitoli, indice e parti (vedi build_tutorial.py)."""

# ---------------------------------------------------------------------------------------------
# STRUTTURA: numeri dei capitoli, indice e intestazioni delle parti li scrive lo script, non la
# mano. Nel testo un capitolo e' <section id="..." data-desc="riga per l'indice"> con
# <h2><span class="n">#</span>Titolo</h2> (il # diventa il numero), le parti sono commenti
# <!--PART Titolo | descrizione--> e l'indice va dove c'e' <!--TOC-->. Ogni <h3> riceve un id
# suo e finisce in "In questo capitolo" in testa al suo capitolo. Cosi' spostare o aggiungere un capitolo
# non lascia mai un indice vecchio o una numerazione sbagliata.
import re
import unicodedata

def _slug(text):
    text = re.sub(r"<[^>]+>|\{\{[^}]*\}\}", " ", text)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:40]

def _plain(text):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", text)).strip()

def structure(html):
    toc, count, part_no = [], [0], [0]

    def section(m):
        sid, desc, body = m.group(1), m.group(2), m.group(3)
        count[0] += 1
        body = body.replace('<span class="n">#</span>', '<span class="n">%d</span>' % count[0], 1)
        title = _plain(re.sub(r'<span class="n">.*?</span>', "", re.search(r"<h2>(.*?)</h2>", body, re.S).group(1)))
        subs = []
        def h3(mm):
            sub_id = sid + "-" + _slug(mm.group(1))
            subs.append((sub_id, _plain(mm.group(1))))
            return '<h3 id="%s">%s</h3>' % (sub_id, mm.group(1))
        body = re.sub(r"<h3>(.*?)</h3>", h3, body, flags=re.S)
        toc[-1][2].append((sid, count[0], title, desc, subs))
        # "In questo capitolo": i sottocapitoli come etichette sotto il titolo (come nella guida per
        # lo staff e nel regolamento del sito), invece che nell'indice in cima.
        if len(subs) > 1:
            chips = "".join('<a href="#%s">%s</a>' % (i, t) for i, t in subs)
            body = re.sub(r"(</h2>)", r'\1\n  <nav class="chips" aria-label="In questo capitolo"><span>In questo capitolo:</span>%s</nav>'
                          % chips.replace("\\", "\\\\"), body, count=1)
        back = '\n  <p class="back"><a href="#indice">↑ Indice</a></p>\n'
        return '<section id="%s" data-desc="%s">%s%s</section>' % (sid, desc, body.rstrip() + "\n", back)

    def part(m):
        part_no[0] += 1
        toc.append((m.group(1).strip(), m.group(2).strip(), []))
        return ('<div class="part" id="parte-%d"><span class="part-n">Parte %d</span>'
                '<span class="part-t">%s</span><span class="part-d">%s</span></div>'
                % (part_no[0], part_no[0], m.group(1).strip(), m.group(2).strip()))

    # parti e capitoli nell'ordine in cui compaiono
    pieces = re.split(r"(<!--PART [^>]*-->)", html)
    out = []
    for piece in pieces:
        pm = re.match(r"<!--PART (.*?)\|(.*?)-->", piece)
        if pm:
            out.append(part(pm))
        else:
            out.append(re.sub(r'<section id="([^"]+)" data-desc="([^"]*)">(.*?)</section>', section, piece, flags=re.S))
    html = "".join(out)

    cards = []
    for n, (ptitle, pdesc, chapters) in enumerate(toc, 1):
        items = []
        for sid, num, title, desc, subs in chapters:
            items.append('<li><a class="toc-ch" href="#%s"><span class="toc-n">%d</span>%s</a>'
                         '<span class="toc-d">%s</span></li>' % (sid, num, title, desc))
        cards.append('<div class="toc-part"><a class="toc-pt" href="#parte-%d">Parte %d · %s</a>'
                     '<ol>%s</ol></div>' % (n, n, ptitle, "".join(items)))
    toc_html = ('<nav class="toc" id="indice" aria-label="Indice"><div class="toc-h">Indice</div>'
                '<div class="toc-grid">%s</div></nav>' % "".join(cards))
    return html.replace("<!--TOC-->", toc_html, 1)

