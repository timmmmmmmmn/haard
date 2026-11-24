/* Haard — de lucht achter de klok: het weer van nu (zon, wolken, regen, sneeuw, mist, nacht met sterren),
   de tijd van de dag (dag, schemer, nacht) en het moment van het jaar (Sinterklaas, kerst, oud en nieuw, Pasen …).
   Alles op één canvas, hooguit 30 beelden per seconde; staat stil in de nachtstand of als het scherm uit is.
   In een gewone browser: ?weer=rainy&t=2026-12-20T19:00 om te proberen. */
(function () {
  var zoek = {};
  location.search.replace(/[?&]([^=&]+)=([^&]*)/g, function (_, k, v) { zoek[k] = decodeURIComponent(v); });
  var demoStart = zoek.t ? new Date(zoek.t).getTime() : 0, echtStart = Date.now();

  /** De tijd van nu; in de browser vast te zetten met ?t= (de seconden lopen dan gewoon door). */
  function nu() { return demoStart ? new Date(demoStart + Date.now() - echtStart) : new Date(); }

  // ---------- Feestdagen en seizoenen ----------
  function dag(j, m, d) { return new Date(j, m - 1, d); }
  function pasen(j) { // Anonieme Gregoriaanse berekening
    var a = j % 19, b = Math.floor(j / 100), c = j % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25),
      g = Math.floor((b - f + 1) / 3), h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4,
      l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451), maand = Math.floor((h + l - 7 * m + 114) / 31);
    return dag(j, maand, ((h + l - 7 * m + 114) % 31) + 1);
  }
  function nachtjes(van, tot) { return Math.round((tot - dag(van.getFullYear(), van.getMonth() + 1, van.getDate())) / 864e5); }
  function aftellen(d, doel, wat, vandaag) {
    var n = nachtjes(d, doel);
    return n <= 0 ? vandaag : n === 1 ? 'Nog één nachtje tot ' + wat : 'Nog ' + n + ' nachtjes tot ' + wat;
  }

  /** {soort, tekst} voor vandaag, of een seizoen zonder tekst. */
  function feest(d) {
    var j = d.getFullYear(), m = d.getMonth() + 1, t = d.getDate(), md = m * 100 + t, p = pasen(j);
    var kd = dag(j, 4, 27); if (kd.getDay() === 0) kd = dag(j, 4, 26);
    var totPasen = nachtjes(d, p);
    if (md >= 1114 && md <= 1205) return { soort: 'sinterklaas', tekst: aftellen(d, dag(j, 12, 5), 'pakjesavond', 'Het is pakjesavond!') };
    if (md >= 1206 && md <= 1226) return { soort: 'kerst', tekst: md >= 1225 ? 'Vrolijk kerstfeest!' : aftellen(d, dag(j, 12, 25), 'kerst', '') };
    if (md >= 1227 && md <= 1231) return { soort: md === 1231 && d.getHours() >= 18 ? 'vuurwerk' : 'oudjaar',
      tekst: md === 1231 ? 'Oudejaarsavond' : aftellen(d, dag(j + 1, 1, 1), 'het nieuwe jaar', '') };
    if (md >= 101 && md <= 106) return { soort: md === 101 ? 'vuurwerk' : 'nieuwjaar', tekst: 'Gelukkig ' + j + '!' };
    if (md >= 213 && md <= 214) return { soort: 'valentijn', tekst: md === 214 ? 'Fijne Valentijnsdag' : '' };
    if (totPasen <= 6 && totPasen >= -1) return { soort: 'pasen', tekst: totPasen <= 0 ? 'Vrolijk Pasen!' : aftellen(d, p, 'Pasen', '') };
    if (nachtjes(d, kd) >= 0 && nachtjes(d, kd) <= 2) return { soort: 'koningsdag', tekst: aftellen(d, kd, 'Koningsdag', 'Fijne Koningsdag!') };
    if (md >= 1024 && md <= 1031) return { soort: 'halloween', tekst: aftellen(d, dag(j, 10, 31), 'Halloween', 'Fijne Halloween') };
    if (md >= 321 && md < 621) return { soort: 'lente', tekst: '' };
    if (md >= 923 && md < 1114) return { soort: 'herfst', tekst: '' };
    return { soort: '', tekst: '' };
  }

  // ---------- Weer en tijd van de dag ----------
  var GROEP = { sunny: 'helder', 'clear-night': 'helder', partlycloudy: 'half', windy: 'half', 'windy-variant': 'half', exceptional: 'half',
    cloudy: 'bewolkt', rainy: 'regen', pouring: 'regen', hail: 'regen', lightning: 'onweer', 'lightning-rainy': 'onweer',
    snowy: 'sneeuw', 'snowy-rainy': 'sneeuw', fog: 'mist' };
  var KLEUR = {
    dag: { helder: ['#2f7fd8', '#6aaef0', '#b6dbfa'], half: ['#4580c0', '#86afd8', '#c4d9ed'], bewolkt: ['#5f6f82', '#8b99aa', '#b4bfca'],
      regen: ['#323c48', '#4c5765', '#66717e'], onweer: ['#22263a', '#363a4e', '#4a4c60'], sneeuw: ['#8796a8', '#b4bfcc', '#dde3ea'], mist: ['#8f99a3', '#b1b8bf', '#cbd0d5'] },
    schemer: { helder: ['#27346e', '#b8527a', '#f5a05a'], half: ['#33406e', '#a05a7c', '#e39a68'], bewolkt: ['#3f4260', '#7d6475', '#ad877e'],
      regen: ['#2e3142', '#4b4858', '#625a66'], onweer: ['#1f2030', '#383446', '#4c4454'], sneeuw: ['#5d6584', '#9c98ac', '#cdbfc4'], mist: ['#5c6070', '#8e8c96', '#b1aaae'] },
    nacht: { helder: ['#050918', '#0d1636', '#1b2754'], half: ['#080c1c', '#121a34', '#212b4b'], bewolkt: ['#0e1118', '#181d27', '#232934'],
      regen: ['#0a0d13', '#141922', '#1d232d'], onweer: ['#08090f', '#12131c', '#1c1d29'], sneeuw: ['#131826', '#232a3a', '#343c4e'], mist: ['#12151b', '#1d2128', '#292d34'] }
  };

  /** Dag, schemer of nacht, met een benadering van zon op en zon onder voor midden-Nederland. */
  function dagdeel(d) {
    var start = new Date(d.getFullYear(), 0, 1), doy = (d - start) / 864e5;
    var lengte = 12.2 + 4.5 * Math.sin(2 * Math.PI * (doy - 80) / 365);
    var zomertijd = d.getTimezoneOffset() < new Date(d.getFullYear(), 0, 1).getTimezoneOffset();
    var middag = 12.67 + (zomertijd ? 1 : 0), uur = d.getHours() + d.getMinutes() / 60;
    var op = middag - lengte / 2, onder = middag + lengte / 2;
    if (uur < op - .6 || uur > onder + .7) return 'nacht';
    if (uur < op + .7 || uur > onder - .6) return 'schemer';
    return 'dag';
  }

  // ---------- Tekenen ----------
  var lucht = document.getElementById('lucht'), doek = document.getElementById('doek'), cx = doek.getContext('2d');
  var B = 0, H = 0, staat = { weer: zoek.weer || 'partlycloudy', deel: '', feest: { soort: '' } };
  var deeltjes = [], wolken = [], sterren = [], vuurwerk = [], volgendePijl = 0;
  var wolkSprite = {};

  function maat() {
    var r = Math.min(window.devicePixelRatio || 1, 1.5);
    B = lucht.clientWidth; H = lucht.clientHeight;
    doek.width = Math.round(B * r); doek.height = Math.round(H * r);
    cx.setTransform(r, 0, 0, r, 0, 0);
    opbouwen();
  }

  /** Een zachte wolk, één keer getekend en daarna alleen verschoven. */
  function sprite(kleur) {
    if (wolkSprite[kleur]) return wolkSprite[kleur];
    var c = document.createElement('canvas'); c.width = 360; c.height = 200;
    var g = c.getContext('2d');
    [[90, 95, 55], [150, 70, 70], [215, 80, 60], [265, 100, 45], [180, 105, 60], [120, 110, 45]].forEach(function (b) {
      var rg = g.createRadialGradient(b[0], b[1], 0, b[0], b[1], b[2]);
      rg.addColorStop(0, kleur); rg.addColorStop(.6, kleur.replace(/[\d.]+\)$/, '0.55)')); rg.addColorStop(1, kleur.replace(/[\d.]+\)$/, '0)'));
      g.fillStyle = rg; g.beginPath(); g.arc(b[0], b[1], b[2], 0, 7); g.fill();
    });
    return (wolkSprite[kleur] = c);
  }

  function willekeur(a, b) { return a + Math.random() * (b - a); }

  function opbouwen() {
    var groep = GROEP[staat.weer] || 'half', deel = staat.deel, s = staat.feest.soort;
    deeltjes = []; wolken = []; sterren = []; vuurwerk = [];
    // Wolken
    var aantal = { helder: 0, half: 3, bewolkt: 6, regen: 7, onweer: 7, sneeuw: 5, mist: 0 }[groep];
    var tint = deel === 'nacht' ? 'rgba(70,80,100,0.5)' : groep === 'regen' || groep === 'onweer' ? 'rgba(60,68,80,0.75)'
      : groep === 'bewolkt' || groep === 'sneeuw' ? 'rgba(225,230,236,0.6)' : 'rgba(255,255,255,0.8)';
    for (var i = 0; i < aantal; i++) wolken.push({ x: willekeur(-200, B), y: willekeur(-30, H * .45), s: willekeur(.8, 1.9),
      v: willekeur(4, 9) * (/windy/.test(staat.weer) ? 3 : 1), img: sprite(tint) });
    // Sterren bij een heldere nacht
    if (deel === 'nacht' && (groep === 'helder' || groep === 'half'))
      for (i = 0; i < 90; i++) sterren.push({ x: Math.random() * B, y: Math.random() * H * .8, r: willekeur(.4, 1.4), f: Math.random() * 6 });
    // Neerslag gaat voor; anders het feest of het seizoen
    if (groep === 'regen' || groep === 'onweer') maak(staat.weer === 'pouring' ? 150 : 90, 'regen');
    else if (groep === 'sneeuw') maak(70, 'sneeuw');
    else if (s === 'sinterklaas') maak(26, 'pepernoot');
    else if (s === 'kerst') maak(40, 'sneeuw');
    else if (s === 'nieuwjaar') maak(40, 'confetti');
    else if (s === 'valentijn') maak(16, 'hart');
    else if (s === 'pasen') maak(14, 'ei');
    else if (s === 'koningsdag') maak(50, 'oranje');
    else if (s === 'halloween') maak(7, 'vleermuis');
    else if (s === 'lente') maak(18, 'bloesem');
    else if (s === 'herfst') maak(16, 'blad');
  }

  var KLEUREN = { confetti: ['#ffd166', '#ef476f', '#06d6a0', '#ffffff', '#f4a261'], oranje: ['#ff7a00', '#ff9a1f', '#ffffff', '#21468b', '#ae1c28'],
    ei: ['#ffd6e0', '#c9f2d0', '#fff1b8', '#cfe3ff', '#e8d4ff'], bloesem: ['#ffd9e4', '#ffeef3', '#ffc2d4'], blad: ['#c8642a', '#e09a3a', '#a8432a', '#d9b04a'] };

  function maak(n, soort) {
    for (var i = 0; i < n; i++) {
      var k = KLEUREN[soort];
      deeltjes.push({ soort: soort, x: Math.random() * B, y: Math.random() * H, r: Math.random() * 6.3, vr: willekeur(-1, 1),
        f: Math.random() * 6.3, z: willekeur(.5, 1), kleur: k ? k[i % k.length] : '' });
    }
  }

  var SNELHEID = { regen: 620, sneeuw: 32, pepernoot: 46, confetti: 40, hart: 22, ei: 30, oranje: 44, vleermuis: 0, bloesem: 26, blad: 34 };

  function beweeg(p, dt, t) {
    var v = SNELHEID[p.soort] * p.z;
    if (p.soort === 'vleermuis') { p.x += 40 * p.z * dt; p.y += Math.sin(t * 1.3 + p.f) * 18 * dt; if (p.x > B + 40) { p.x = -40; p.y = willekeur(20, H * .5); } return; }
    p.y += v * dt;
    p.x += (p.soort === 'regen' ? -60 : Math.sin(t * .8 + p.f) * 20) * dt * p.z;
    p.r += p.vr * dt;
    if (p.y > H + 20) { p.y = -20; p.x = Math.random() * (B + 100); }
    if (p.x < -30) p.x = B + 20;
  }

  function teken(p, t) {
    var x = p.x, y = p.y, z = p.z;
    cx.save(); cx.translate(x, y); cx.rotate(p.r);
    switch (p.soort) {
      case 'regen': cx.rotate(-p.r + .1); cx.strokeStyle = 'rgba(190,215,240,' + (.25 + .3 * z) + ')'; cx.lineWidth = 1.2 * z;
        cx.beginPath(); cx.moveTo(0, 0); cx.lineTo(-2, 16 * z); cx.stroke(); break;
      case 'sneeuw': cx.fillStyle = 'rgba(255,255,255,' + (.5 + .4 * z) + ')'; cx.beginPath(); cx.arc(0, 0, 2.6 * z, 0, 7); cx.fill(); break;
      case 'pepernoot': var r = 9.5 * z; cx.fillStyle = '#8a4f22'; cx.beginPath(); cx.ellipse(0, 0, r, r * .85, 0, 0, 7); cx.fill();
        cx.fillStyle = 'rgba(255,220,170,.35)'; cx.beginPath(); cx.ellipse(-r * .3, -r * .3, r * .35, r * .25, 0, 0, 7); cx.fill(); break;
      case 'confetti': case 'oranje': cx.fillStyle = p.kleur; cx.fillRect(-4 * z, -2 * z, 8 * z, 4 * z * Math.abs(Math.cos(t * 3 + p.f))); break;
      case 'hart': var s = 9 * z; cx.rotate(-p.r + Math.sin(t + p.f) * .3); cx.fillStyle = 'rgba(255,90,120,' + (.55 + .3 * z) + ')';
        cx.beginPath(); cx.moveTo(0, s * .35); cx.bezierCurveTo(-s, -s * .3, -s * .5, -s, 0, -s * .45); cx.bezierCurveTo(s * .5, -s, s, -s * .3, 0, s * .35); cx.fill(); break;
      case 'ei': cx.fillStyle = p.kleur; cx.beginPath(); cx.ellipse(0, 0, 7 * z, 9.5 * z, 0, 0, 7); cx.fill();
        cx.strokeStyle = 'rgba(255,255,255,.7)'; cx.lineWidth = 1.5; cx.beginPath(); cx.moveTo(-6 * z, 0); cx.lineTo(6 * z, 0); cx.stroke(); break;
      case 'bloesem': cx.fillStyle = p.kleur; for (var b = 0; b < 5; b++) { cx.rotate(1.2566); cx.beginPath(); cx.ellipse(0, -3.5 * z, 2.2 * z, 3.4 * z, 0, 0, 7); cx.fill(); } break;
      case 'blad': cx.fillStyle = p.kleur; cx.beginPath(); cx.ellipse(0, 0, 4 * z, 9 * z, 0, 0, 7); cx.fill(); break;
      case 'vleermuis': cx.rotate(-p.r); var w = Math.sin(t * 9 + p.f) * 8, q = 22 * z; cx.fillStyle = 'rgba(12,8,18,.95)';
        cx.beginPath(); cx.moveTo(0, 0); cx.quadraticCurveTo(-q * .6, -q * .5 - w, -q * 1.4, -w); cx.quadraticCurveTo(-q * .7, q * .1, 0, q * .3);
        cx.quadraticCurveTo(q * .7, q * .1, q * 1.4, -w); cx.quadraticCurveTo(q * .6, -q * .5 - w, 0, 0); cx.fill(); break;
    }
    cx.restore();
  }

  /** Kerstlichtjes: een slinger langs de bovenkant die zacht twinkelt. */
  function lichtjes(t, kleuren, vlag) {
    var n = 18, zak = 34;
    cx.strokeStyle = 'rgba(30,40,30,.8)'; cx.lineWidth = 2; cx.beginPath();
    for (var i = 0; i <= 60; i++) { var x = B * i / 60, y = 8 + zak * Math.sin(Math.PI * ((i % 30) / 30)); i ? cx.lineTo(x, y) : cx.moveTo(x, y); }
    cx.stroke();
    for (i = 0; i < n; i++) {
      var f = (i + .5) / n, px = B * f, py = 8 + zak * Math.sin(Math.PI * ((f * 2) % 1)) + 7, k = kleuren[i % kleuren.length];
      if (vlag) { cx.fillStyle = k; cx.beginPath(); cx.moveTo(px - 11, py - 7); cx.lineTo(px + 11, py - 7); cx.lineTo(px, py + 14); cx.fill(); continue; }
      var a = .55 + .45 * Math.sin(t * 1.6 + i * 1.7);
      var g = cx.createRadialGradient(px, py, 0, px, py, 16); g.addColorStop(0, k.replace('A', a)); g.addColorStop(1, k.replace('A', 0));
      cx.fillStyle = g; cx.beginPath(); cx.arc(px, py, 16, 0, 7); cx.fill();
      cx.fillStyle = k.replace('A', .5 + a / 2); cx.beginPath(); cx.ellipse(px, py, 3.2, 4.6, 0, 0, 7); cx.fill();
    }
  }

  /** Rustig vuurwerk: om de paar seconden een zachte bol vonken, zonder flitsen. */
  function pijlen(t, dt) {
    if (t > volgendePijl) {
      volgendePijl = t + willekeur(1.2, 2.8);
      var x = willekeur(B * .1, B * .9), y = willekeur(H * .12, H * .45), kl = ['#ffd166', '#ef476f', '#7bdff2', '#b2f7ef', '#f7aef8'][Math.floor(Math.random() * 5)];
      for (var i = 0; i < 46; i++) { var h = i / 46 * 6.283, s = willekeur(50, 110); vuurwerk.push({ x: x, y: y, vx: Math.cos(h) * s, vy: Math.sin(h) * s, l: 1.8, k: kl }); }
    }
    for (var j = vuurwerk.length - 1; j >= 0; j--) {
      var v = vuurwerk[j]; v.l -= dt; v.vy += 30 * dt; v.x += v.vx * dt; v.y += v.vy * dt; v.vx *= .985; v.vy *= .985;
      if (v.l <= 0) { vuurwerk.splice(j, 1); continue; }
      cx.globalAlpha = Math.min(1, v.l / 1.2) * .9; cx.fillStyle = v.k; cx.beginPath(); cx.arc(v.x, v.y, 1.9, 0, 7); cx.fill();
    }
    cx.globalAlpha = 1;
  }

  var vorige = 0, laatst = 0, loopt = false;
  function beeld(ms) {
    if (!loopt) return;
    requestAnimationFrame(beeld);
    if (ms - laatst < 33) return; // ~30 beelden per seconde is genoeg en spaart de tablet
    var dt = Math.min(.1, (ms - (vorige || ms)) / 1000), t = ms / 1000;
    vorige = laatst = ms;
    cx.clearRect(0, 0, B, H);
    var groep = GROEP[staat.weer] || 'half', s = staat.feest.soort;
    for (var i = 0; i < sterren.length; i++) {
      var st = sterren[i]; cx.globalAlpha = .35 + .45 * Math.abs(Math.sin(t * .6 + st.f)); cx.fillStyle = '#fff';
      cx.beginPath(); cx.arc(st.x, st.y, st.r, 0, 7); cx.fill();
    }
    cx.globalAlpha = 1;
    if (s === 'vuurwerk' && groep !== 'regen' && groep !== 'onweer') pijlen(t, dt);
    for (i = 0; i < wolken.length; i++) {
      var w = wolken[i]; w.x += w.v * dt; if (w.x > B + 40) w.x = -360 * w.s;
      cx.drawImage(w.img, w.x, w.y, 360 * w.s, 200 * w.s);
    }
    for (i = 0; i < deeltjes.length; i++) { beweeg(deeltjes[i], dt, t); teken(deeltjes[i], t); }
    if (s === 'kerst') lichtjes(t, ['rgba(255,80,80,A)', 'rgba(255,210,90,A)', 'rgba(110,220,130,A)', 'rgba(110,170,255,A)']);
    if (s === 'koningsdag') lichtjes(t, ['#ff7a00', '#ae1c28', '#ffffff', '#21468b'], true);
  }

  function zetLoop() {
    var aan = !document.hidden && !document.documentElement.classList.contains('nacht') && !document.documentElement.classList.contains('met-fotos');
    if (aan && !loopt) { loopt = true; vorige = 0; requestAnimationFrame(beeld); }
    if (!aan) { loopt = false; cx.clearRect(0, 0, B, H); }
  }

  /** De achtergrondkleur, de zon of de maan, mist. */
  function verf() {
    var groep = GROEP[staat.weer] || 'half', k = KLEUR[staat.deel][groep];
    lucht.style.background = 'linear-gradient(170deg,' + k[0] + ' 0%,' + k[1] + ' 55%,' + k[2] + ' 100%)';
    lucht.className = (klaar ? 'klaar ' : '') + 'deel-' + staat.deel + ' weer-' + groep + (staat.feest.soort ? ' feest-' + staat.feest.soort : '');
    document.documentElement.classList.toggle('lucht-licht', staat.deel === 'dag' && (groep === 'sneeuw' || groep === 'mist'));
  }

  function bijwerken() {
    var d = nu(), deel = dagdeel(d), f = feest(d);
    if (zoek.feest !== undefined) f = { soort: zoek.feest, tekst: f.tekst };
    var anders = deel !== staat.deel || f.soort !== staat.feest.soort;
    staat.deel = deel; staat.feest = f;
    if (anders) { verf(); opbouwen(); }
    var regel = document.getElementById('feestRegel');
    if (regel) { regel.textContent = f.tekst; regel.hidden = !f.tekst; }
  }

  window.Lucht = {
    nu: nu,
    /** Het weer van Home Assistant (bijvoorbeeld "rainy"); in de browser wint ?weer=. */
    weer: function (w) { if (zoek.weer || !w || w === staat.weer) return; staat.weer = w; verf(); opbouwen(); },
    controleer: zetLoop
  };

  window.addEventListener('resize', maat);
  document.addEventListener('visibilitychange', zetLoop);
  new MutationObserver(zetLoop).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  var klaar = false;
  bijwerken(); maat(); verf(); zetLoop();
  klaar = true; // pas na de eerste keer zacht overvloeien
  setTimeout(verf, 50);
  setInterval(bijwerken, 30000);
})();
