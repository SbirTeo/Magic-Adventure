/*
 * Scheda "Progetto" del gestionale: lo spazio di lavoro condiviso fra gli amministratori.
 * Legge e scrive tutto da /api/project.php. Viste: Panoramica (avanzamento, obiettivi,
 * andamento, prossimi appuntamenti), Bacheca (schede trascinabili fra le colonne, anche col
 * dito), Calendario (mese), Registro (chi ha fatto cosa) e la Chat, sempre accanto da schermo
 * largo e scheda a se' da telefono.
 *
 * Aggiornamento: un sondaggio ogni 3 secondi (12 se la pagina e' in secondo piano) porta i
 * messaggi nuovi o cambiati, chi sta scrivendo e le spunte di lettura; se la "revisione" della
 * bacheca cambia, si ricarica lo stato intero (sono pochi dati).
 */
(function () {
  'use strict';

  const root = document.getElementById('project');
  if (!root) return;

  const API = '/api/project';   // senza .php, come /api/chat: nginx ci arriva da solo
  const CSRF = root.dataset.csrf;
  const ME = Number(root.dataset.me);
  const COLUMNS = [
    { id: 'idea', label: 'Idee', color: '#94959b' },
    { id: 'todo', label: 'Da fare', color: '#f5b942' },
    { id: 'doing', label: 'In corso', color: '#c04ff0' },
    { id: 'done', label: 'Fatto', color: '#a3e635' },
  ];
  const PRIORITY = { low: 'Bassa', normal: 'Normale', high: 'Alta' };
  const SWATCHES = ['#a3e635', '#c04ff0', '#38bdf8', '#f5b942', '#f07070', '#2dd4bf', '#94959b'];
  const MONTHS = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio', 'agosto',
    'settembre', 'ottobre', 'novembre', 'dicembre'];
  const MONTHS_SHORT = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic'];
  const DOW = ['lun', 'mar', 'mer', 'gio', 'ven', 'sab', 'dom'];

  const state = {
    admins: new Map(), goals: [], tasks: [], events: [], activity: [], weekly: [],
    messages: new Map(), read: {}, typing: [], reactions: [],
    rev: null, since: '', lastMsg: 0, activitySeen: 0, skew: 0,
    view: loadPref('view', 'overview'),
    calMonth: null,
    filter: { who: 'all', goal: 'all', q: '' },
    reply: null, attach: null, editing: null, search: '',
    dragging: false, loaded: false,
  };

  // ------------------------------------------------------------------ utilita'

  function loadPref(key, fallback) {
    try { return localStorage.getItem('pj.' + key) || fallback; } catch (e) { return fallback; }
  }
  function savePref(key, value) {
    try { localStorage.setItem('pj.' + key, value); } catch (e) { /* navigazione privata: pazienza */ }
  }

  /** Crea un elemento: el('div', {class: 'x', onclick: fn}, [figli o testo]). */
  function el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        const v = attrs[k];
        if (v === null || v === undefined || v === false) continue;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k === 'html') node.innerHTML = v;
        else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
        else if (k === 'dataset') Object.assign(node.dataset, v);
        else node.setAttribute(k, v === true ? '' : v);
      }
    }
    (Array.isArray(children) ? children : children !== undefined ? [children] : []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      node.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    });
    return node;
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /** "YYYY-MM-DD HH:MM:SS" (ora del server) -> Date locale con gli stessi numeri. */
  function parseDate(s) {
    if (!s) return null;
    const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?/);
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0));
  }
  function pad(n) { return String(n).padStart(2, '0'); }
  function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function hm(d) { return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
  /** "Adesso" secondo l'orologio del server (la differenza col telefono e' misurata al caricamento). */
  function now() { return new Date(Date.now() + state.skew); }
  function today() { const n = now(); return new Date(n.getFullYear(), n.getMonth(), n.getDate()); }
  function daysBetween(a, b) { return Math.round((b - a) / 86400000); }
  function sameDay(a, b) { return a && b && a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate(); }

  function timeAgo(s) {
    const d = parseDate(s);
    if (!d) return '';
    const sec = Math.max(0, Math.round((now() - d) / 1000));
    if (sec < 60) return 'adesso';
    if (sec < 3600) return Math.floor(sec / 60) + ' min fa';
    if (sec < 86400) return Math.floor(sec / 3600) + ' h fa';
    if (sec < 86400 * 7) return Math.floor(sec / 86400) + ' g fa';
    return d.getDate() + ' ' + MONTHS_SHORT[d.getMonth()];
  }

  function dueLabel(dateStr) {
    const d = parseDate(dateStr);
    if (!d) return null;
    const diff = daysBetween(today(), d);
    let text;
    if (diff === 0) text = 'oggi';
    else if (diff === 1) text = 'domani';
    else if (diff === -1) text = 'ieri';
    else if (diff < 0) text = Math.abs(diff) + ' g fa';
    else if (diff < 7) text = 'fra ' + diff + ' g';
    else text = d.getDate() + ' ' + MONTHS_SHORT[d.getMonth()];
    return { text: text, late: diff < 0, diff: diff };
  }

  function admin(id) { return state.admins.get(Number(id)) || null; }
  function adminName(id) { const a = admin(id); return a ? a.name : 'qualcuno'; }
  function avatar(id, cls) {
    const a = admin(id);
    return a ? el('img', { class: 'pj-avatar ' + (cls || ''), src: a.avatar, alt: a.name, title: a.name, loading: 'lazy' }) : null;
  }
  function goal(id) { return state.goals.find(function (g) { return g.id === Number(id); }) || null; }
  function task(id) { return state.tasks.find(function (t) { return t.id === Number(id); }) || null; }

  function toast(message) {
    const t = el('div', { class: 'pj-toast', role: 'alert', text: message });
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 4000);
  }

  // ------------------------------------------------------------------ rete

  async function apiGet(params) {
    const res = await fetch(API + '?' + new URLSearchParams(params), { credentials: 'same-origin', cache: 'no-store' });
    const data = await res.json().catch(function () { return { ok: false, error: 'Risposta non valida dal server.' }; });
    if (!data.ok) throw new Error(data.error || 'Errore');
    return data;
  }

  async function apiPost(action, fields, file) {
    const body = new FormData();
    body.append('csrf', CSRF);
    body.append('action', action);
    for (const k in (fields || {})) {
      const v = fields[k];
      body.append(k, v === null || v === undefined ? '' : v === true ? '1' : v === false ? '' : v);
    }
    if (file) body.append('image', file, file.name || 'immagine.png');
    const res = await fetch(API, { method: 'POST', body: body, credentials: 'same-origin' });
    const data = await res.json().catch(function () { return { ok: false, error: 'Risposta non valida dal server.' }; });
    if (!data.ok) throw new Error(data.error || 'Errore');
    return data;
  }

  /** Esegue un'azione, avvisa se fallisce, e ricarica lo stato. */
  async function act(action, fields, file) {
    try {
      const r = await apiPost(action, fields, file);
      await loadState();
      return r;
    } catch (e) {
      toast(e.message);
      return null;
    }
  }

  function mergeMessages(list) {
    let changed = false;
    list.forEach(function (m) {
      const old = state.messages.get(m.id);
      if (!old || JSON.stringify(old) !== JSON.stringify(m)) { state.messages.set(m.id, m); changed = true; }
      if (m.id > state.lastMsg) state.lastMsg = m.id;
    });
    return changed;
  }

  async function loadState() {
    const d = await apiGet({ action: 'state' });
    state.skew = (parseDate(d.now) || new Date()) - Date.now();
    state.admins = new Map(d.admins.map(function (a) { return [a.id, a]; }));
    state.goals = d.goals;
    state.tasks = d.tasks;
    state.events = d.events;
    state.activity = d.activity;
    state.weekly = d.weekly;
    state.reactions = d.reactions;
    state.read = d.read || {};
    state.typing = d.typing || [];
    state.rev = d.rev;
    state.since = d.now;
    if (!state.loaded) state.activitySeen = d.activity_seen;
    const msgChanged = mergeMessages(d.messages);
    if (!state.loaded) { buildLayout(); state.loaded = true; }
    renderView();
    renderChatHead();
    if (msgChanged || !state.chatRendered) renderMessages();
    updateUnread();
  }

  let pollTimer = null;
  async function poll() {
    clearTimeout(pollTimer);
    try {
      const d = await apiGet({ action: 'poll', after: state.lastMsg, since: state.since, rev: state.rev || '' });
      state.since = d.now;
      state.read = d.read || {};
      const typingBefore = state.typing.join(',');
      state.typing = d.typing || [];
      const newIds = d.messages.filter(function (m) { return !state.messages.has(m.id) && m.user !== ME; });
      const changed = mergeMessages(d.messages);
      if (changed) renderMessages();
      else refreshTicks();
      if (typingBefore !== state.typing.join(',')) renderTyping();
      if (newIds.length) notifyNew(newIds);
      updateUnread();
      if (d.rev !== state.rev && !state.dragging) await loadState();
    } catch (e) {
      /* rete assente: si riprova al prossimo giro */
    }
    pollTimer = setTimeout(poll, document.hidden ? 12000 : 3000);
  }
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { poll(); markRead(); }
  });

  // ------------------------------------------------------------------ impaginazione

  const views = [
    { id: 'overview', label: 'Panoramica' },
    { id: 'board', label: 'Bacheca' },
    { id: 'calendar', label: 'Calendario' },
    { id: 'log', label: 'Registro' },
    { id: 'chat', label: 'Chat' },
  ];
  let viewBox, sideBox, tabsBox;

  function buildLayout() {
    root.innerHTML = '';
    tabsBox = el('div', { class: 'pj-tabs', role: 'tablist' });
    views.forEach(function (v) {
      tabsBox.appendChild(el('button', {
        type: 'button', class: 'pj-tab', role: 'tab', dataset: { view: v.id },
        onclick: function () { setView(v.id); },
      }, [v.label, el('span', { class: 'pj-count', hidden: true })]));
    });
    viewBox = el('div', { class: 'pj-view' });
    sideBox = el('aside', { class: 'pj-side' }, buildChat());
    root.appendChild(tabsBox);
    root.appendChild(el('div', { class: 'pj-layout' }, [el('div', { class: 'pj-main' }, viewBox), sideBox]));
    setView(state.view, true);
  }

  function setView(id, silent) {
    if (!views.some(function (v) { return v.id === id; })) id = 'overview';
    // Da schermo largo la chat e' gia' accanto: la scheda "Chat" riporta alla panoramica.
    if (id === 'chat' && window.matchMedia('(min-width: 1101px)').matches) id = 'overview';
    state.view = id;
    savePref('view', id);
    tabsBox.querySelectorAll('.pj-tab').forEach(function (t) { t.classList.toggle('is-active', t.dataset.view === id); });
    root.classList.toggle('show-chat', id === 'chat');
    if (id !== 'chat') renderView();
    if (id === 'chat') { scrollChatBottom(true); markRead(); }
    if (id === 'log') markActivitySeen();
    if (!silent) window.scrollTo({ top: root.getBoundingClientRect().top + window.pageYOffset - 120, behavior: 'smooth' });
  }

  function renderView() {
    if (!viewBox || state.dragging) return;
    viewBox.innerHTML = '';
    if (state.view === 'board') viewBox.appendChild(renderBoard());
    else if (state.view === 'calendar') viewBox.appendChild(renderCalendar());
    else if (state.view === 'log') viewBox.appendChild(renderLog());
    else if (state.view !== 'chat') viewBox.appendChild(renderOverview());
    updateUnread();
  }

  // ------------------------------------------------------------------ panoramica

  /** Attivita' che contano per l'avanzamento: le idee non sono ancora impegni. */
  function committed(list) { return list.filter(function (t) { return t.status !== 'idea'; }); }

  function renderOverview() {
    const wrap = el('div', { class: 'pj-overview' });
    const work = committed(state.tasks);
    const done = work.filter(function (t) { return t.status === 'done'; }).length;
    const pct = work.length ? Math.round(done / work.length * 100) : 0;
    const late = work.filter(function (t) { const d = dueLabel(t.due_date); return t.status !== 'done' && d && d.late; }).length;
    const doing = state.tasks.filter(function (t) { return t.status === 'doing'; }).length;
    const todo = state.tasks.filter(function (t) { return t.status === 'todo'; }).length;
    const ideas = state.tasks.filter(function (t) { return t.status === 'idea'; }).length;

    // Anello dell'avanzamento complessivo
    const C = 2 * Math.PI * 54;
    const ring = el('div', { class: 'pj-ring', role: 'img', 'aria-label': 'Avanzamento complessivo ' + pct + '%' });
    ring.innerHTML = '<svg viewBox="0 0 132 132" aria-hidden="true">'
      + '<circle class="pj-ring-track" cx="66" cy="66" r="54" fill="none" stroke-width="12"/>'
      + '<circle class="pj-ring-fill" cx="66" cy="66" r="54" fill="none" stroke-width="12" stroke-linecap="round"'
      + ' stroke-dasharray="' + C.toFixed(1) + '" stroke-dashoffset="' + (C * (1 - pct / 100)).toFixed(1) + '"/></svg>';
    ring.appendChild(el('div', { class: 'pj-ring-label' }, [el('strong', { text: pct + '%' }), el('span', { text: done + ' di ' + work.length + ' fatte' })]));
    const stats = el('div', { class: 'pj-stats' }, [
      stat(doing, 'in corso'), stat(todo, 'da fare'),
      stat(late, late === 1 ? 'in ritardo ⚠' : 'in ritardo ⚠', late > 0), stat(ideas, 'idee in attesa'),
    ]);
    wrap.appendChild(el('section', { class: 'pj-card pj-hero' }, [
      el('h3', {}, ['Avanzamento del progetto']),
      el('div', { class: 'pj-hero-body' }, [ring, stats]),
    ]));

    wrap.appendChild(renderNext());
    wrap.appendChild(renderGoals());
    wrap.appendChild(renderWeekly());
    return wrap;
  }

  function stat(n, label, alert) {
    return el('div', { class: 'pj-stat' + (alert ? ' is-alert' : '') }, [el('b', { text: n }), el('span', { text: label })]);
  }

  function renderGoals() {
    const card = el('section', { class: 'pj-card pj-goals' }, [
      el('h3', {}, ['Obiettivi', el('span', { class: 'pj-h-actions' },
        el('button', { type: 'button', class: 'pj-btn', onclick: function () { editGoal(null); } }, '+ Obiettivo'))]),
    ]);
    if (!state.goals.length) {
      card.appendChild(el('p', { class: 'pj-empty', text: 'Nessun obiettivo ancora. Creane uno (es. «Apertura al pubblico») e collegaci le attività: la barra si riempie da sola man mano che le chiudete.' }));
      return card;
    }
    state.goals.forEach(function (g) {
      const list = committed(state.tasks.filter(function (t) { return t.goal_id === g.id; }));
      const done = list.filter(function (t) { return t.status === 'done'; }).length;
      const pct = list.length ? Math.round(done / list.length * 100) : 0;
      const due = dueLabel(g.due_date);
      const metaText = done + '/' + list.length + ' · ' + pct + '%'
        + (due ? ' · ' + (due.late && pct < 100 ? '⚠ scaduto ' + due.text : 'scadenza ' + due.text) : '');
      card.appendChild(el('div', { class: 'pj-goal', title: g.description || '', onclick: function () { editGoal(g); } }, [
        el('div', { class: 'pj-goal-top' }, [
          el('span', { class: 'pj-goal-title', text: g.title }),
          el('span', { class: 'pj-goal-meta' + (due && due.late && pct < 100 ? ' is-late' : ''), text: metaText }),
        ]),
        el('div', { class: 'pj-bar', role: 'progressbar', 'aria-valuenow': pct, 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': g.title },
          el('i', { style: { width: pct + '%', '--c': g.color } })),
      ]));
    });
    return card;
  }

  /** Attivita' chiuse per settimana, ultime 12: una colonna per settimana, una sola serie. */
  function renderWeekly() {
    const card = el('section', { class: 'pj-card pj-weekly' }, [
      el('h3', {}, ['Attività chiuse per settimana']),
    ]);
    const byWeek = {};
    state.weekly.forEach(function (w) { byWeek[w.week] = w.n; });
    const monday = today();
    monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
    const weeks = [];
    for (let i = 11; i >= 0; i--) {
      const d = new Date(monday); d.setDate(d.getDate() - i * 7);
      weeks.push({ date: d, n: byWeek[ymd(d)] || 0 });
    }
    const max = Math.max(4, ...weeks.map(function (w) { return w.n; }));
    const W = 600, H = 170, padL = 26, padB = 22, padT = 10;
    const bw = (W - padL) / weeks.length;
    let svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none" role="img" aria-label="Attività chiuse nelle ultime 12 settimane">';
    [0, Math.ceil(max / 2), max].forEach(function (v) {
      const y = padT + (H - padT - padB) * (1 - v / max);
      svg += '<line class="grid" x1="' + padL + '" x2="' + W + '" y1="' + y + '" y2="' + y + '"/>'
        + '<text class="axis" x="' + (padL - 6) + '" y="' + (y + 3) + '" text-anchor="end">' + v + '</text>';
    });
    weeks.forEach(function (w, i) {
      const h = w.n ? Math.max(4, (H - padT - padB) * w.n / max) : 3;
      const x = padL + i * bw + bw * 0.18, bwInner = bw * 0.64, y = H - padB - h;
      const tip = 'Settimana del ' + w.date.getDate() + ' ' + MONTHS_SHORT[w.date.getMonth()] + ': ' + w.n + (w.n === 1 ? ' attività chiusa' : ' attività chiuse');
      svg += '<rect class="hit" data-tip="' + esc(tip) + '" data-i="' + i + '" x="' + (padL + i * bw) + '" y="' + padT + '" width="' + bw + '" height="' + (H - padT) + '"/>'
        + '<rect class="bar' + (w.n ? '' : ' is-zero') + '" data-i="' + i + '" x="' + x + '" y="' + y + '" width="' + bwInner + '" height="' + h + '" rx="4"/>';
      if (i % 2 === 1 || weeks.length < 8) {
        svg += '<text class="axis" x="' + (x + bwInner / 2) + '" y="' + (H - 6) + '" text-anchor="middle">' + w.date.getDate() + '/' + (w.date.getMonth() + 1) + '</text>';
      }
    });
    svg += '</svg>';
    const chart = el('div', { class: 'pj-chart', html: svg });
    const tip = el('div', { class: 'pj-tip', hidden: true });
    chart.appendChild(tip);
    chart.addEventListener('pointermove', function (e) {
      const hit = e.target.closest ? e.target.closest('.hit') : null;
      chart.querySelectorAll('.bar.is-hover').forEach(function (b) { b.classList.remove('is-hover'); });
      if (!hit) { tip.hidden = true; return; }
      const bar = chart.querySelector('.bar[data-i="' + hit.dataset.i + '"]');
      if (bar) bar.classList.add('is-hover');
      const r = chart.getBoundingClientRect(), br = (bar || hit).getBoundingClientRect();
      tip.textContent = hit.dataset.tip;
      tip.style.left = (br.left + br.width / 2 - r.left) + 'px';
      tip.style.top = (br.top - r.top - 6) + 'px';
      tip.hidden = false;
    });
    chart.addEventListener('pointerleave', function () { tip.hidden = true; });
    card.appendChild(chart);
    const total = weeks.reduce(function (s, w) { return s + w.n; }, 0);
    card.appendChild(el('p', { class: 'pj-sub', text: total + ' attività chiuse nelle ultime 12 settimane' }));
    return card;
  }

  /** Prossimi appuntamenti e scadenze vicine, in ordine di tempo. */
  function renderNext() {
    const card = el('section', { class: 'pj-card pj-next' }, [
      el('h3', {}, ['In arrivo', el('span', { class: 'pj-h-actions' },
        el('button', { type: 'button', class: 'pj-btn', onclick: function () { editEvent(null, today()); } }, '+ Appuntamento'))]),
    ]);
    const from = now();
    const items = [];
    state.events.forEach(function (ev) {
      const s = parseDate(ev.starts_at), e = parseDate(ev.ends_at) || s;
      const end = ev.all_day ? new Date(e.getFullYear(), e.getMonth(), e.getDate(), 23, 59) : e;
      if (end >= from) items.push({ date: s, kind: 'event', ev: ev });
    });
    state.tasks.forEach(function (t) {
      const d = dueLabel(t.due_date);
      if (t.status !== 'done' && t.status !== 'idea' && d && d.diff <= 7) items.push({ date: parseDate(t.due_date), kind: 'task', t: t, due: d });
    });
    items.sort(function (a, b) { return a.date - b.date; });
    if (!items.length) {
      card.appendChild(el('p', { class: 'pj-empty', text: 'Niente in programma. Fissa un appuntamento o dai una scadenza a un\'attività.' }));
      return card;
    }
    items.slice(0, 6).forEach(function (it) {
      const d = it.date;
      if (it.kind === 'event') {
        const ev = it.ev;
        const diff = daysBetween(today(), new Date(d.getFullYear(), d.getMonth(), d.getDate()));
        const when = (diff === 0 ? 'Oggi' : diff === 1 ? 'Domani' : DOW[(d.getDay() + 6) % 7] + ' ' + d.getDate() + ' ' + MONTHS_SHORT[d.getMonth()])
          + (ev.all_day ? ' · tutto il giorno' : ' · ' + hm(d));
        card.appendChild(el('div', { class: 'pj-agenda-item', onclick: function () { editEvent(ev); } }, [
          el('div', { class: 'pj-date-chip', style: { '--c': ev.color } }, [el('b', { text: d.getDate() }), el('span', { text: MONTHS_SHORT[d.getMonth()] })]),
          el('div', {}, [el('div', { class: 't', text: ev.title }), el('div', { class: 's' }, [el('span', { class: diff <= 1 ? 'pj-when-soon' : '', text: when })])]),
        ]));
      } else {
        const t = it.t;
        card.appendChild(el('div', { class: 'pj-agenda-item', onclick: function () { editTask(t); } }, [
          el('div', { class: 'pj-date-chip', style: { '--c': it.due.late ? 'var(--pj-danger)' : '#f5b942' } }, [el('b', { text: d.getDate() }), el('span', { text: MONTHS_SHORT[d.getMonth()] })]),
          el('div', {}, [el('div', { class: 't', text: '#' + t.id + ' ' + t.title }),
            el('div', { class: 's', text: (it.due.late ? '⚠ scaduta ' : 'Scadenza ') + it.due.text + (t.assignee_id ? ' · ' + adminName(t.assignee_id) : '') })]),
        ]));
      }
    });
    return card;
  }

  // ------------------------------------------------------------------ bacheca

  function renderBoard() {
    const wrap = el('div');
    const who = el('select', { 'aria-label': 'Chi', onchange: function () { state.filter.who = who.value; renderView(); } }, [
      el('option', { value: 'all', text: 'Tutti' }),
      el('option', { value: 'me', text: 'Le mie' }),
      el('option', { value: 'none', text: 'Non assegnate' }),
    ].concat(Array.from(state.admins.values()).filter(function (a) { return a.id !== ME; })
      .map(function (a) { return el('option', { value: String(a.id), text: 'Di ' + a.name }); })));
    who.value = state.filter.who;
    const goalSel = el('select', { 'aria-label': 'Obiettivo', onchange: function () { state.filter.goal = goalSel.value; renderView(); } },
      [el('option', { value: 'all', text: 'Tutti gli obiettivi' }), el('option', { value: 'none', text: 'Senza obiettivo' })]
        .concat(state.goals.map(function (g) { return el('option', { value: String(g.id), text: g.title }); })));
    goalSel.value = state.filter.goal;
    const search = el('input', { type: 'search', placeholder: 'Cerca…', value: state.filter.q, 'aria-label': 'Cerca nelle attività' });
    search.addEventListener('input', function () {
      state.filter.q = search.value;
      clearTimeout(search._t);
      search._t = setTimeout(function () {
        renderView();
        const s = viewBox.querySelector('input[type="search"]');
        if (s) { s.focus(); s.setSelectionRange(s.value.length, s.value.length); }
      }, 250);
    });
    wrap.appendChild(el('div', { class: 'pj-filters' }, [who, goalSel, search,
      el('span', { class: 'pj-sub', text: 'Trascina le schede fra le colonne · clic per aprirle' })]));

    const board = el('div', { class: 'pj-board' });
    const q = state.filter.q.trim().toLowerCase();
    COLUMNS.forEach(function (col) {
      const list = state.tasks.filter(function (t) {
        if (t.status !== col.id) return false;
        const f = state.filter;
        if (f.who === 'me' && t.assignee_id !== ME) return false;
        if (f.who === 'none' && t.assignee_id) return false;
        if (/^\d+$/.test(f.who) && t.assignee_id !== Number(f.who)) return false;
        if (f.goal === 'none' && t.goal_id) return false;
        if (/^\d+$/.test(f.goal) && t.goal_id !== Number(f.goal)) return false;
        if (q && (t.title + ' ' + (t.notes || '') + ' #' + t.id).toLowerCase().indexOf(q) < 0) return false;
        return true;
      }).sort(function (a, b) { return a.sort_order - b.sort_order || a.id - b.id; });
      const body = el('div', { class: 'pj-col-body', dataset: { status: col.id } });
      list.forEach(function (t) { body.appendChild(taskCard(t)); });
      if (!list.length) body.appendChild(el('p', { class: 'pj-empty', text: col.id === 'idea' ? 'Annota qui le idee da valutare.' : 'Vuota' }));
      board.appendChild(el('div', { class: 'pj-col', dataset: { status: col.id } }, [
        el('div', { class: 'pj-col-head' }, [
          el('span', { class: 'dot', style: { '--c': col.color } }), col.label, el('span', { class: 'n', text: list.length }),
          el('button', { type: 'button', class: 'pj-btn icon', title: 'Nuova attività in ' + col.label, 'aria-label': 'Nuova attività in ' + col.label,
            onclick: function () { editTask(null, col.id); } }, '+'),
        ]),
        body,
      ]));
    });
    wrap.appendChild(board);
    return wrap;
  }

  function taskCard(t) {
    const g = goal(t.goal_id);
    const due = dueLabel(t.due_date);
    const meta = el('div', { class: 'pj-task-meta' }, [
      el('span', { class: 'pj-task-id', text: '#' + t.id }),
      g ? el('span', { class: 'pj-chip', title: 'Obiettivo' }, [el('span', { style: { color: g.color }, text: '●' }), g.title]) : null,
      due && t.status !== 'done' ? el('span', { class: 'pj-chip' + (due.late ? ' is-late' : ''), title: 'Scadenza' }, (due.late ? '⚠ ' : '📅 ') + due.text) : null,
      t.priority === 'high' ? el('span', { class: 'pj-chip is-high' }, '▲ Alta') : null,
      t.notes ? el('span', { title: 'Ha delle note', text: '📝' }) : null,
      t.assignee_id ? avatar(t.assignee_id) : null,
    ]);
    const card = el('div', {
      class: 'pj-task', tabindex: 0, role: 'button', dataset: { id: t.id },
      style: { '--gc': g ? g.color : 'transparent' }, 'aria-label': t.title,
      onkeydown: function (e) { if (e.key === 'Enter') editTask(t); },
    }, [el('div', { class: 'pj-task-title', text: t.title }), meta]);
    card.addEventListener('pointerdown', function (e) { startDrag(e, card, t); });
    return card;
  }

  /** Trascinamento con i pointer event: mouse, dito e penna allo stesso modo. */
  function startDrag(e, card, t) {
    if (e.button !== 0) return;
    const sx = e.clientX, sy = e.clientY;
    let ghost = null, placeholder = null, dx = 0, dy = 0, overBody = null;
    card.setPointerCapture(e.pointerId);

    function move(ev) {
      if (!ghost) {
        if (Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) < 6) return;
        state.dragging = true;
        const r = card.getBoundingClientRect();
        dx = sx - r.left; dy = sy - r.top;
        ghost = card.cloneNode(true);
        ghost.classList.add('is-ghost');
        ghost.style.width = r.width + 'px';
        document.body.appendChild(ghost);
        placeholder = el('div', { class: 'pj-placeholder', style: { height: r.height + 'px' } });
        card.parentNode.insertBefore(placeholder, card);
        card.classList.add('is-dragging');
        card.style.display = 'none';
      }
      ghost.style.left = (ev.clientX - dx) + 'px';
      ghost.style.top = (ev.clientY - dy) + 'px';
      const under = document.elementFromPoint(ev.clientX, ev.clientY);
      const col = under && under.closest ? under.closest('.pj-col') : null;
      viewBox.querySelectorAll('.pj-col.is-over').forEach(function (c) { if (c !== col) c.classList.remove('is-over'); });
      if (!col) return;
      col.classList.add('is-over');
      overBody = col.querySelector('.pj-col-body');
      const empty = overBody.querySelector('.pj-empty');
      if (empty) empty.remove();
      const cards = Array.from(overBody.querySelectorAll('.pj-task:not(.is-dragging)'));
      const before = cards.find(function (c) { const r = c.getBoundingClientRect(); return ev.clientY < r.top + r.height / 2; });
      if (before) overBody.insertBefore(placeholder, before); else overBody.appendChild(placeholder);
    }

    function up() {
      card.removeEventListener('pointermove', move);
      card.removeEventListener('pointerup', up);
      card.removeEventListener('pointercancel', up);
      if (!ghost) { editTask(t); return; }
      ghost.remove();
      viewBox.querySelectorAll('.pj-col.is-over').forEach(function (c) { c.classList.remove('is-over'); });
      const body = placeholder.parentNode;
      body.insertBefore(card, placeholder);
      placeholder.remove();
      card.style.display = '';
      card.classList.remove('is-dragging');
      state.dragging = false;
      const status = body.dataset.status;
      const order = Array.from(body.querySelectorAll('.pj-task')).map(function (c) { return c.dataset.id; });
      // Subito in pagina, poi al server (che conferma ricaricando).
      t.status = status;
      order.forEach(function (id, i) { const x = task(id); if (x) x.sort_order = i + 1; });
      act('task_move', { id: t.id, status: status, order: order.join(',') });
    }

    card.addEventListener('pointermove', move);
    card.addEventListener('pointerup', up);
    card.addEventListener('pointercancel', up);
  }

  // ------------------------------------------------------------------ calendario

  function renderCalendar() {
    if (!state.calMonth) { const t = today(); state.calMonth = new Date(t.getFullYear(), t.getMonth(), 1); }
    const m = state.calMonth;
    const wrap = el('div', { class: 'pj-card' });
    wrap.appendChild(el('div', { class: 'pj-cal-head' }, [
      el('button', { type: 'button', class: 'pj-btn icon', 'aria-label': 'Mese precedente', onclick: function () { state.calMonth = new Date(m.getFullYear(), m.getMonth() - 1, 1); renderView(); } }, '‹'),
      el('h3', { text: MONTHS[m.getMonth()] + ' ' + m.getFullYear() }),
      el('button', { type: 'button', class: 'pj-btn icon', 'aria-label': 'Mese successivo', onclick: function () { state.calMonth = new Date(m.getFullYear(), m.getMonth() + 1, 1); renderView(); } }, '›'),
      el('button', { type: 'button', class: 'pj-btn', onclick: function () { state.calMonth = null; renderView(); } }, 'Oggi'),
      el('button', { type: 'button', class: 'pj-btn primary', onclick: function () { editEvent(null, today()); } }, '+ Appuntamento'),
    ]));
    const grid = el('div', { class: 'pj-cal' });
    DOW.forEach(function (d) { grid.appendChild(el('div', { class: 'pj-cal-dow', text: d })); });
    const start = new Date(m.getFullYear(), m.getMonth(), 1);
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
    const t0 = today();
    for (let i = 0; i < 42; i++) {
      const day = new Date(start); day.setDate(start.getDate() + i);
      if (i === 35 && day.getMonth() !== m.getMonth()) break;   // sesta riga solo se serve
      const items = dayItems(day);
      const cell = el('div', {
        class: 'pj-day' + (day.getMonth() !== m.getMonth() ? ' is-out' : '') + (sameDay(day, t0) ? ' is-today' : ''),
        role: 'button', tabindex: 0, 'aria-label': day.getDate() + ' ' + MONTHS[day.getMonth()] + (items.length ? ', ' + items.length + ' voci' : ''),
        onclick: function (e) { if (e.target === cell || e.target.classList.contains('pj-day-n')) editEvent(null, day); },
      }, el('span', { class: 'pj-day-n', text: day.getDate() }));
      items.slice(0, 3).forEach(function (it) { cell.appendChild(it.node); });
      if (items.length > 3) cell.appendChild(el('span', { class: 'pj-ev-more', text: '+' + (items.length - 3) + ' altri' }));
      grid.appendChild(cell);
    }
    wrap.appendChild(grid);
    wrap.appendChild(monthAgenda(m));
    wrap.appendChild(el('div', { class: 'pj-cal-legend' }, [
      el('span', {}, [el('i', { style: { background: '#c04ff0' } }), 'Appuntamento']),
      el('span', {}, [el('i', { style: { border: '1px dashed var(--border-strong)' } }), 'Scadenza di un\'attività']),
      el('span', {}, 'Clic su un giorno per fissare un appuntamento'),
    ]));
    return wrap;
  }

  /** Sotto la griglia: tutto il mese in elenco, giorno per giorno (da telefono i giorni della
   *  griglia mostrano solo dei trattini colorati, qui si legge tutto senza aprirli uno a uno). */
  function monthAgenda(m) {
    const box = el('div', { class: 'pj-month-list' }, el('h4', { text: 'Questo mese' }));
    const last = new Date(m.getFullYear(), m.getMonth() + 1, 0).getDate();
    let any = false;
    for (let d = 1; d <= last; d++) {
      const day = new Date(m.getFullYear(), m.getMonth(), d);
      const items = dayItems(day);
      if (!items.length) continue;
      any = true;
      const t0 = today();
      box.appendChild(el('div', { class: 'pj-month-day' + (day < t0 ? ' is-past' : '') + (sameDay(day, t0) ? ' is-today' : '') }, [
        el('div', { class: 'pj-month-date' }, [el('b', { text: d }), el('span', { text: DOW[(day.getDay() + 6) % 7] })]),
        el('div', { class: 'pj-month-items' }, items.map(function (it) { return it.node; })),
      ]));
    }
    if (!any) box.appendChild(el('p', { class: 'pj-empty', text: 'Niente in programma questo mese.' }));
    return box;
  }

  function dayItems(day) {
    const items = [];
    const dayStart = new Date(day.getFullYear(), day.getMonth(), day.getDate());
    const dayEnd = new Date(dayStart); dayEnd.setDate(dayEnd.getDate() + 1);
    state.events.forEach(function (ev) {
      const s = parseDate(ev.starts_at);
      const e = parseDate(ev.ends_at) || s;
      if (s < dayEnd && e >= dayStart) {
        items.push({ sort: ev.all_day ? 0 : s.getTime(), node: el('div', {
          class: 'pj-ev', style: { '--c': ev.color }, title: ev.title + (ev.all_day ? '' : ' · ' + hm(s)),
          onclick: function (x) { x.stopPropagation(); editEvent(ev); },
        }, (ev.all_day || !sameDay(s, day) ? '' : hm(s) + ' ') + ev.title) });
      }
    });
    state.tasks.forEach(function (t) {
      if (!t.due_date || t.status === 'idea') return;
      const d = parseDate(t.due_date);
      if (!sameDay(d, day)) return;
      const late = t.status !== 'done' && d < today();
      items.push({ sort: 9e15, node: el('div', {
        class: 'pj-ev is-task' + (late ? ' is-late' : ''), title: 'Scadenza: ' + t.title,
        onclick: function (x) { x.stopPropagation(); editTask(t); },
      }, (t.status === 'done' ? '✓ ' : '◷ ') + t.title) });
    });
    return items.sort(function (a, b) { return a.sort - b.sort; });
  }

  // ------------------------------------------------------------------ registro

  function renderLog() {
    const card = el('section', { class: 'pj-card' }, el('h3', {}, 'Registro delle attività'));
    if (!state.activity.length) {
      card.appendChild(el('p', { class: 'pj-empty', text: 'Qui compare chi ha aggiunto, spostato o modificato cosa.' }));
      return card;
    }
    state.activity.forEach(function (a) {
      card.appendChild(el('div', { class: 'pj-log-item' + (a.id > state.activitySeen && a.user_id !== ME ? ' is-new' : '') }, [
        avatar(a.user_id),
        el('span', {}, [a.text, a.task_id && task(a.task_id) ? el('a', { href: '#', style: { marginLeft: '6px' }, onclick: function (e) { e.preventDefault(); editTask(task(a.task_id)); } }, '#' + a.task_id) : null]),
        el('span', { class: 'when', title: a.created_at, text: timeAgo(a.created_at) }),
      ]));
    });
    return card;
  }

  function markActivitySeen() {
    const top = state.activity.length ? state.activity[0].id : 0;
    if (top > state.activitySeen) {
      apiPost('read', { activity_id: top }).catch(function () {});
      setTimeout(function () { state.activitySeen = top; updateUnread(); }, 4000);
    }
  }

  // ------------------------------------------------------------------ finestre di modifica

  function modal(title, fields, onSave, onDelete) {
    const back = el('div', { class: 'pj-modal-back' });
    const form = el('form', { class: 'pj-modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
    form.appendChild(el('h3', { text: title }));
    fields.forEach(function (f) { form.appendChild(f); });
    const actions = el('div', { class: 'pj-modal-actions' }, [
      onDelete ? el('button', { type: 'button', class: 'pj-btn danger', onclick: async function () {
        if (!confirm('Eliminare davvero?')) return;
        close(); await onDelete();
      } }, 'Elimina') : null,
      el('button', { type: 'button', class: 'pj-btn', style: onDelete ? {} : { marginLeft: 'auto' }, onclick: function () { close(); } }, 'Annulla'),
      el('button', { type: 'submit', class: 'pj-btn primary' }, 'Salva'),
    ]);
    form.appendChild(actions);
    form.addEventListener('submit', async function (e) {
      e.preventDefault();
      const data = Object.fromEntries(new FormData(form).entries());
      form.querySelectorAll('input[type="checkbox"]').forEach(function (c) { data[c.name] = c.checked; });
      const ok = await onSave(data);
      if (ok !== false) close();
    });
    function close() { back.remove(); document.removeEventListener('keydown', onKey); }
    function onKey(e) { if (e.key === 'Escape') close(); }
    back.addEventListener('pointerdown', function (e) { if (e.target === back) close(); });
    document.addEventListener('keydown', onKey);
    back.appendChild(form);
    document.body.appendChild(back);
    const first = form.querySelector('input, textarea, select');
    if (first) setTimeout(function () { first.focus(); }, 30);
  }

  function field(label, input) { return el('div', { class: 'pj-field' }, [el('label', { text: label }), input]); }
  function select(name, options, value) {
    const s = el('select', { name: name }, options.map(function (o) { return el('option', { value: o[0], text: o[1] }); }));
    s.value = value === null || value === undefined ? '' : String(value);
    return s;
  }
  function colorField(name, value) {
    const input = el('input', { type: 'color', name: name, value: value });
    const row = el('div', { style: { display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap' } }, [input].concat(SWATCHES.map(function (c) {
      return el('button', { type: 'button', 'aria-label': 'Colore ' + c, style: { width: '22px', height: '22px', borderRadius: '6px', border: '1px solid var(--border)', background: c, cursor: 'pointer' },
        onclick: function () { input.value = c; } });
    })));
    return row;
  }

  function editTask(t, status) {
    const isNew = !t;
    t = t || { title: '', notes: '', status: status || 'todo', priority: 'normal', assignee_id: null, goal_id: null, due_date: null };
    const fields = [
      field('Titolo', el('input', { name: 'title', required: true, maxlength: 160, value: t.title })),
      el('div', { class: 'pj-row' }, [
        field('Colonna', select('status', COLUMNS.map(function (c) { return [c.id, c.label]; }), t.status)),
        field('Priorità', select('priority', Object.keys(PRIORITY).map(function (k) { return [k, PRIORITY[k]]; }), t.priority)),
      ]),
      el('div', { class: 'pj-row' }, [
        field('Chi se ne occupa', select('assignee_id', [['', 'Nessuno']].concat(Array.from(state.admins.values()).map(function (a) { return [String(a.id), a.name + (a.id === ME ? ' (tu)' : '')]; })), t.assignee_id)),
        field('Scadenza', el('input', { type: 'date', name: 'due_date', value: t.due_date || '' })),
      ]),
      field('Obiettivo', select('goal_id', [['', 'Nessuno']].concat(state.goals.map(function (g) { return [String(g.id), g.title]; })), t.goal_id)),
      field('Note', el('textarea', { name: 'notes', maxlength: 5000 }, t.notes || '')),
    ];
    if (!isNew) {
      fields.push(el('p', { class: 'pj-note', text: '#' + t.id + ' · creata da ' + adminName(t.created_by) + ' ' + timeAgo(t.created_at)
        + (t.completed_at ? ' · chiusa ' + timeAgo(t.completed_at) : '') + ' · in chat scrivi #' + t.id + ' per citarla' }));
    }
    modal(isNew ? 'Nuova attività' : 'Attività #' + t.id, fields, async function (d) {
      d.id = isNew ? '' : t.id;
      return (await act('task_save', d)) !== null;
    }, isNew ? null : function () { return act('task_delete', { id: t.id }); });
  }

  function editGoal(g) {
    const isNew = !g;
    g = g || { title: '', description: '', color: '#a3e635', due_date: null };
    modal(isNew ? 'Nuovo obiettivo' : 'Obiettivo', [
      field('Nome', el('input', { name: 'title', required: true, maxlength: 120, value: g.title })),
      el('div', { class: 'pj-row' }, [field('Colore', colorField('color', g.color)), field('Scadenza', el('input', { type: 'date', name: 'due_date', value: g.due_date || '' }))]),
      field('Descrizione', el('textarea', { name: 'description', maxlength: 2000 }, g.description || '')),
      el('p', { class: 'pj-note', text: 'L\'avanzamento si calcola da solo: attività collegate chiuse su quelle totali (le idee non contano).' }),
    ], async function (d) {
      d.id = isNew ? '' : g.id;
      return (await act('goal_save', d)) !== null;
    }, isNew ? null : function () { return act('goal_delete', { id: g.id }); });
  }

  function editEvent(ev, day) {
    const isNew = !ev;
    const s = ev ? parseDate(ev.starts_at) : new Date(day.getFullYear(), day.getMonth(), day.getDate(), 21, 0);
    const e = ev && ev.ends_at ? parseDate(ev.ends_at) : null;
    const allDay = el('input', { type: 'checkbox', name: 'all_day', checked: ev ? !!ev.all_day : false });
    const timeStart = el('input', { type: 'time', name: 'time_start', value: hm(s) });
    const timeEnd = el('input', { type: 'time', name: 'time_end', value: e ? hm(e) : '' });
    function toggleTimes() { timeStart.disabled = timeEnd.disabled = allDay.checked; }
    allDay.addEventListener('change', toggleTimes);
    toggleTimes();
    modal(isNew ? 'Nuovo appuntamento' : 'Appuntamento', [
      field('Titolo', el('input', { name: 'title', required: true, maxlength: 160, value: ev ? ev.title : '', placeholder: 'Es. Riunione su Discord' })),
      el('div', { class: 'pj-row' }, [
        field('Giorno', el('input', { type: 'date', name: 'date', required: true, value: ymd(s) })),
        field('Fino al giorno (facoltativo)', el('input', { type: 'date', name: 'date_end', value: e && !sameDay(e, s) ? ymd(e) : '' })),
      ]),
      el('label', { class: 'pj-check' }, [allDay, 'Tutto il giorno']),
      el('div', { class: 'pj-row' }, [field('Dalle', timeStart), field('Alle (facoltativo)', timeEnd)]),
      field('Colore', colorField('color', ev ? ev.color : '#c04ff0')),
      field('Note', el('textarea', { name: 'notes', maxlength: 3000, placeholder: 'Di cosa si parla, link della chiamata…' }, ev ? ev.notes || '' : '')),
    ], async function (d) {
      const startTime = d.all_day ? '00:00' : (d.time_start || '00:00');
      const endDay = d.date_end || d.date;
      let end = '';
      if (d.all_day) end = d.date_end ? endDay + ' 23:59' : '';
      else if (d.time_end || d.date_end) end = endDay + ' ' + (d.time_end || '23:59');
      const payload = { id: isNew ? '' : ev.id, title: d.title, notes: d.notes, color: d.color, all_day: !!d.all_day,
        starts_at: d.date + ' ' + startTime, ends_at: end };
      return (await act('event_save', payload)) !== null;
    }, isNew ? null : function () { return act('event_delete', { id: ev.id }); });
  }

  // ------------------------------------------------------------------ chat

  let msgsBox, typingBox, pinsBox, chatHead, textarea, replyBar, attBar, composeBox, fileInput, sendBtn, searchInput;

  function buildChat() {
    chatHead = el('div', { class: 'pj-chat-head' });
    pinsBox = el('div', { class: 'pj-pins', hidden: true });
    searchInput = el('input', { type: 'search', placeholder: 'Cerca nei messaggi…', hidden: true,
      style: { margin: '6px 12px 0', padding: '6px 10px', borderRadius: '8px', border: '1px solid var(--border)', background: 'var(--bg-panel-2)', color: 'var(--text)' } });
    searchInput.addEventListener('input', function () { state.search = searchInput.value.trim().toLowerCase(); renderMessages(); });
    msgsBox = el('div', { class: 'pj-msgs', 'aria-live': 'polite' });
    msgsBox.addEventListener('scroll', function () { if (nearBottom()) markRead(); });
    typingBox = el('div', { class: 'pj-typing' });
    replyBar = el('div', { class: 'pj-reply-bar', hidden: true });
    attBar = el('div', { class: 'pj-att-bar', hidden: true });
    textarea = el('textarea', { rows: 1, placeholder: 'Scrivi un messaggio…', maxlength: 4000, 'aria-label': 'Messaggio' });
    fileInput = el('input', { type: 'file', accept: 'image/*', hidden: true, onchange: function () { if (fileInput.files[0]) setAttach(fileInput.files[0]); fileInput.value = ''; } });
    sendBtn = el('button', { type: 'button', class: 'pj-btn primary', title: 'Invia (Invio)', onclick: send }, 'Invia');
    const emojiBtn = el('button', { type: 'button', class: 'pj-btn icon', title: 'Emoji', 'aria-label': 'Inserisci un\'emoji', onclick: function (e) {
      emojiPop(e.currentTarget, function (em) { insertAtCursor(em); });
    } }, '😊');
    composeBox = el('div', { class: 'pj-compose' }, [replyBar, attBar,
      el('div', { class: 'pj-compose-row' }, [
        el('button', { type: 'button', class: 'pj-btn icon', title: 'Allega un\'immagine', 'aria-label': 'Allega un\'immagine', onclick: function () { fileInput.click(); } }, '📎'),
        emojiBtn, textarea, sendBtn, fileInput,
      ]),
      el('div', { class: 'pj-compose-hint', text: 'Invio per mandare · Maiusc+Invio a capo · **grassetto** *corsivo* `codice` · #12 cita un\'attività · incolla o trascina un\'immagine · ↑ modifica l\'ultimo' }),
    ]);

    textarea.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
      else if (e.key === 'Escape') { cancelEdit(); setReply(null); setAttach(null); }
      else if (e.key === 'ArrowUp' && textarea.value === '' && !state.editing) {
        const mine = Array.from(state.messages.values()).filter(function (m) { return m.user === ME && !m.deleted && m.body; }).pop();
        if (mine) { e.preventDefault(); startEdit(mine); }
      }
    });
    let lastTyping = 0;
    textarea.addEventListener('input', function () {
      autosize();
      if (textarea.value && Date.now() - lastTyping > 3000 && !state.editing) {
        lastTyping = Date.now();
        apiPost('typing', {}).catch(function () {});
      }
    });
    textarea.addEventListener('paste', function (e) {
      const item = Array.from(e.clipboardData ? e.clipboardData.items : []).find(function (i) { return i.type.startsWith('image/'); });
      if (item) { e.preventDefault(); setAttach(item.getAsFile()); }
    });
    ['dragenter', 'dragover'].forEach(function (t) { composeBox.addEventListener(t, function (e) { e.preventDefault(); composeBox.classList.add('is-drop'); }); });
    ['dragleave', 'drop'].forEach(function (t) { composeBox.addEventListener(t, function (e) { e.preventDefault(); composeBox.classList.remove('is-drop'); }); });
    composeBox.addEventListener('drop', function (e) {
      const f = Array.from(e.dataTransfer.files || []).find(function (x) { return x.type.startsWith('image/'); });
      if (f) setAttach(f);
    });

    return el('div', { class: 'pj-chat' }, [chatHead, pinsBox, searchInput, msgsBox, typingBox, composeBox]);
  }

  function autosize() {
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(160, textarea.scrollHeight + 2) + 'px';
  }

  function insertAtCursor(text) {
    const s = textarea.selectionStart, e = textarea.selectionEnd;
    textarea.value = textarea.value.slice(0, s) + text + textarea.value.slice(e);
    textarea.selectionStart = textarea.selectionEnd = s + text.length;
    textarea.focus();
    autosize();
  }

  function renderChatHead() {
    const others = Array.from(state.admins.values()).filter(function (a) { return a.id !== ME; });
    chatHead.innerHTML = '';
    const who = el('div', { class: 'who' }, others.slice(0, 3).map(function (a) { return el('img', { class: 'pj-avatar', src: a.avatar, alt: a.name, width: 28, height: 28 }); }));
    chatHead.appendChild(who);
    chatHead.appendChild(el('div', {}, [
      el('div', { class: 'name', text: others.length ? others.map(function (a) { return a.name; }).join(', ') : 'Chat del progetto' }),
      el('div', { class: 'status', id: 'pjStatus' }),
    ]));
    const notifOn = loadPref('notify', '0') === '1' && 'Notification' in window && Notification.permission === 'granted';
    chatHead.appendChild(el('button', { type: 'button', class: 'pj-btn icon', title: 'Cerca nei messaggi', 'aria-label': 'Cerca nei messaggi', onclick: function () {
      searchInput.hidden = !searchInput.hidden;
      if (searchInput.hidden) { searchInput.value = ''; state.search = ''; renderMessages(); } else searchInput.focus();
    } }, '🔍'));
    chatHead.appendChild(el('button', { type: 'button', class: 'pj-btn icon', style: { marginLeft: '4px' },
      title: notifOn ? 'Notifiche attive: clic per spegnerle' : 'Avvisami dei messaggi nuovi quando la pagina è in secondo piano',
      'aria-label': 'Notifiche', onclick: toggleNotify }, notifOn ? '🔔' : '🔕'));
    renderTyping();
  }

  function renderTyping() {
    const names = state.typing.map(adminName);
    typingBox.textContent = names.length ? names.join(', ') + (names.length > 1 ? ' stanno scrivendo…' : ' sta scrivendo…') : '';
    const st = document.getElementById('pjStatus');
    if (st) {
      st.classList.toggle('is-typing', names.length > 0);
      st.textContent = names.length ? 'sta scrivendo…' : 'Solo amministratori';
    }
  }

  function nearBottom() { return msgsBox.scrollHeight - msgsBox.scrollTop - msgsBox.clientHeight < 80; }
  function scrollChatBottom(force) {
    if (force || nearBottom()) requestAnimationFrame(function () { msgsBox.scrollTop = msgsBox.scrollHeight; });
  }

  /** Testo del messaggio -> HTML sicuro: prima si mette in sicurezza, poi si riconoscono i segni. */
  function formatText(body) {
    let s = esc(body);
    const blocks = [];
    s = s.replace(/```([\s\S]*?)```/g, function (_, code) { blocks.push('<pre>' + code.replace(/^\n/, '') + '</pre>'); return '\u0000' + (blocks.length - 1) + '\u0000'; });
    s = s.replace(/`([^`\n]+)`/g, function (_, code) { blocks.push('<code>' + code + '</code>'); return '\u0000' + (blocks.length - 1) + '\u0000'; });
    s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    s = s.replace(/(^|[\s(])_([^_\n]+)_/g, '$1<em>$2</em>');
    s = s.replace(/~~([^~\n]+)~~/g, '<del>$1</del>');
    s = s.replace(/\bhttps?:\/\/[^\s<]+[^\s<.,;:!?)\]'"]/g, function (url) {
      return '<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + url + '</a>';
    });
    s = s.replace(/(^|[\s(])#(\d{1,6})\b/g, function (m, pre, id) {
      const t = task(id);
      return t ? pre + '<span class="tasklink" data-task="' + id + '" title="' + esc(t.title) + '">#' + id + ' ' + esc(t.title) + '</span>' : m;
    });
    state.admins.forEach(function (a) {
      const re = new RegExp('(^|[\\s(])@(' + a.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')\\b', 'gi');
      s = s.replace(re, '$1<span class="mention">@$2</span>');
    });
    return s.replace(/\u0000(\d+)\u0000/g, function (_, i) { return blocks[+i]; });
  }

  function isOnlyEmoji(body) {
    const t = body.trim();
    return t.length > 0 && t.length <= 12 && /^(\p{Extended_Pictographic}|\p{Emoji_Component}|‍|️|\s)+$/u.test(t) && !/\d/.test(t);
  }

  function readByOthers(m) {
    return Array.from(state.admins.keys()).some(function (id) { return id !== ME && (state.read[id] || 0) >= m.id; });
  }

  function renderMessages() {
    if (!msgsBox) return;
    state.chatRendered = true;
    const stick = nearBottom() || !msgsBox.childElementCount;
    const list = Array.from(state.messages.values()).sort(function (a, b) { return a.id - b.id; });
    msgsBox.innerHTML = '';
    let prev = null, prevDay = null;
    const q = state.search;
    list.forEach(function (m) {
      if (q && (m.deleted || (m.body || '').toLowerCase().indexOf(q) < 0)) return;
      const d = parseDate(m.at);
      if (!prevDay || !sameDay(d, prevDay)) {
        const diff = daysBetween(new Date(d.getFullYear(), d.getMonth(), d.getDate()), today());
        msgsBox.appendChild(el('div', { class: 'pj-day-sep', text: diff === 0 ? 'Oggi' : diff === 1 ? 'Ieri' : DOW[(d.getDay() + 6) % 7] + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()] + (d.getFullYear() !== today().getFullYear() ? ' ' + d.getFullYear() : '') }));
        prev = null;
      }
      prevDay = d;
      const cont = prev && prev.user === m.user && (d - parseDate(prev.at)) < 5 * 60000;
      msgsBox.appendChild(messageNode(m, cont));
      prev = m;
    });
    if (!list.length) msgsBox.appendChild(el('p', { class: 'pj-empty', style: { textAlign: 'center', marginTop: '30px' }, text: 'Nessun messaggio ancora. Rompete il ghiaccio 👋' }));
    renderPins(list);
    if (stick) scrollChatBottom(true);
    if (stick) markRead();
  }

  function messageNode(m, cont) {
    const mine = m.user === ME;
    const bubble = el('div', { class: 'pj-bubble' });
    if (!mine && !cont) bubble.appendChild(el('div', { class: 'author', text: adminName(m.user) }));
    if (m.reply_to) {
      const r = state.messages.get(m.reply_to);
      bubble.appendChild(el('div', { class: 'pj-quote', onclick: function () { flashMessage(m.reply_to); } }, [
        el('b', { text: r ? adminName(r.user) : 'Messaggio' }),
        el('span', { text: r ? (r.deleted ? 'messaggio eliminato' : (r.body || '📷 Immagine')) : 'non più disponibile' }),
      ]));
    }
    if (m.deleted) {
      bubble.appendChild(el('div', { class: 'text is-deleted', text: 'Messaggio eliminato' }));
    } else {
      if (m.image) {
        bubble.appendChild(el('img', { class: 'att', src: m.image, alt: 'Immagine allegata', loading: 'lazy',
          onclick: function () { lightbox(m.image); }, onload: function () { if (nearBottom()) scrollChatBottom(true); } }));
      }
      if (m.body) {
        const txt = el('div', { class: 'text' + (isOnlyEmoji(m.body) && !m.image ? ' is-big' : ''), html: formatText(m.body) });
        txt.addEventListener('click', function (e) { const tl = e.target.closest('.tasklink'); if (tl) editTask(task(tl.dataset.task)); });
        bubble.appendChild(txt);
      }
    }
    const reacts = m.reactions || {};
    const rkeys = Object.keys(reacts);
    if (rkeys.length) {
      bubble.appendChild(el('div', { class: 'pj-reacts' }, rkeys.map(function (em) {
        const users = reacts[em];
        return el('button', { type: 'button', class: 'pj-react' + (users.indexOf(ME) >= 0 ? ' is-mine' : ''),
          title: users.map(adminName).join(', '), onclick: function () { react(m, em); } }, em + ' ' + users.length);
      })));
    }
    const d = parseDate(m.at);
    bubble.appendChild(el('div', { class: 'meta' }, [
      m.pinned ? el('span', { title: 'Fissato', text: '📌' }) : null,
      m.edited ? el('span', { text: 'modificato' }) : null,
      el('span', { title: m.at, text: hm(d) }),
      mine && !m.deleted ? el('span', { class: 'ticks' + (readByOthers(m) ? ' is-read' : ''), dataset: { id: m.id },
        title: readByOthers(m) ? 'Letto' : 'Inviato', text: '✓✓' }) : null,
    ]));

    const tools = m.deleted ? null : el('div', { class: 'pj-msg-tools' }, [
      el('button', { type: 'button', title: 'Rispondi', 'aria-label': 'Rispondi', onclick: function () { setReply(m); } }, '↩'),
      el('button', { type: 'button', title: 'Reazione', 'aria-label': 'Reazione', onclick: function (e) { emojiPop(e.currentTarget, function (em) { react(m, em); }, state.reactions); } }, '🙂'),
      el('button', { type: 'button', title: m.pinned ? 'Togli dai fissati' : 'Fissa in alto', 'aria-label': 'Fissa', onclick: function () { chatAct('msg_pin', { id: m.id }); } }, '📌'),
      el('button', { type: 'button', title: 'Crea un\'attività da questo messaggio', 'aria-label': 'Crea attività', onclick: function () { taskFromMessage(m); } }, '➕'),
      mine && m.body ? el('button', { type: 'button', title: 'Modifica', 'aria-label': 'Modifica', onclick: function () { startEdit(m); } }, '✏️') : null,
      mine ? el('button', { type: 'button', title: 'Elimina', 'aria-label': 'Elimina', onclick: function () { if (confirm('Eliminare il messaggio?')) chatAct('msg_delete', { id: m.id }); } }, '🗑') : null,
    ]);
    const node = el('div', { class: 'pj-msg' + (mine ? ' is-mine' : '') + (cont ? ' is-cont' : ''), dataset: { id: m.id } },
      [avatar(m.user), bubble, tools]);
    // Da telefono non c'e' il passaggio del mouse: un tocco sul fumetto mostra gli strumenti.
    bubble.addEventListener('click', function (e) {
      if (e.target.closest('a, img, .tasklink, .pj-react, .pj-quote')) return;
      msgsBox.querySelectorAll('.pj-msg.show-tools').forEach(function (n) { if (n !== node) n.classList.remove('show-tools'); });
      node.classList.toggle('show-tools');
    });
    return node;
  }

  function refreshTicks() {
    msgsBox.querySelectorAll('.ticks').forEach(function (t) {
      const m = state.messages.get(Number(t.dataset.id));
      if (!m) return;
      const read = readByOthers(m);
      t.classList.toggle('is-read', read);
      t.title = read ? 'Letto' : 'Inviato';
    });
  }

  function renderPins(list) {
    const pins = list.filter(function (m) { return m.pinned; });
    pinsBox.hidden = !pins.length;
    if (!pins.length) return;
    const last = pins[pins.length - 1];
    pinsBox.innerHTML = '';
    pinsBox.appendChild(el('b', { text: '📌 ' + (pins.length > 1 ? pins.length + ' fissati' : 'Fissato') }));
    pinsBox.appendChild(el('span', { text: last.body || '📷 Immagine' }));
    pinsBox.onclick = function () {
      const i = pins.indexOf(pins.find(function (p) { return p.id === Number(pinsBox.dataset.next || last.id); }));
      const target = pins[i >= 0 ? i : pins.length - 1];
      flashMessage(target.id);
      pinsBox.dataset.next = pins[(pins.indexOf(target) - 1 + pins.length) % pins.length].id;
    };
  }

  function flashMessage(id) {
    const node = msgsBox.querySelector('.pj-msg[data-id="' + id + '"]');
    if (!node) return;
    node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    node.classList.add('is-flash');
    setTimeout(function () { node.classList.remove('is-flash'); }, 1600);
  }

  function emojiPop(anchor, onPick, list) {
    document.querySelectorAll('.pj-emoji-pop').forEach(function (p) { p.remove(); });
    const choices = list || ['😀', '😂', '😅', '😍', '🤔', '😎', '😢', '😡', '👍', '👎', '🙏', '👏', '🎉', '🔥', '❤️', '✅', '❌', '⚠️', '💡', '🚀', '👀', '💪'];
    const pop = el('div', { class: 'pj-emoji-pop', style: list ? {} : { flexWrap: 'wrap', width: '252px', borderRadius: '12px' } }, choices.map(function (em) {
      return el('button', { type: 'button', onclick: function () { pop.remove(); onPick(em); } }, em);
    }));
    document.body.appendChild(pop);
    const r = anchor.getBoundingClientRect();
    const pw = pop.offsetWidth, ph = pop.offsetHeight;
    pop.style.left = Math.max(8, Math.min(window.innerWidth - pw - 8, r.left + r.width / 2 - pw / 2)) + window.pageXOffset + 'px';
    pop.style.top = (r.top - ph - 6 < 8 ? r.bottom + 6 : r.top - ph - 6) + window.pageYOffset + 'px';
    setTimeout(function () {
      document.addEventListener('pointerdown', function close(e) {
        if (!pop.contains(e.target)) { pop.remove(); document.removeEventListener('pointerdown', close); }
      });
    }, 0);
  }

  function lightbox(src) {
    const box = el('div', { class: 'pj-lightbox', onclick: function () { box.remove(); } }, el('img', { src: src, alt: '' }));
    document.body.appendChild(box);
  }

  function setReply(m) {
    state.reply = m;
    replyBar.innerHTML = '';
    replyBar.hidden = !m;
    if (!m) return;
    replyBar.appendChild(el('b', { text: '↩ ' + adminName(m.user) }));
    replyBar.appendChild(el('span', { text: m.body || '📷 Immagine' }));
    replyBar.appendChild(el('button', { type: 'button', 'aria-label': 'Annulla risposta', onclick: function () { setReply(null); } }, '✕'));
    textarea.focus();
  }

  function setAttach(file) {
    state.attach = file;
    attBar.innerHTML = '';
    attBar.hidden = !file;
    if (!file) return;
    const url = URL.createObjectURL(file);
    attBar.appendChild(el('img', { src: url, alt: 'Anteprima' }));
    attBar.appendChild(el('span', { text: (file.name || 'Immagine incollata') + ' · ' + Math.round(file.size / 1024) + ' KB' }));
    attBar.appendChild(el('button', { type: 'button', 'aria-label': 'Togli immagine', onclick: function () { setAttach(null); } }, '✕'));
    textarea.focus();
  }

  function startEdit(m) {
    state.editing = m;
    setReply(null);
    textarea.value = m.body;
    sendBtn.textContent = 'Salva';
    textarea.focus();
    autosize();
  }
  function cancelEdit() {
    if (!state.editing) return;
    state.editing = null;
    textarea.value = '';
    sendBtn.textContent = 'Invia';
    autosize();
  }

  let sending = false;
  async function send() {
    if (sending) return;
    const body = textarea.value.trim();
    if (state.editing) {
      if (!body) return;
      const id = state.editing.id;
      cancelEdit();
      await chatAct('msg_edit', { id: id, body: body });
      return;
    }
    if (!body && !state.attach) return;
    sending = true;
    sendBtn.disabled = true;
    try {
      await apiPost('msg_send', { body: body, reply_to: state.reply ? state.reply.id : '' }, state.attach);
      textarea.value = '';
      autosize();
      setReply(null);
      setAttach(null);
      await poll();
      scrollChatBottom(true);
    } catch (e) {
      toast(e.message);
    } finally {
      sending = false;
      sendBtn.disabled = false;
      textarea.focus();
    }
  }

  async function chatAct(action, fields) {
    try { await apiPost(action, fields); await poll(); } catch (e) { toast(e.message); }
  }
  function react(m, em) { chatAct('msg_react', { id: m.id, emoji: em }); }

  function taskFromMessage(m) {
    editTask(null, 'todo');
    const form = document.querySelector('.pj-modal');
    if (!form) return;
    const title = (m.body || 'Da un messaggio in chat').split('\n')[0].slice(0, 160);
    form.querySelector('[name="title"]').value = title;
    form.querySelector('[name="notes"]').value = 'Dalla chat, ' + adminName(m.user) + ' ' + m.at.slice(0, 16) + ':\n' + (m.body || '') + (m.image ? '\n' + location.origin + m.image : '');
  }

  // ------------------------------------------------------------------ letture e avvisi

  function unreadCount() {
    const mine = state.read[ME] || 0;
    let n = 0;
    state.messages.forEach(function (m) { if (m.user !== ME && !m.deleted && m.id > mine) n++; });
    return n;
  }

  function chatVisible() {
    return !document.hidden && (state.view === 'chat' || window.matchMedia('(min-width: 1101px)').matches);
  }

  let readPending = false;
  function markRead() {
    if (!chatVisible() || !nearBottom() || readPending) return;
    const mine = state.read[ME] || 0;
    if (state.lastMsg <= mine) return;
    readPending = true;
    const upto = state.lastMsg;
    state.read[ME] = upto;
    apiPost('read', { message_id: upto }).catch(function () {}).finally(function () { readPending = false; });
    updateUnread();
  }

  const baseTitle = document.title;
  function updateUnread() {
    const n = unreadCount();
    const newLog = state.activity.filter(function (a) { return a.id > state.activitySeen && a.user_id !== ME; }).length;
    if (tabsBox) {
      tabsBox.querySelectorAll('.pj-tab').forEach(function (t) {
        const c = t.querySelector('.pj-count');
        const v = t.dataset.view === 'chat' ? n : t.dataset.view === 'log' ? newLog : 0;
        c.hidden = !v;
        c.textContent = v;
      });
    }
    document.title = (n ? '(' + n + ') ' : '') + baseTitle;
    const badge = document.getElementById('progettoBadge');
    if (badge) { badge.textContent = n; badge.hidden = !n; }
  }

  function toggleNotify() {
    if (!('Notification' in window)) { toast('Questo browser non supporta le notifiche.'); return; }
    if (loadPref('notify', '0') === '1') { savePref('notify', '0'); renderChatHead(); return; }
    Notification.requestPermission().then(function (p) {
      if (p === 'granted') savePref('notify', '1');
      else toast('Notifiche bloccate dal browser: abilitale dalle impostazioni del sito.');
      renderChatHead();
    });
  }

  function notifyNew(msgs) {
    if (!document.hidden) return;
    if (loadPref('notify', '0') !== '1' || !('Notification' in window) || Notification.permission !== 'granted') return;
    const m = msgs[msgs.length - 1];
    try {
      const n = new Notification(adminName(m.user) + ' · Progetto', { body: m.body || '📷 Immagine', icon: (admin(m.user) || {}).avatar, tag: 'pj-chat' });
      n.onclick = function () { window.focus(); setView('chat'); n.close(); };
    } catch (e) { /* alcuni telefoni vogliono il service worker: pazienza */ }
  }

  // Tasto rapido: "/" porta nella chat da qualunque punto della pagina.
  document.addEventListener('keydown', function (e) {
    if (e.key === '/' && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && !document.querySelector('.pj-modal-back')) {
      e.preventDefault();
      if (!window.matchMedia('(min-width: 1101px)').matches) setView('chat');
      textarea.focus();
    }
  });

  // ------------------------------------------------------------------ avvio

  loadState().then(function () {
    scrollChatBottom(true);
    pollTimer = setTimeout(poll, 3000);
  }).catch(function (e) {
    root.innerHTML = '';
    root.appendChild(el('div', { class: 'alert alert-error' }, 'Lo spazio di lavoro non si è caricato: ' + e.message
      + '. Se è la prima volta, va applicata la migrazione 2026-09-29-progetto-condiviso.sql.'));
  });
})();
