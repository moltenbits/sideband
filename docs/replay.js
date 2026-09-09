// Replay of a Sideband exchange. The script is a list of steps; each step
// appends one line to a pane or one entry to the ledger and then waits.
// render(T) draws the state at virtual time T, so the scrubber can move
// forwards or backwards and playback is just T advancing.
(function () {
  'use strict';

  var panes = {
    C: document.getElementById('pane-claude'),
    X: document.getElementById('pane-codex'),
    J: document.getElementById('ledger')
  };
  var track = document.getElementById('replay-track');
  var bar = document.getElementById('progress-bar');
  var chapterEls = Array.prototype.slice.call(document.getElementById('chapters').children);
  var copyBtn = document.getElementById('copy');

  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var text = document.getElementById('install-cmd').textContent;
      if (!navigator.clipboard) return;
      navigator.clipboard.writeText(text).then(function () {
        copyBtn.textContent = 'Copied';
        setTimeout(function () { copyBtn.textContent = 'Copy'; }, 1400);
      });
    });
  }

  var TYPE_MS = 22;   // per character while a human types
  var LINE_MS = 90;   // per line while an agent prints

  // [pane, kind, content, wait-after-ms]
  //   kind 'h'   a line the human typed, shown character by character
  //   kind 'l'   a line the client printed
  //   kind 'a'   an entry arriving from the other agent (rendered as a block)
  //   kind 'g'   a blank line
  //   kind 'ch'  chapter marker (content is the chapter index)
  //   pane 'J'   a ledger entry: [position, from, to, type, summary]
  var SCRIPT = [
    ['ch', 0],
    ['C', 'h', '/sideband', 500, 'join'],
    ['C', 'l', '<span class="c">⏺</span> Bash(sideband join --resume)', 300],
    ['C', 'l', '  <span class="dim">⎿</span>  {"session":{"resumed":true},"open":[],"in_progress":[],"updates":[],"outgoing":[]}', 500],
    ['C', 'l', '<span class="c">⏺</span> Joined as Claude. Nothing is waiting; entries for Claude are pushed straight into this session.', 900],
    ['J', ['join', 'operator', 'both', 'session', 'Claude Code and Codex each join from their own session.'], 400],
    ['X', 'h', '$sideband', 500, 'join'],
    ['X', 'l', '<span class="x">•</span> Ran <b>sideband join --resume</b>', 400],
    ['X', 'l', '<span class="x">•</span> Joined as Codex. This thread is recorded as the delivery address; nothing is pending.', 1200],

    ['ch', 1],
    ['C', 'g', '', 0],
    ['C', 'h', 'Fix #42, the upload retry loop. Keep the commits small and ask Codex to review each one before you start the next; address what it finds before moving on.', 500, '12'],
    ['J', ['12', 'operator', 'claude', 'request', 'Fix #42, the upload retry loop. Keep the commits small…'], 700],
    ['C', 'l', '<span class="c">⏺</span> The loop retries every failure, 4xx included. First commit: retry only transient errors.', 600],
    ['C', 'l', '<span class="c">⏺</span> Bash(git commit -am "Retry uploads only on transient errors")', 300],
    ['C', 'l', '  <span class="dim">⎿</span>  [main 4f1c2e0] Retry uploads only on transient errors', 500],
    ['C', 'l', '<span class="c">⏺</span> Bash(sideband append --to codex --type request --caused-by 9286e79b --body-file review.md)', 300, '13'],
    ['C', 'l', '  <span class="dim">⎿</span>  "pushes":[{"role":"codex","outcome":"pushed"}]', 400],
    ['J', ['13', 'claude', 'codex', 'request', 'Review 4f1c2e0: retry uploads only on transient errors.'], 300],
    ['C', 'l', '<span class="c">⏺</span> Asked Codex to review 4f1c2e0. Its reply will be pushed here.', 900],

    ['ch', 2],
    ['X', 'g', '', 0],
    ['X', 'a', '<span class="dim">[Sideband message] from Claude · request 16a4e14e · expects a reply</span>\nReview 4f1c2e0 on main: the upload retry loop now retries only transient errors (network failures and 5xx). Findings before I start the next commit, please.', 900, '13'],
    ['X', 'l', '<span class="x">•</span> Ran <b>sideband append --type ack --reply-to 16a4e14e</b>', 300, '14'],
    ['J', ['14', 'codex', 'claude', 'ack', ''], 500],
    ['X', 'l', '<span class="x">•</span> One finding in 4f1c2e0. The transient check is <b>status >= 500</b>, so 429 is treated as permanent and a rate-limited upload fails on the first try. The new test covers 503 only.', 800],
    ['X', 'l', '<span class="x">•</span> Ran <b>sideband append --type reply --reply-to 16a4e14e --body-file reply.md</b>', 300, '15'],
    ['J', ['15', 'codex', 'claude', 'reply', 'One finding: 429 is excluded by the status >= 500 check.'], 900],

    ['ch', 3],
    ['C', 'g', '', 0],
    ['C', 'a', '<span class="dim">[Sideband message] from Codex · reply to 16a4e14e</span>\nOne finding in 4f1c2e0. The transient check is status >= 500, so 429 is treated as permanent and a rate-limited upload fails on the first try. The new test covers 503 only.', 800, '15'],
    ['C', 'l', '<span class="c">⏺</span> Bash(git commit -am "Treat 429 as transient")', 300],
    ['C', 'l', '  <span class="dim">⎿</span>  [main 8d02b7a] Treat 429 as transient', 400],
    ['C', 'l', '<span class="c">⏺</span> Bash(sideband append --to codex --type request --caused-by a30e81c0 --body-file review.md)', 300, '16'],
    ['J', ['16', 'claude', 'codex', 'request', 'Review 8d02b7a: treat 429 as transient.'], 600],
    ['X', 'a', '<span class="dim">[Sideband message] from Claude · request 2c9d41f7 · expects a reply</span>\nReview 8d02b7a: 429 now counts as transient, with a test for it.', 700, '16'],
    ['X', 'l', '<span class="x">•</span> Ran <b>sideband append --type ack --reply-to 2c9d41f7</b>', 300, '17'],
    ['J', ['17', 'codex', 'claude', 'ack', ''], 400],
    ['X', 'l', '<span class="x">•</span> No findings in 8d02b7a. The check now reads status >= 500 || status === 429, and the test exercises both.', 500],
    ['X', 'l', '<span class="x">•</span> Ran <b>sideband append --type reply --reply-to 2c9d41f7 --body-file reply.md</b>', 300, '18'],
    ['J', ['18', 'codex', 'claude', 'reply', 'No findings in 8d02b7a.'], 700],
    ['C', 'a', '<span class="dim">[Sideband message] from Codex · reply to 2c9d41f7</span>\nNo findings in 8d02b7a. The check now reads status >= 500 || status === 429, and the test exercises both.', 700, '18'],
    ['C', 'l', '<span class="c">⏺</span> Bash(sideband append --to operator --type reply --reply-to 9286e79b --body-file done.md)', 300, '19'],
    ['J', ['19', 'claude', 'operator', 'reply', 'Fixed #42 in two commits, each reviewed by Codex.'], 400],
    ['C', 'l', '<span class="c">⏺</span> Fixed #42 in two commits, each reviewed by Codex before the next: 4f1c2e0 retries only transient errors, and 8d02b7a treats 429 as transient after Codex caught that it was excluded.', 2500]
  ];

  // Lay the steps out on a timeline.
  var steps = [];
  var t = 0;
  var chapterStarts = [];
  SCRIPT.forEach(function (s) {
    if (s[0] === 'ch') { chapterStarts[s[1]] = t; return; }
    var pane = s[0], kind = s[1], content = s[2], wait = s[3] || 0;
    if (pane === 'J') { kind = 'j'; content = s[1]; wait = s[2] || 0; }
    var dur = 0;
    if (pane === 'J') dur = 250;
    else if (kind === 'h') dur = plain(content).length * TYPE_MS;
    else if (kind === 'g') dur = 0;
    else dur = LINE_MS;
    var entry = pane === 'J' ? content[0] : s[4] || null;
    var from = pane === 'J' ? content[1] : null;
    steps.push({ pane: pane, kind: kind, content: content, start: t, dur: dur, entry: entry, from: from });
    t += dur + wait;
  });
  var TOTAL = t;

  function plain(html) {
    var d = document.createElement('div');
    d.innerHTML = html;
    return d.textContent;
  }

  function ledgerEntry(e) {
    var pos = e[0], from = e[1], to = e[2], type = e[3], sum = e[4];
    var div = document.createElement('div');
    div.className = 'entry';
    div.setAttribute('data-entry', pos);
    div.setAttribute('data-from', from);
    var whoHtml = type === 'session'
      ? '<span class="who"><span class="pos">' + pos + '</span>  ' + tag('claude') + ' · ' + tag('codex') + '  <span class="kind">' + type + '</span></span>'
      : '<span class="who"><span class="pos">' + pos + '</span>  ' + tag(from) + ' → ' + tag(to) + '  <span class="kind">' + type + '</span></span>';
    div.innerHTML = whoHtml + (sum ? '<span class="sum">' + sum + '</span>' : '');
    return div;
  }
  function tag(who) {
    if (who === 'claude') return '<span class="c">claude</span>';
    if (who === 'codex') return '<span class="x">codex</span>';
    return '<span>' + who + '</span>';
  }

  var lastKey = null;
  function render(T) {
    var key = [];
    var frag = { C: [], X: [], J: [] };
    var typing = null;
    steps.forEach(function (s, i) {
      if (s.start > T) return;
      if (s.pane === 'J') {
        frag.J.push(ledgerEntry(s.content));
        key.push(i);
        return;
      }
      var p = document.createElement('p');
      p.className = 'line';
      if (s.entry) p.setAttribute('data-entry', s.entry);
      if (s.kind === 'g') { p.className += ' gap'; }
      else if (s.kind === 'h') {
        p.className += ' human';
        var text = plain(s.content);
        var n = T >= s.start + s.dur ? text.length : Math.floor((T - s.start) / TYPE_MS);
        p.textContent = text.slice(0, n);
        if (n < text.length) { p.className += ' cursor'; typing = i + ':' + n; }
      } else if (s.kind === 'a') {
        p.className += ' arrive';
        p.innerHTML = s.content;
      } else {
        p.innerHTML = s.content;
      }
      if (s.entry && !(s.kind === 'h' && typing)) {
        var tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = s.entry;
        if (s.pane === 'X') p.insertBefore(tag, p.firstChild); else p.appendChild(tag);
      }
      frag[s.pane].push(p);
      key.push(i);
    });
    var k = key.join(',') + '|' + typing;
    if (k === lastKey) return;
    lastKey = k;
    ['C', 'X'].forEach(function (id) {
      var el = panes[id];
      el.innerHTML = '';
      frag[id].forEach(function (n) { el.appendChild(n); });
      if (!frag[id].length) { var idle = document.createElement('p'); idle.className = 'line human cursor'; el.appendChild(idle); }
    });
    var title = panes.J.querySelector('.title');
    panes.J.innerHTML = '';
    panes.J.appendChild(title);
    frag.J.forEach(function (n) { panes.J.appendChild(n); });
    var ch = 0;
    chapterStarts.forEach(function (start, i) { if (T >= start) ch = i; });
    chapterEls.forEach(function (el, i) { el.className = i === ch ? 'on' : ''; });
    drawLinks();
  }

  // Connectors: for every journal entry on screen, a line to the terminal
  // line it was written from or delivered to, redrawn from live geometry
  // on every render so they follow the content as it scrolls.
  var stageEl = document.querySelector('.stage');
  var svg = document.getElementById('links');
  var colors = { claude: '#B5711C', codex: '#1F8C82', operator: '#4A5867' };
  function visibleIn(rect, box) {
    return rect.top >= box.top - 1 && rect.bottom <= box.bottom + 1;
  }
  function drawLinks() {
    if (!svg || !window.matchMedia('(min-width: 901px)').matches) return;
    var sr = stageEl.getBoundingClientRect();
    svg.setAttribute('viewBox', '0 0 ' + sr.width + ' ' + sr.height);
    var d = [];
    var entries = panes.J.querySelectorAll('.entry');
    var ledgerBox = panes.J.getBoundingClientRect();
    var scrollC = panes.C.getBoundingClientRect(), scrollX = panes.X.getBoundingClientRect();
    var newest = entries.length ? entries[entries.length - 1].getAttribute('data-entry') : null;
    entries.forEach(function (en) {
      var id = en.getAttribute('data-entry');
      var er = en.getBoundingClientRect();
      if (!visibleIn(er, ledgerBox)) return;
      var color = colors[en.getAttribute('data-from')] || colors.operator;
      var strong = id === newest;
      en.classList.toggle('now', strong);
      [['C', scrollC], ['X', scrollX]].forEach(function (side) {
        var line = panes[side[0]].querySelector('.line[data-entry="' + id + '"]');
        if (!line) return;
        var lr = line.getBoundingClientRect();
        line.classList.toggle('now', strong);
        if (!visibleIn(lr, side[1])) return;
        var tag = line.querySelector('.tag');
        var tr = tag ? tag.getBoundingClientRect() : lr;
        var y1 = tr.top + tr.height / 2 - sr.top;
        var y2 = er.top + er.height / 2 - sr.top;
        var x1 = (side[0] === 'C' ? tr.right : tr.left) - sr.left;
        var x2 = (side[0] === 'C' ? er.left : er.right) - sr.left;
        var mx = (x1 + x2) / 2;
        d.push('<path d="M' + x1 + ',' + y1 + ' C' + mx + ',' + y1 + ' ' + mx + ',' + y2 + ' ' + x2 + ',' + y2 +
          '" stroke="' + color + '" stroke-width="' + (strong ? 1.6 : 1) + '" opacity="' + (strong ? 0.95 : 0.55) + '" fill="none"/>');
      });
    });
    svg.innerHTML = d.join('');
  }
  window.addEventListener('resize', function () { lastKey = null; });

  // Scroll drives the replay: the stage stays pinned while the track
  // scrolls past, and the fraction scrolled is the fraction played, so
  // scrolling back up rewinds.
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var pinned = window.matchMedia && window.matchMedia('(min-width: 901px)').matches;
  // The stage pins at its sticky offset and stays pinned until the track
  // runs out, so the pinned span is the play span.
  var stage = document.getElementById('replay');
  function progress() {
    var rect = track.getBoundingClientRect();
    var top = parseFloat(getComputedStyle(stage).top) || 0;
    var range = track.offsetHeight - stage.offsetHeight;
    if (range <= 0) return 1;
    return Math.min(1, Math.max(0, (top - rect.top) / range));
  }
  function show(p) {
    render(p * TOTAL);
    bar.style.width = (p * 100) + '%';
  }
  if (reduced || !pinned || location.hash === '#end') {
    show(1);
  } else {
    var ticking = false;
    function onScroll() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(function () { show(progress()); ticking = false; });
    }
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    show(progress());
  }
})();
