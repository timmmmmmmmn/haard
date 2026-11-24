// Haard: het scherm. Praat via window.HaardNative met de app; die stuurt berichten terug via haard.bericht().
// Bewust zonder ?. en ?? en flex-gap: de WebView van Fire OS kan ouder zijn.
(function () {
  'use strict';

  var N = window.HaardNative || demo();
  var $ = function (id) { return document.getElementById(id); };
  var html = document.documentElement;
  var inst = JSON.parse(N.instellingen());

  // ---------- Brug met de app ----------
  var wachtend = {};
  var volgnr = 1;
  function vraag(methode, pad, body) {
    return new Promise(function (ok, nee) {
      var id = volgnr++;
      wachtend[id] = { ok: ok, nee: nee };
      N.ha(id, methode, pad, body ? JSON.stringify(body) : '');
    });
  }
  function ws(bericht) {
    return new Promise(function (ok, nee) {
      var id = volgnr++;
      wachtend[id] = { ok: ok, nee: nee };
      N.ws(id, JSON.stringify(bericht));
    });
  }

  var haard = window.haard = {
    antwoord: function (id, status, tekst) {
      var w = wachtend[id];
      if (!w) return;
      delete wachtend[id];
      var data = null;
      try { data = tekst ? JSON.parse(tekst) : null; } catch (e) { data = tekst; }
      if (status >= 200 && status < 300) w.ok(data); else w.nee({ status: status, data: data });
    },
    bericht: function (soort, data) {
      var f = berichten[soort];
      if (f) f(data);
    },
    ingelogd: function (ok) {
      inst = JSON.parse(N.instellingen());
      toast(ok ? 'Verbonden met Home Assistant' : 'Inloggen is niet gelukt');
      vulInstellingen();
      if (ok) laadWeer();
    }
  };

  window.haardTerug = function () {
    var open = document.querySelector('.vel:not([hidden])');
    if (open) { sluitVel(open); return true; }
    if (!$('assist').hidden) { N.stop(); verbergAssist(); return true; }
    return false;
  };

  // ---------- Iconen ----------
  $('instKnop').innerHTML = ICONEN.instellingen;
  $('micKnop').innerHTML = ICONEN.mic;
  $('hoes').innerHTML = ICONEN.muziek;
  [].forEach.call(document.querySelectorAll('[data-sluit]'), function (b) { b.innerHTML = ICONEN.sluit; });
  [].forEach.call(document.querySelectorAll('[data-media]'), function (b) {
    if (b.dataset.media !== 'wissel') b.innerHTML = ICONEN[b.dataset.media];
  });

  // ---------- Thema ----------
  function thema() {
    var t = inst.thema;
    if (t === 'systeem') t = window.matchMedia('(prefers-color-scheme: light)').matches ? 'licht' : 'donker';
    html.setAttribute('data-theme', t);
  }
  thema();

  // ---------- Klok en nachtstand ----------
  var DAGEN = ['zondag', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag'];
  var MAANDEN = ['januari', 'februari', 'maart', 'april', 'mei', 'juni', 'juli', 'augustus', 'september', 'oktober', 'november', 'december'];
  var wakkerTot = 0;

  function twee(n) { return (n < 10 ? '0' : '') + n; }
  function minuten(hhmm) { var p = (hhmm || '0:0').split(':'); return (+p[0]) * 60 + (+p[1]); }

  function isNacht(d) {
    // De nachtstand loopt gelijk met de stille tijd (doordeweeks en in het weekend)
    if (!inst.nacht || Date.now() < wakkerTot) return false;
    var weekend = d.getDay() === 0 || d.getDay() === 6;
    var nu = d.getHours() * 60 + d.getMinutes(), van = minuten(inst.stil_van), tot = minuten(weekend ? inst.stil_tot_weekend : inst.stil_tot);
    return van <= tot ? (nu >= van && nu < tot) : (nu >= van || nu < tot);
  }

  var wasNacht = null;
  function tik() {
    var d = Lucht.nu();
    $('uur').textContent = d.getHours();
    $('min').textContent = twee(d.getMinutes());
    $('datum').innerHTML = '<b>' + DAGEN[d.getDay()] + '</b> ' + d.getDate() + ' ' + MAANDEN[d.getMonth()];
    var nacht = isNacht(d);
    if (nacht !== wasNacht) {
      wasNacht = nacht;
      html.classList.toggle('nacht', nacht);
      N.helderheid(nacht ? 0.01 : -1);
    }
  }
  tik();
  setInterval(tik, 1000);

  // 's Nachts het scherm aanraken: even een halve minuut wakker
  $('rust').addEventListener('click', function () {
    if (html.classList.contains('nacht')) { wakkerTot = Date.now() + 30000; tik(); }
  }, true);

  // ---------- Home Assistant-status ----------
  var berichten = {};
  var haStatus = { verbonden: false };
  berichten.ha = function (d) {
    haStatus = d;
    $('haPunt').classList.toggle('aan', !!d.verbonden);
    if (!$('instVel').hidden) vulInstellingen();
    if (d.verbonden && !weerGeladen) laadWeer();
    if (d.verbonden && !inst.pipeline) kiesHaard();
  };

  // Nog geen assistent gekozen: dan die met de naam "Haard" (zo heet hij in Home Assistant)
  function kiesHaard() {
    ws({ type: 'assist_pipeline/pipeline/list' }).then(function (m) {
      var p = ((m.result || {}).pipelines || []).filter(function (p) { return /^haard$/i.test(p.name); })[0];
      if (p && !inst.pipeline) zet('pipeline', p.id);
    }).catch(function () {});
  }

  // ---------- Weer ----------
  var weerGeladen = false, weerEntity = null, verwachting = [];

  function laadWeer() {
    if (!inst.ingelogd) return;
    var kies = inst.weer
      ? Promise.resolve(inst.weer)
      : vraag('GET', '/api/states').then(function (states) {
          var w = states.filter(function (s) { return s.entity_id.indexOf('weather.') === 0; });
          var thuis = w.filter(function (s) { return s.entity_id === 'weather.forecast_home'; })[0];
          return (thuis || w[0] || {}).entity_id || null;
        });
    kies.then(function (id) {
      if (!id) return;
      weerEntity = id;
      return Promise.all([
        vraag('GET', '/api/states/' + id),
        vraag('POST', '/api/services/weather/get_forecasts?return_response', { entity_id: id, type: 'daily' }).catch(function () { return null; })
      ]).then(function (r) {
        weerGeladen = true;
        toonWeer(r[0], r[1] && r[1].service_response && r[1].service_response[id] ? r[1].service_response[id].forecast : []);
      });
    }).catch(function () { weerGeladen = false; });
  }

  function graden(t) { return t == null ? '' : Math.round(t) + '°'; }

  function toonWeer(st, fc) {
    verwachting = fc || [];
    $('weerKnop').hidden = false;
    $('weerIcoon').innerHTML = WEER[st.state] || WEER.cloudy;
    Lucht.weer(st.state);
    $('weerTemp').textContent = graden(st.attributes.temperature);
    var zin = WEER_TEKST[st.state] || '';
    var v = verwachting[0];
    if (v) {
      zin += (zin ? ' · ' : '') + graden(v.templow) + ' / ' + graden(v.temperature);
      if (v.precipitation_probability >= 20) zin += ' · ' + Math.round(v.precipitation_probability) + '% regen';
    }
    $('weerZin').textContent = zin;
    $('weerTitel').textContent = st.attributes.friendly_name || 'Weer';
  }

  function toonVerwachting() {
    var h = '';
    verwachting.slice(0, 6).forEach(function (v, i) {
      var d = new Date(v.datetime);
      var naam = i === 0 ? 'vandaag' : i === 1 ? 'morgen' : DAGEN[d.getDay()].slice(0, 2);
      h += '<div class="dag"><div class="dag-naam">' + naam + '</div>' + (WEER[v.condition] || WEER.cloudy) +
        '<div><span class="dag-max">' + graden(v.temperature) + '</span><span class="dag-min">' + graden(v.templow) + '</span></div>' +
        '<div class="dag-regen">' + (v.precipitation_probability ? Math.round(v.precipitation_probability) + '%' : (v.precipitation ? v.precipitation + ' mm' : '')) + '</div></div>';
    });
    $('dagen').innerHTML = h || '<div class="rij-sub" style="padding:20px">Geen verwachting beschikbaar.</div>';
  }

  $('weerKnop').addEventListener('click', function () { toonVerwachting(); openVel($('weerVel')); });
  setInterval(laadWeer, 10 * 60 * 1000);
  laadWeer();

  // ---------- Fotolijst ----------
  var fotoAantal = 0, fotoNr = 0, fotoKant = 0, fotoTimer = null;
  berichten.fotos = function (d) {
    fotoAantal = d.aantal || 0;
    html.classList.toggle('met-fotos', fotoAantal > 0);
    $('fotoSub').textContent = fotoAantal ? fotoAantal + " foto's in " + d.map : "Zet foto's in de map " + d.map + ' op de tablet';
    if (fotoAantal && !fotoTimer) volgendeFoto();
  };
  function volgendeFoto() {
    clearTimeout(fotoTimer);
    fotoTimer = null;
    if (!fotoAantal) return;
    var nr = fotoNr++ % fotoAantal;
    var img = new Image();
    var url = '/__foto/' + nr + '?t=' + Date.now();
    img.onload = function () {
      fotoKant = 1 - fotoKant;
      var nieuw = fotoKant ? $('fotoB') : $('fotoA'), oud = fotoKant ? $('fotoA') : $('fotoB');
      nieuw.style.backgroundImage = 'url("' + url + '")';
      nieuw.classList.add('zicht');
      oud.classList.remove('zicht');
    };
    img.src = url;
    fotoTimer = setTimeout(volgendeFoto, (inst.foto_interval || 30) * 1000);
  }

  // ---------- Muziek ----------
  var media = {};
  // ---------- Agenda: de volgende afspraak onder de klok (alleen binnen anderhalve dag) ----------
  var agendaId = null;
  function laadAgenda() {
    if (!inst.ingelogd) return;
    var id = agendaId !== null ? Promise.resolve(agendaId) : vraag('GET', '/api/states').then(function (states) {
      var ids = states.map(function (s) { return s.entity_id; }).filter(function (e) { return e.indexOf('calendar.') === 0; });
      return (agendaId = ids.indexOf('calendar.persoonlijk') > -1 ? 'calendar.persoonlijk' : (ids[0] || ''));
    });
    id.then(function (eid) {
      if (!eid) return;
      var nu = new Date(), tot = new Date(nu.getTime() + 36 * 3600e3);
      return vraag('GET', '/api/calendars/' + eid + '?start=' + encodeURIComponent(nu.toISOString()) + '&end=' + encodeURIComponent(tot.toISOString()))
        .then(function (lijst) {
          var a = (lijst || []).filter(function (e) { return e.start && e.start.dateTime && new Date(e.start.dateTime) > nu; })[0];
          var r = $('agendaRegel');
          if (!a) { r.hidden = true; return; }
          var d = new Date(a.start.dateTime), morgen = d.getDate() !== nu.getDate();
          r.innerHTML = '<b>' + (morgen ? 'Morgen ' : '') + d.getHours() + ':' + twee(d.getMinutes()) + '</b> · ' +
            String(a.summary || 'Afspraak').replace(/</g, '&lt;');
          r.hidden = false;
        });
    }).catch(function () {});
  }
  laadAgenda();
  setInterval(laadAgenda, 5 * 60000);

  // ---------- Timers (gezet via Home Assistant: "zet een timer van tien minuten") ----------
  var timers = [];
  var TIMER_ICOON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M9 2h6"/></svg>';
  berichten.timers = function (d) { timers = d.lijst || []; toonTimers(); };
  function toonTimers() {
    var h = '';
    timers.forEach(function (t) {
      var over = t.loopt ? Math.max(0, t.over - Math.floor((Date.now() - t.sinds) / 1000)) : t.over;
      var u = Math.floor(over / 3600), m = Math.floor(over % 3600 / 60), s = over % 60;
      h += '<div class="timer">' + TIMER_ICOON + (u ? u + ':' + twee(m) : m) + ':' + twee(s) +
        (t.naam ? '<small>' + t.naam.replace(/</g, '&lt;') + '</small>' : '') + '</div>';
    });
    $('timers').innerHTML = h;
  }
  setInterval(function () { if (timers.length) toonTimers(); }, 1000);
  berichten.timerKlaar = function (d) {
    $('timerKlaar').hidden = !!d.weg;
    $('tkNaam').textContent = d.naam || '';
  };
  $('tkStop').addEventListener('click', function () { N.timerStop(); $('timerKlaar').hidden = true; });

  // Nu speelt: hoes als wazige achtergrond, klok links, voortgang die zelf doortelt.
  // Na tien minuten pauze komt de gewone lucht terug.
  var hoesUrl = '', pauzeSinds = 0, lokaal = {}, haSpeler = {}, bron = 'lokaal';
  // Wat er op de Fire zelf speelt gaat voor; anders wat Spotify via Home Assistant speelt (telefoon, speaker …)
  berichten.media = function (d) { lokaal = d; kiesMuziek(); };
  function kiesMuziek() {
    var d = lokaal.actief ? lokaal : haSpeler.actief ? haSpeler : lokaal;
    bron = d === haSpeler ? 'ha' : 'lokaal';
    toonMedia(d);
  }

  /** Spotify uit Home Assistant volgen: elke paar seconden de stand van de speler. */
  var spotifyId = null;
  function volgSpotify() {
    if (!inst.ingelogd || html.classList.contains('nacht')) return;
    var id = spotifyId ? Promise.resolve(spotifyId) : vraag('GET', '/api/states').then(function (states) {
      var s = states.filter(function (s) { return /^media_player\.spotify/.test(s.entity_id); })[0];
      return (spotifyId = s ? s.entity_id : '');
    });
    id.then(function (eid) {
      if (!eid) return;
      return vraag('GET', '/api/states/' + eid).then(function (s) {
        var a = s.attributes || {}, aan = s.state === 'playing' || s.state === 'paused';
        var pos = a.media_position != null ? a.media_position * 1000 : 0;
        var sinds = a.media_position_updated_at ? Date.parse(a.media_position_updated_at) : Date.now();
        haSpeler = { toegang: lokaal.toegang, actief: aan, speelt: s.state === 'playing', titel: a.media_title, artiest: a.media_artist,
          app: a.source || 'Spotify', hoesUrl: a.entity_picture ? '/__ha' + a.entity_picture : '',
          positie: pos, tijdstip: sinds, duur: (a.media_duration || 0) * 1000 };
        kiesMuziek();
      });
    }).catch(function () {});
  }
  volgSpotify();
  setInterval(volgSpotify, 3000);

  function toonMedia(d) {
    media = d;
    $('mediaSub').textContent = lokaal.toegang ? 'Toegang gegeven' : 'Nodig om te zien wat er speelt';
    $('mediaKnop').textContent = lokaal.toegang ? 'Instellingen' : 'Toegang geven';
    if (d.actief && !d.speelt) pauzeSinds = pauzeSinds || Date.now(); else pauzeSinds = 0;
    toonMuziek();
    if (!d.actief) return;
    $('mTitel').textContent = d.titel || 'Onbekend nummer';
    $('mArtiest').textContent = d.artiest || d.app || '';
    $('mSpeel').innerHTML = d.speelt ? ICONEN.pauze : ICONEN.speel;
    var url = d.hoesUrl || (d.hoes ? '/__hoes?v=' + d.hoes : '');
    if (url !== hoesUrl) { hoesUrl = url; laadHoes(url); }
    voortgang();
  };

  function toonMuziek() {
    var aan = !!media.actief && !(pauzeSinds && Date.now() - pauzeSinds > 600000);
    $('muziek').hidden = !aan;
    html.classList.toggle('met-muziek', aan);
    html.classList.toggle('muziek-pauze', aan && !media.speelt);
  }

  function laadHoes(url) {
    var h = $('hoes');
    h.style.backgroundImage = url ? 'url("' + url + '")' : '';
    h.classList.toggle('met-plaatje', !!url);
    if (!url) { kleurAchtergrond(null); return; }
    var img = new Image();
    img.onload = function () { kleurAchtergrond(img); };
    img.src = url;
  }

  /** Tekent de hoes heel klein; groot geschaald wordt dat vanzelf een zachte waas. Kiest ook de sterkste kleur. */
  function kleurAchtergrond(img) {
    var c = $('hoesDoek'), x = c.getContext('2d');
    if (!img) { x.fillStyle = '#2a2230'; x.fillRect(0, 0, c.width, c.height); html.style.removeProperty('--muziek'); return; }
    // Eerst de kleur kiezen uit een scherpe versie, dan de waas: vervaagd en iets voller van kleur
    x.filter = 'none'; x.drawImage(img, 0, 0, c.width, c.height);
    var p, beste = -1, kleur = null;
    try { p = x.getImageData(0, 0, c.width, c.height).data; } catch (e) { p = []; }
    for (var i = 0; i < p.length; i += 4) {
      var r = p[i], g = p[i + 1], b = p[i + 2], max = Math.max(r, g, b), min = Math.min(r, g, b);
      var score = (max - min) * (max > 60 ? 1 : 0.2) + max * 0.15; // verzadigd en niet te donker
      if (score > beste) { beste = score; kleur = [r, g, b]; }
    }
    // Een beetje lichter, zodat het op de donkere waas goed opvalt
    x.filter = 'blur(3px) saturate(1.3)';
    x.drawImage(img, -6, -6, c.width + 12, c.height + 12);
    x.filter = 'none';
    if (kleur) html.style.setProperty('--muziek', 'rgb(' + kleur.map(function (v) { return Math.round(v + (255 - v) * .35); }).join(',') + ')');
  }

  function mmss(ms) { var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + twee(s % 60); }
  function voortgang() {
    var d = media;
    if (!d.actief || !d.duur) { $('mBalk').style.width = '0'; $('mNu').textContent = $('mDuur').textContent = ''; return; }
    var pos = Math.min(d.duur, (d.positie || 0) + (d.speelt && d.tijdstip ? Date.now() - d.tijdstip : 0));
    $('mBalk').style.width = (100 * pos / d.duur).toFixed(2) + '%';
    $('mNu').textContent = mmss(pos);
    $('mDuur').textContent = mmss(d.duur);
  }
  setInterval(function () { if (media.actief) { voortgang(); toonMuziek(); } }, 1000);
  var HA_DIENST = { wissel: 'media_play_pause', volgende: 'media_next_track', vorige: 'media_previous_track' };
  [].forEach.call(document.querySelectorAll('[data-media]'), function (b) {
    b.addEventListener('click', function () {
      if (bron === 'ha' && spotifyId) {
        vraag('POST', '/api/services/media_player/' + HA_DIENST[b.dataset.media], { entity_id: spotifyId }).then(function () { setTimeout(volgSpotify, 400); });
      } else N.media(b.dataset.media);
    });
  });

  // ---------- Assistent ----------
  var LABELS = { luisteren: 'Ik luister…', denken: 'Even denken…', spreken: '', klaar: '', fout: '' };
  var verbergTimer = null;

  berichten.assist = function (d) {
    clearTimeout(verbergTimer);
    if (d.fase === 'weg') { verbergAssist(); return; } // niets echts gehoord: stil verdwijnen
    var a = $('assist');
    a.hidden = false;
    $('vlam').className = 'vlam ' + d.fase;
    $('assistLabel').textContent = LABELS[d.fase] || '';
    $('assistLabel').hidden = !LABELS[d.fase];
    if (d.fase === 'luisteren') { $('assistJij').textContent = ''; $('assistAntwoord').textContent = ''; }
    if (d.jij != null) $('assistJij').textContent = '“' + d.jij + '”';
    if (d.antwoord != null) $('assistAntwoord').textContent = d.antwoord;
    if (d.fase === 'klaar' || d.fase === 'fout') {
      var tekst = d.antwoord || '';
      verbergTimer = setTimeout(verbergAssist, tekst ? Math.min(12000, 3500 + tekst.length * 45) : 800);
    }
  };
  function verbergAssist() { $('assist').hidden = true; }
  $('assist').addEventListener('click', function () { N.stop(); verbergAssist(); });
  $('micKnop').addEventListener('click', function () { N.luister(); });

  // ---------- Vellen ----------
  function openVel(v) { v.hidden = false; }
  function sluitVel(v) {
    v.hidden = true;
    if (v.id === 'instVel') N.meten(false);
  }
  [].forEach.call(document.querySelectorAll('.vel'), function (v) {
    v.addEventListener('click', function (e) { if (e.target === v || e.target.closest('[data-sluit]')) sluitVel(v); });
  });

  // ---------- Instellingen ----------
  function segment(id, waarde, opslaan) {
    var g = $(id);
    [].forEach.call(g.querySelectorAll('button'), function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.v === String(waarde)));
      b.onclick = function () { opslaan(b.dataset.v); segment(id, b.dataset.v, opslaan); };
    });
  }
  function zet(k, v) {
    N.zet(k, String(v));
    inst[k] = typeof inst[k] === 'number' ? +v : typeof inst[k] === 'boolean' ? (v === true || v === 'true') : v;
  }

  var DREMPEL = { laag: 0.7, normaal: 0.5, hoog: 0.3 };
  var wekwoord = { aan: false, model: '' };
  berichten.wekwoord = function (d) {
    wekwoord = d;
    var naam = (d.model || '').replace(/_v[\d.]+$/, '').replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
    $('wwSub').textContent = d.fout ? d.fout : d.aan ? 'Luistert naar “' + naam + '”' : 'Uit: tik op de microfoon om iets te vragen';
  };
  berichten.meter = function (d) { $('meter').style.width = Math.round(d.kans * 100) + '%'; };

  function vulInstellingen() {
    inst = JSON.parse(N.instellingen());
    $('haStatus').textContent = !inst.ingelogd ? 'Niet ingelogd' : haStatus.verbonden ? 'Verbonden' : (haStatus.melding || 'Verbinden…');
    $('haUrlTekst').textContent = inst.token ? 'Met token' : inst.ingelogd ? 'Ingelogd' : 'Log in om Haard te koppelen';
    $('loginKnop').textContent = inst.ingelogd ? 'Opnieuw inloggen' : 'Inloggen';
    $('loginKnop').className = 'knop' + (inst.ingelogd ? '' : ' accent');
    $('uitlogRij').hidden = !inst.ingelogd;
    if (document.activeElement !== $('haUrl')) $('haUrl').value = inst.ha_url;
    $('wwAan').checked = inst.wekwoord;
    segment('gevoelig', inst.gevoeligheid, function (v) { zet('gevoeligheid', v); $('drempel').style.left = DREMPEL[v] * 100 + '%'; });
    $('drempel').style.left = (DREMPEL[inst.gevoeligheid] || 0.5) * 100 + '%';
    segment('thema', inst.thema, function (v) { zet('thema', v); thema(); });
    segment('fotoInterval', inst.foto_interval, function (v) { zet('foto_interval', v); volgendeFoto(); });
    $('stilAan').checked = inst.stil;
    $('stilVan').value = inst.stil_van;
    $('stilTot').value = inst.stil_tot;
    $('stilTotWeekend').value = inst.stil_tot_weekend;
    $('nachtAan').checked = inst.nacht;
    $('versie').textContent = 'Versie ' + N.versie();
    if (inst.ingelogd && haStatus.verbonden) laadKeuzes();
  }

  function laadKeuzes() {
    ws({ type: 'assist_pipeline/pipeline/list' }).then(function (m) {
      var r = m.result || {}, sel = $('pipeline');
      sel.innerHTML = '<option value="">Standaard' + '</option>';
      (r.pipelines || []).forEach(function (p) {
        var o = document.createElement('option');
        o.value = p.id;
        o.textContent = p.name + (p.id === r.preferred_pipeline ? ' (voorkeur)' : '');
        sel.appendChild(o);
      });
      sel.value = inst.pipeline || '';
    }).catch(function () {});
    vraag('GET', '/api/states').then(function (states) {
      var sel = $('weerEntity');
      sel.innerHTML = '<option value="">Automatisch</option>';
      states.filter(function (s) { return s.entity_id.indexOf('weather.') === 0; }).forEach(function (s) {
        var o = document.createElement('option');
        o.value = s.entity_id;
        o.textContent = s.attributes.friendly_name || s.entity_id;
        sel.appendChild(o);
      });
      sel.value = inst.weer || '';
    }).catch(function () {});
  }

  $('instKnop').addEventListener('click', function () {
    vulInstellingen();
    openVel($('instVel'));
    N.meten(true);
    N.fotos();
  });
  $('loginKnop').addEventListener('click', function () {
    zet('ha_url', $('haUrl').value.trim() || inst.ha_url);
    N.login();
  });
  $('haUrl').addEventListener('change', function () { zet('ha_url', this.value.trim()); });
  $('tokenKnop').addEventListener('click', function () {
    var t = $('haToken').value.trim();
    if (!t) return;
    N.token(t);
    $('haToken').value = '';
    toast('Token opgeslagen');
    setTimeout(function () { vulInstellingen(); laadWeer(); }, 1500);
  });
  $('uitlogKnop').addEventListener('click', function () { N.uitloggen(); setTimeout(vulInstellingen, 300); });
  $('wwAan').addEventListener('change', function () { zet('wekwoord', this.checked); });
  $('pipeline').addEventListener('change', function () { zet('pipeline', this.value); });
  $('weerEntity').addEventListener('change', function () { zet('weer', this.value); laadWeer(); });
  $('stilAan').addEventListener('change', function () { zet('stil', this.checked); });
  $('stilVan').addEventListener('change', function () { zet('stil_van', this.value); });
  $('stilTot').addEventListener('change', function () { zet('stil_tot', this.value); });
  $('stilTotWeekend').addEventListener('change', function () { zet('stil_tot_weekend', this.value); });
  $('nachtAan').addEventListener('change', function () { zet('nacht', this.checked); wasNacht = null; tik(); });
  $('testKnop').addEventListener('click', function () { sluitVel($('instVel')); N.luister(); });
  $('mediaKnop').addEventListener('click', function () { N.mediaToegang(); });
  $('updateKnop').addEventListener('click', function () { N.update(); });
  $('bronKnop').addEventListener('click', function () { N.open('https://github.com/timmmmmmmmn/haard'); });

  // ---------- Toast ----------
  var toastTimer = null;
  function toast(t) {
    var el = $('toast');
    el.textContent = t;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 3500);
  }

  // Nog niet gekoppeld? Dan meteen de instellingen tonen.
  if (!inst.ingelogd) setTimeout(function () { $('instKnop').click(); }, 600);

  // ---------- Voorbeeld in een gewone browser (zonder de app) ----------
  function demoHoes() {
    var c = document.createElement('canvas'); c.width = c.height = 300;
    var x = c.getContext('2d'), g = x.createLinearGradient(0, 0, 300, 300);
    g.addColorStop(0, '#ff4f8b'); g.addColorStop(.5, '#7b2ff7'); g.addColorStop(1, '#1b1464');
    x.fillStyle = g; x.fillRect(0, 0, 300, 300);
    x.fillStyle = 'rgba(255,214,102,.9)'; x.beginPath(); x.arc(200, 110, 60, 0, 7); x.fill();
    x.fillStyle = '#12082e'; for (var i = 0; i < 9; i++) x.fillRect(i * 34, 300 - 40 - (i * 37 % 90), 30, 140);
    return c.toDataURL();
  }
  function demo() {
    var s = { ha_url: 'https://ha.timvanbergen.nl', pipeline: '', wekwoord: true, gevoeligheid: 'normaal', thema: 'donker',
      foto_interval: 30, stil: true, stil_van: '23:00', stil_tot: '08:00', stil_tot_weekend: '10:00', nacht: false, nacht_van: '23:00', nacht_tot: '07:00', weer: '', ingelogd: true, token: false, media_toegang: true };
    var nu = Date.now();
    var fc = [0, 1, 2, 3, 4, 5].map(function (i) {
      return { datetime: new Date(nu + i * 864e5).toISOString(), condition: ['partlycloudy', 'rainy', 'sunny', 'cloudy', 'pouring', 'clear-night'][i],
        temperature: 9 - i % 3, templow: 3 + i % 2, precipitation_probability: [10, 70, 0, 20, 90, 5][i] };
    });
    setTimeout(function () {
      haard.bericht('ha', { verbonden: true, ingelogd: true });
      haard.bericht('wekwoord', { aan: true, model: 'hey_jarvis_v0.1' });
      haard.bericht('fotos', { aantal: 0, map: 'Pictures/Haard' });
      if (location.hash === '#muziek' || location.hash === '#assist') {
        haard.bericht('media', { toegang: true, actief: true, speelt: true, titel: 'Midnight City', artiest: 'M83', app: 'Spotify', hoesUrl: demoHoes(),
          positie: 83000, tijdstip: Date.now(), duur: 243000 });
      }
      if (location.hash === '#timer') haard.bericht('timers', { lijst: [{ id: 'a', naam: 'pasta', over: 538, loopt: true, sinds: Date.now() }] });
      if (location.hash === '#inst') document.getElementById('instKnop').click();
      if (location.hash === '#weer') setTimeout(function () { document.getElementById('weerKnop').click(); }, 100);
      if (location.hash === '#assist') haard.bericht('assist', { fase: 'spreken', jij: 'Wat voor weer wordt het morgen?', antwoord: 'Morgen wordt het bewolkt met regen, tussen de 3 en 9 graden.' });
    }, 50);
    return {
      instellingen: function () { return JSON.stringify(s); },
      zet: function (k, v) { s[k] = v; },
      versie: function () { return 'voorbeeld'; },
      ha: function (id, m, pad) {
        setTimeout(function () {
          var r = pad.indexOf('/api/calendars/') === 0 ? [{ summary: 'Tandarts', start: { dateTime: new Date(Date.now() + 5 * 3600e3).toISOString() } }]
            : pad.indexOf('get_forecasts') > -1 ? { service_response: { 'weather.forecast_home': { forecast: fc } } }
            : pad === '/api/states' ? [{ entity_id: 'weather.forecast_home', attributes: { friendly_name: 'Thuis' } }, { entity_id: 'calendar.persoonlijk', attributes: {} }]
            : { state: (location.search.match(/weer=([\w-]+)/) || [])[1] || 'partlycloudy', attributes: { temperature: 7.4, friendly_name: 'Thuis' } };
          haard.antwoord(id, 200, JSON.stringify(r));
        }, 30);
      },
      ws: function (id) { setTimeout(function () { haard.antwoord(id, 200, JSON.stringify({ result: { pipelines: [{ id: 'a', name: 'Haard' }], preferred_pipeline: 'a' } })); }, 30); },
      luister: function () { haard.bericht('assist', { fase: 'luisteren' }); },
      stop: function () {}, media: function () {}, timerStop: function () {}, mediaToegang: function () {}, meten: function () {}, fotos: function () {},
      helderheid: function () {}, update: function () {}, open: function (u) { window.open(u); },
      login: function () {}, token: function () {}, uitloggen: function () {}
    };
  }
})();
