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

  // ---------- Feestdagen ----------
  /** Versiering is bewust minimaal: alleen kerst en, van de week na kerst tot en met nieuwjaarsdag, vuurwerk. */
  function feest(d) {
    var md = (d.getMonth() + 1) * 100 + d.getDate();
    if (md >= 1206 && md <= 1226) return 'kerst';
    if (md >= 1227 || md === 101) return 'vuurwerk';
    return '';
  }

  /** Hoeveel seconden tussen twee pijlen: op oudejaarsavond en in de nieuwjaarsnacht veel vaker. */
  function tussenPijlen(d) {
    var md = (d.getMonth() + 1) * 100 + d.getDate(), u = d.getHours();
    return md === 1231 && u >= 18 || md === 101 && u < 3 ? [0.6, 1.8] : [3, 7];
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
  var B = 0, H = 0, staat = { weer: zoek.weer || 'partlycloudy', deel: '', feest: '' };
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
    var groep = GROEP[staat.weer] || 'half', deel = staat.deel, s = staat.feest;
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
    // Neerslag gaat voor; met kerst valt er anders zachte sneeuw
    if (groep === 'regen' || groep === 'onweer') maak(staat.weer === 'pouring' ? 150 : 90, 'regen');
    else if (groep === 'sneeuw' || s === 'kerst') maak(groep === 'sneeuw' ? 70 : 40, 'sneeuw');
  }

  function maak(n, soort) {
    for (var i = 0; i < n; i++) {
      deeltjes.push({ soort: soort, x: Math.random() * B, y: Math.random() * H, r: Math.random() * 6.3, vr: willekeur(-1, 1),
        f: Math.random() * 6.3, z: willekeur(.5, 1) });
    }
  }

  var SNELHEID = { regen: 620, sneeuw: 32 };

  function beweeg(p, dt, t) {
    var v = SNELHEID[p.soort] * p.z;
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
    }
    cx.restore();
  }

  /** Kerstlichtjes: een slinger langs de bovenkant die zacht twinkelt. */
  function lichtjes(t, kleuren) {
    var n = 18, zak = 34;
    cx.strokeStyle = 'rgba(30,40,30,.8)'; cx.lineWidth = 2; cx.beginPath();
    for (var i = 0; i <= 60; i++) { var x = B * i / 60, y = 8 + zak * Math.sin(Math.PI * ((i % 30) / 30)); i ? cx.lineTo(x, y) : cx.moveTo(x, y); }
    cx.stroke();
    for (i = 0; i < n; i++) {
      var f = (i + .5) / n, px = B * f, py = 8 + zak * Math.sin(Math.PI * ((f * 2) % 1)) + 7, k = kleuren[i % kleuren.length];
      var a = .55 + .45 * Math.sin(t * 1.6 + i * 1.7);
      var g = cx.createRadialGradient(px, py, 0, px, py, 16); g.addColorStop(0, k.replace('A', a)); g.addColorStop(1, k.replace('A', 0));
      cx.fillStyle = g; cx.beginPath(); cx.arc(px, py, 16, 0, 7); cx.fill();
      cx.fillStyle = k.replace('A', .5 + a / 2); cx.beginPath(); cx.ellipse(px, py, 3.2, 4.6, 0, 0, 7); cx.fill();
    }
  }

  /** Vuurwerk: om de paar seconden een bol gloeiende vonken met staartjes die uitwaaieren en uitdoven. */
  var gloed = [];
  function pijlen(t, dt) {
    if (t > volgendePijl) {
      var tp = tussenPijlen(nu()); volgendePijl = t + willekeur(tp[0], tp[1]);
      var x = willekeur(B * .08, B * .92), y = willekeur(H * .1, H * .5), kl = ['#ffd166', '#ff5d8f', '#7bdff2', '#b8f2a0', '#f7aef8', '#ffffff'][Math.floor(Math.random() * 6)];
      var n = 70, kracht = willekeur(90, 150);
      for (var i = 0; i < n; i++) { var h = i / n * 6.283 + Math.random() * .1, s = kracht * willekeur(.75, 1);
        vuurwerk.push({ x: x, y: y, vx: Math.cos(h) * s, vy: Math.sin(h) * s, l: willekeur(1.6, 2.4), k: kl }); }
      gloed.push({ x: x, y: y, l: 1, k: kl });
    }
    cx.globalCompositeOperation = 'lighter';
    for (var g = gloed.length - 1; g >= 0; g--) {
      var q = gloed[g]; q.l -= dt * 1.4; if (q.l <= 0) { gloed.splice(g, 1); continue; }
      var rg = cx.createRadialGradient(q.x, q.y, 0, q.x, q.y, 140);
      rg.addColorStop(0, q.k); rg.addColorStop(1, 'rgba(0,0,0,0)');
      cx.globalAlpha = q.l * .22; cx.fillStyle = rg; cx.beginPath(); cx.arc(q.x, q.y, 140, 0, 7); cx.fill();
    }
    cx.lineCap = 'round';
    for (var j = vuurwerk.length - 1; j >= 0; j--) {
      var v = vuurwerk[j]; v.l -= dt; v.vy += 38 * dt; v.vx *= .975; v.vy *= .975; v.x += v.vx * dt; v.y += v.vy * dt;
      if (v.l <= 0) { vuurwerk.splice(j, 1); continue; }
      cx.globalAlpha = Math.min(1, v.l / 1.1); cx.strokeStyle = v.k; cx.lineWidth = 2.2;
      cx.beginPath(); cx.moveTo(v.x - v.vx * .09, v.y - v.vy * .09); cx.lineTo(v.x, v.y); cx.stroke();
    }
    cx.globalAlpha = 1; cx.globalCompositeOperation = 'source-over';
  }

  var vorige = 0, laatst = 0, loopt = false;
  function beeld(ms) {
    if (!loopt) return;
    requestAnimationFrame(beeld);
    if (ms - laatst < 33) return; // ~30 beelden per seconde is genoeg en spaart de tablet
    var dt = Math.min(.1, (ms - (vorige || ms)) / 1000), t = ms / 1000;
    vorige = laatst = ms;
    cx.clearRect(0, 0, B, H);
    var groep = GROEP[staat.weer] || 'half', s = staat.feest;
    for (var i = 0; i < sterren.length; i++) {
      var st = sterren[i]; cx.globalAlpha = .35 + .45 * Math.abs(Math.sin(t * .6 + st.f)); cx.fillStyle = '#fff';
      cx.beginPath(); cx.arc(st.x, st.y, st.r, 0, 7); cx.fill();
    }
    cx.globalAlpha = 1;
    if (s === 'vuurwerk') pijlen(t, dt);
    for (i = 0; i < wolken.length; i++) {
      var w = wolken[i]; w.x += w.v * dt; if (w.x > B + 40) w.x = -360 * w.s;
      cx.drawImage(w.img, w.x, w.y, 360 * w.s, 200 * w.s);
    }
    for (i = 0; i < deeltjes.length; i++) { beweeg(deeltjes[i], dt, t); teken(deeltjes[i], t); }
    if (s === 'kerst') lichtjes(t, ['rgba(255,80,80,A)', 'rgba(255,210,90,A)', 'rgba(110,220,130,A)', 'rgba(110,170,255,A)']);
  }

  function zetLoop() {
    var aan = !document.hidden && !document.documentElement.classList.contains('nacht') && !document.documentElement.classList.contains('met-fotos') && !document.documentElement.classList.contains('met-muziek');
    if (aan && !loopt) { loopt = true; vorige = 0; requestAnimationFrame(beeld); }
    if (!aan) { loopt = false; cx.clearRect(0, 0, B, H); }
  }

  /** De achtergrondkleur, de zon of de maan, mist. */
  function verf() {
    var groep = GROEP[staat.weer] || 'half', k = KLEUR[staat.deel][groep];
    lucht.style.background = 'linear-gradient(170deg,' + k[0] + ' 0%,' + k[1] + ' 55%,' + k[2] + ' 100%)';
    lucht.className = (klaar ? 'klaar ' : '') + 'deel-' + staat.deel + ' weer-' + groep + (staat.feest ? ' feest-' + staat.feest : '');
    document.documentElement.classList.toggle('lucht-licht', staat.deel === 'dag' && (groep === 'sneeuw' || groep === 'mist'));
  }

  function bijwerken() {
    var d = nu(), deel = dagdeel(d), f = zoek.feest !== undefined ? zoek.feest : feest(d);
    if (deel === staat.deel && f === staat.feest) return;
    staat.deel = deel; staat.feest = f;
    verf(); opbouwen();
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
