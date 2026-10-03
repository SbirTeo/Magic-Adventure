/* =====================================================================================
   Store dei Magix (store.php): cursore della quantita', prezzo con lo sconto, sacco che si
   riempie e portafoglio che resta aggiornato da solo (/api/magix).

   Prezzi e livelli arrivano dal server (data-config, da includes/magix.php): i conti qui
   servono solo a mostrare il prezzo mentre si sceglie, la cassa (store/checkout.php) li rifa'
   da capo e non si fida di questi.
   ===================================================================================== */
(() => {
  const box = document.getElementById('magixScelta');
  if (!box) return;

  const CFG = JSON.parse(box.dataset.config);
  const MIN = CFG.min, MAX = CFG.max, LIVELLI = CFG.tiers;
  const $ = id => document.getElementById(id);
  const numero = n => n.toLocaleString('it-IT');
  const euro = cents => (cents / 100).toLocaleString('it-IT', {minimumFractionDigits: 2, maximumFractionDigits: 2}) + ' €';
  const lento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const binario = $('magixBinario'), maniglia = $('magixManiglia'), riempito = $('magixRiempito');
  const sacco = $('magixSacco'), linea = $('magixLinea');

  let valore = Math.min(MAX, Math.max(MIN, parseInt(box.dataset.initial, 10) || MIN));
  let mostrato = valore, livelloPrima = -1;

  // La scala del cursore non e' lineare: i primi valori hanno piu' spazio, cosi' anche le
  // quantita' piccole si scelgono comode col dito. pos 0..1 <-> valore MIN..MAX
  const ESP = 1.6;
  const posOf = v => Math.pow((v - MIN) / (MAX - MIN), 1 / ESP);
  const valueAt = p => Math.round(MIN + Math.pow(Math.min(1, Math.max(0, p)), ESP) * (MAX - MIN));
  const tierOf = v => LIVELLI.reduce((acc, l, k) => (v >= l.from ? k : acc), 0);

  /** Stessi conti di magix_quote() in includes/magix.php, in centesimi interi. */
  function quote(v) {
    const pct = LIVELLI[tierOf(v)].pct;
    const pieno = Math.round(v * CFG.price * 100);
    const totale = Math.floor((pieno * (100 - pct) + 50) / 100);
    return {pct, pieno, totale};
  }

  // ---- tacche ed etichette sotto il cursore -------------------------------------------
  const etichette = $('magixEtichette');
  LIVELLI.forEach((l, i) => {
    const p = posOf(l.from) * 100;
    // Niente puntino alle due estremita' (1 e 1000): cadrebbe sul bordo arrotondato del
    // binario, e li' c'e' comunque la maniglia quando ci si arriva.
    if (i > 0 && i < LIVELLI.length - 1) {
      const t = document.createElement('span');
      t.className = 'magix-tacca'; t.style.left = p + '%'; t.dataset.i = i;
      binario.appendChild(t);
    }
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'magix-tacca-lbl' + (i === 0 ? ' primo' : '') + (i === LIVELLI.length - 1 ? ' ultimo' : '');
    b.dataset.i = i; b.style.left = p + '%';
    b.innerHTML = numero(l.from) + '<small>' + (l.pct ? '-' + l.pct + '%' : '&nbsp;') + '</small>';
    b.addEventListener('click', () => setValue(l.from, true));
    etichette.appendChild(b);
  });
  // Toccare un pacchetto del catalogo porta il cursore al suo costo (fino al massimo comprabile).
  const voci = document.querySelectorAll('.magix-voce');
  voci.forEach(c => c.addEventListener('click', () => setValue(Math.min(MAX, parseInt(c.dataset.cost, 10)), true)));

  /** "Cosa puoi comprare con N Magix": per ogni pacchetto, basta o quanto manca. */
  function renderCatalog(v) {
    const n = $('magixCatalogoN');
    if (n) n.textContent = numero(v);
    voci.forEach(c => {
      const costo = parseInt(c.dataset.cost, 10), basta = costo <= v;
      c.classList.toggle('basta', basta);
      c.querySelector('.magix-voce-barra i').style.width = Math.min(100, Math.round(v / Math.max(1, costo) * 100)) + '%';
      c.querySelector('.magix-voce-stato').textContent = basta ? '\u2713 Ti bastano' : 'Ti mancano ' + numero(costo - v) + ' Magix';
    });
  }

  document.querySelectorAll('.magix-livello').forEach(c =>
    c.addEventListener('click', () => setValue(parseInt(c.dataset.from, 10), true)));

  // ---- il sacco si colora dal basso quanto il cursore -----------------------------------
  function fillBag(f) {
    sacco.style.setProperty('--vuoto', (f >= 1 ? 0 : 92 - 85 * f) + '%');
    linea.classList.toggle('spenta', f <= 0 || f >= 1);
    $('magixAlone').style.transform = `scale(${0.8 + 0.5 * f})`;
  }

  function animateNumber(el, da, a, durata, poi) {
    cancelAnimationFrame(el._raf);
    if (lento) { el.textContent = numero(a); if (poi) poi(); return; }
    const t0 = performance.now();
    const passo = t => {
      const k = Math.min(1, (t - t0) / durata), e = 1 - Math.pow(1 - k, 3);
      el.textContent = numero(Math.round(da + (a - da) * e));
      if (k < 1) el._raf = requestAnimationFrame(passo); else if (poi) poi();
    };
    el._raf = requestAnimationFrame(passo);
  }

  function sparkles(n) {
    if (lento) return;
    const r = maniglia.getBoundingClientRect(), br = binario.getBoundingClientRect();
    for (let i = 0; i < n; i++) {
      const s = document.createElement('span');
      s.className = 'magix-scintilla';
      s.style.left = (r.left - br.left + r.width / 2) + 'px';
      s.style.top = (r.top - br.top + r.height / 2) + 'px';
      s.style.background = ['#ffd1ff', '#d54cff', 'var(--gold)', '#ffffff'][i % 4];
      binario.appendChild(s);
      const ang = Math.random() * Math.PI * 2, dist = 40 + Math.random() * 60;
      s.animate([
        {transform: 'translate(0,0) rotate(0)', opacity: 1},
        {transform: `translate(${Math.cos(ang) * dist}px,${Math.sin(ang) * dist - 30}px) rotate(${Math.random() * 360}deg)`, opacity: 0},
      ], {duration: 700 + Math.random() * 400, easing: 'cubic-bezier(.2,.8,.3,1)'}).onfinish = () => s.remove();
    }
  }

  function render(scorri) {
    const v = valore, pos = posOf(v);
    maniglia.style.left = pos * 100 + '%';
    riempito.style.width = pos * 100 + '%';
    maniglia.setAttribute('aria-valuenow', v);
    maniglia.setAttribute('aria-valuetext', v + ' Magix');
    const campo = $('magixAmount');
    if (campo) campo.value = v;

    const li = tierOf(v), q = quote(v);
    if (scorri) animateNumber($('magixNum'), mostrato, v, 380); else $('magixNum').textContent = numero(v);
    mostrato = v;
    $('magixPieno').textContent = euro(q.pieno);
    $('magixSconto').textContent = q.pct ? q.pct + '%' : '—';
    $('magixRisparmio').textContent = euro(q.pieno - q.totale);
    $('magixTotale').textContent = euro(q.totale);
    $('magixUnit').textContent = (q.totale / 100 / v).toLocaleString('it-IT', {minimumFractionDigits: 3, maximumFractionDigits: 3}) + ' €';
    $('magixBarrato').style.visibility = q.pct ? 'visible' : 'hidden';

    const pill = $('magixPill');
    pill.textContent = '-' + q.pct + '%';
    pill.classList.toggle('spento', !q.pct);

    const prossimo = LIVELLI[li + 1];
    $('magixProssimo').innerHTML = prossimo
      ? `Ancora <b>${numero(prossimo.from - v)}</b> Magix e passi a <b>-${prossimo.pct}%</b>`
      : '<b>Sconto massimo raggiunto!</b>';

    document.querySelectorAll('.magix-tacca, .magix-tacca-lbl').forEach(e => e.classList.toggle('presa', +e.dataset.i <= li));
    document.querySelectorAll('.magix-livello').forEach(e => {
      e.classList.toggle('raggiunto', +e.dataset.i <= li);
      e.classList.toggle('attuale', +e.dataset.i === li);
    });

    fillBag(pos);
    renderCatalog(v);

    if (livelloPrima !== -1 && li > livelloPrima && !lento) {
      pill.classList.remove('pop'); void pill.offsetWidth; pill.classList.add('pop');
      sacco.classList.remove('salto'); void sacco.offsetWidth; sacco.classList.add('salto');
      sparkles(18);
    }
    livelloPrima = li;
  }

  function setValue(v, scorri) {
    v = Math.min(MAX, Math.max(MIN, Math.round(v)));
    if (v === valore && livelloPrima !== -1) return;
    valore = v;
    render(scorri);
  }

  // ---- trascinamento --------------------------------------------------------------------
  let trascina = false;
  const posFromEvent = e => { const r = binario.getBoundingClientRect(); return (e.clientX - r.left) / r.width; };
  binario.addEventListener('pointerdown', e => {
    trascina = true;
    binario.setPointerCapture(e.pointerId);
    maniglia.classList.add('presa', 'toccata');
    maniglia.focus({preventScroll: true});
    setValue(valueAt(posFromEvent(e)), !maniglia.contains(e.target));
  });
  binario.addEventListener('pointermove', e => { if (trascina) setValue(valueAt(posFromEvent(e)), false); });
  const fine = () => { trascina = false; maniglia.classList.remove('presa'); };
  binario.addEventListener('pointerup', fine);
  binario.addEventListener('pointercancel', fine);

  // Tastiera: frecce +-1 (con Maiusc +-10), PagSu/PagGiu +-50, Inizio/Fine
  maniglia.addEventListener('keydown', e => {
    const passo = e.shiftKey ? 10 : 1;
    const mosse = {ArrowRight: passo, ArrowUp: passo, ArrowLeft: -passo, ArrowDown: -passo, PageUp: 50, PageDown: -50};
    if (e.key in mosse) { setValue(valore + mosse[e.key], false); e.preventDefault(); maniglia.classList.add('toccata'); }
    if (e.key === 'Home') { setValue(MIN, true); e.preventDefault(); }
    if (e.key === 'End') { setValue(MAX, true); e.preventDefault(); }
  });

  // - e +: tenendo premuto il valore scorre sempre piu' in fretta
  function holdRepeat(btn, d) {
    let t, giri = 0;
    const via = () => { clearTimeout(t); giri = 0; };
    const vai = () => { setValue(valore + d * (giri > 12 ? 10 : 1), false); giri++; t = setTimeout(vai, giri === 1 ? 350 : 60); };
    btn.addEventListener('pointerdown', e => { e.preventDefault(); vai(); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => btn.addEventListener(ev, via));
    btn.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setValue(valore + d, false); } });
  }
  holdRepeat($('magixMeno'), -1);
  holdRepeat($('magixPiu'), 1);

  render(false);

  // ---- regalo: per me o per un altro giocatore -----------------------------------------
  // Il nome si controlla mentre lo si scrive (/api/magix?player=): finche' non e' un
  // giocatore vero il pulsante non parte. La cassa lo ricontrolla comunque.
  const regalo = $('magixRegalo'), campo = $('magixDestinatario');
  if (regalo && campo) {
    const esito = $('magixRegaloEsito'), avatar = $('magixRegaloAvatar'), testoPaga = $('magixPagaTesto');
    const gemma = avatar.getAttribute('src');
    const scelte = document.querySelectorAll('input[name="for"]');
    const frase = esito.textContent;
    let attesa, ultimo = '';

    const isGift = () => document.querySelector('input[name="for"]:checked').value === 'gift';
    const markRecipient = (stato, testo, foto) => {
      regalo.dataset.stato = stato;
      esito.textContent = testo;
      avatar.src = foto || gemma;
      campo.setCustomValidity(stato === 'ok' ? '' : (testo || 'Scrivi il nome di un giocatore'));
    };

    async function checkRecipient() {
      const nome = campo.value.trim();
      if (nome === ultimo) return;
      ultimo = nome;
      if (nome.length < 2) { markRecipient('', frase); return; }
      markRecipient('cerca', 'Cerco ' + nome + '...');
      try {
        const r = await fetch('/api/magix?player=' + encodeURIComponent(nome), {credentials: 'same-origin', cache: 'no-store'});
        const d = await r.json();
        if (campo.value.trim() !== nome) return;          // nel frattempo ha scritto altro
        if (!d.found) markRecipient('no', 'Nessun giocatore si chiama ' + nome + '.');
        else if (d.self) markRecipient('no', 'Questo sei tu: per te scegli «Per me».', d.avatar);
        else markRecipient('ok', 'I Magix arriveranno nel portafoglio di ' + d.name + ', su tutta la rete.', d.avatar);
      } catch (e) { markRecipient('', frase); ultimo = ''; }
    }

    function syncChoice() {
      const g = isGift();
      regalo.hidden = !g;
      campo.required = g;
      if (testoPaga) testoPaga.textContent = g ? 'Regala con PayPal' : 'Paga con PayPal';
      if (!g) campo.setCustomValidity('');
      else { ultimo = ''; checkRecipient(); }
    }
    scelte.forEach(r => r.addEventListener('change', () => { syncChoice(); if (isGift()) campo.focus(); }));
    campo.addEventListener('input', () => { clearTimeout(attesa); attesa = setTimeout(checkRecipient, 350); });
    syncChoice();
  }

  // ---- portafoglio: saldo vero, riletto ogni pochi secondi -----------------------------
  const portafoglio = $('magixPortafoglio');
  const saldoEl = $('magixSaldo');
  if (!portafoglio || portafoglio.dataset.live !== '1' || !saldoEl) return;

  const readBalance = () => parseInt(saldoEl.textContent.replace(/\D/g, ''), 10);
  let saldo = readBalance();
  let ultimaRicarica = null;

  function celebrateBalance(da, a) {
    const piu = a - da;
    animateNumber(saldoEl, da, a, 900);
    const s = $('magixSaldoBox');
    s.classList.remove('cresce'); void s.offsetWidth; s.classList.add('cresce');
    if (piu > 0) {
      const p = $('magixPiuArrivo');
      p.textContent = '+' + numero(piu);
      p.classList.remove('vai'); void p.offsetWidth; p.classList.add('vai');
    }
  }

  function renderTopUps(ordini) {
    const ul = $('magixMovimenti');
    const primo = ordini.length ? ordini[0].id : null;
    if (primo === ultimaRicarica) return;
    const nuova = ultimaRicarica !== null;
    ultimaRicarica = primo;
    ul.textContent = '';
    ordini.forEach((o, i) => {
      const li = document.createElement('li');
      // kind: self = ricarica, sent = regalo fatto (non entra nel mio saldo), received = regalo ricevuto
      li.className = 'is-' + o.kind + (nuova && i === 0 ? ' nuovo' : '');
      li.innerHTML = '<span class="magix-mov-ico"></span><span class="magix-mov-cosa"><b></b><span></span></span><span class="magix-mov-q"></span>';
      li.querySelector('.magix-mov-ico').textContent = o.kind === 'sent' ? '\u2197' : '+';
      li.querySelector('.magix-mov-cosa b').textContent = o.label;
      li.querySelector('.magix-mov-cosa span').textContent = o.meta;
      li.querySelector('.magix-mov-q').textContent = (o.kind === 'sent' ? '' : '+') + numero(o.amount);
      ul.appendChild(li);
    });
    $('magixNessuna').hidden = ordini.length > 0;
  }

  async function refreshWallet() {
    if (document.hidden) return;
    try {
      const r = await fetch('/api/magix', {credentials: 'same-origin', cache: 'no-store'});
      if (!r.ok) return;
      const d = await r.json();
      if (!d.ok) return;
      if (isNaN(saldo)) { saldo = d.balance; saldoEl.textContent = numero(saldo); }
      else if (d.balance !== saldo) { celebrateBalance(saldo, d.balance); saldo = d.balance; }
      renderTopUps(d.orders);
    } catch (e) { /* rete assente: si riprova al giro dopo */ }
  }
  refreshWallet();
  setInterval(refreshWallet, 8000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshWallet(); });

  // ---- rientro da PayPal: i Magix volano dal sacco al portafoglio -----------------------
  const velo = $('magixVelo');
  if (!velo) return;
  const closeDialog = () => velo.classList.remove('on');
  $('magixChiudi').addEventListener('click', closeDialog);
  velo.addEventListener('click', e => { if (e.target === velo) closeDialog(); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') closeDialog(); });

  const arrivati = parseInt(velo.dataset.amount, 10) || 0;
  if (!arrivati || isNaN(saldo)) return;
  const prima = Math.max(0, saldo - arrivati);
  saldoEl.textContent = numero(prima);
  velo.classList.remove('on');
  setTimeout(() => {
    const da = sacco.getBoundingClientRect(), a = saldoEl.getBoundingClientRect();
    const n = lento ? 0 : Math.min(26, 6 + Math.round(arrivati / 40));
    for (let i = 0; i < n; i++) {
      const g = document.createElement('img');
      g.src = '/assets/img/magix.svg'; g.alt = ''; g.className = 'magix-volo';
      document.body.appendChild(g);
      const x0 = da.left + da.width / 2 + (Math.random() - .5) * 60, y0 = da.top + da.height / 2 + (Math.random() - .5) * 40;
      const x1 = a.left - 30, y1 = a.top + a.height / 2 - 11;
      const cx = (x0 + x1) / 2 + (Math.random() - .5) * 200, cy = Math.min(y0, y1) - 120 - Math.random() * 120;
      const punti = [];
      for (let k = 0; k <= 20; k++) {
        const t = k / 20;
        const x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x1;
        const y = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y1;
        punti.push({transform: `translate(${x}px,${y}px) rotate(${t * 360}deg) scale(${1 - t * .3})`, opacity: t > .95 ? 0 : 1});
      }
      g.animate(punti, {duration: 900 + Math.random() * 300, delay: i * 35, easing: 'cubic-bezier(.45,0,.3,1)', fill: 'both'}).onfinish = () => g.remove();
    }
    setTimeout(() => celebrateBalance(prima, saldo), lento ? 0 : 1000);
    setTimeout(() => velo.classList.add('on'), lento ? 0 : 2100);
  }, 400);
})();
