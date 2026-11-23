// Iconen als SVG-tekst: lijnen in currentColor, zodat ze het thema volgen.
var ICONEN = (function () {
  function lijn(d, w) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (w || 1.9) +
      '" stroke-linecap="round" stroke-linejoin="round">' + d + '</svg>';
  }
  function vol(d) {
    return '<svg viewBox="0 0 24 24" fill="currentColor">' + d + '</svg>';
  }
  return {
    instellingen: lijn('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
    mic: lijn('<rect x="9" y="2.5" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3.5"/>'),
    sluit: lijn('<path d="M6 6l12 12M18 6L6 18"/>', 2.2),
    speel: vol('<path d="M7 4.5v15a1 1 0 0 0 1.5.9l12-7.5a1 1 0 0 0 0-1.7l-12-7.5A1 1 0 0 0 7 4.5z"/>'),
    pauze: vol('<rect x="5.5" y="4" width="4.5" height="16" rx="1.2"/><rect x="14" y="4" width="4.5" height="16" rx="1.2"/>'),
    vorige: vol('<path d="M18 5.2v13.6a.9.9 0 0 1-1.4.7L8 13.2V18a1 1 0 0 1-2 0V6a1 1 0 0 1 2 0v4.8l8.6-6.3a.9.9 0 0 1 1.4.7z"/>'),
    volgende: vol('<path d="M6 5.2v13.6a.9.9 0 0 0 1.4.7l8.6-6.3V18a1 1 0 0 0 2 0V6a1 1 0 0 0-2 0v4.8L7.4 4.5A.9.9 0 0 0 6 5.2z"/>'),
    muziek: lijn('<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>')
  };
})();

// Weericonen per toestand van Home Assistant. Zon en maan in de accentkleur, wolken in de tekstkleur.
var WEER = (function () {
  var zon = '<circle cx="12" cy="12" r="4.2" fill="var(--zon)" stroke="none"/><g stroke="var(--zon)"><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></g>';
  var maan = '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" fill="var(--maan)" stroke="none"/>';
  var wolk = '<path d="M7 19h10.5a4 4 0 0 0 .4-8 6 6 0 0 0-11.6 1.5A3.3 3.3 0 0 0 7 19z" fill="var(--wolk)" stroke="currentColor"/>';
  var kleineZon = '<g transform="translate(-3.5 -4) scale(.75)">' + zon + '</g>';
  function svg(d) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' + d + '</svg>';
  }
  var regen = '<path d="M9 21l-1 2M13 21l-1 2M17 21l-1 2" stroke="var(--regen)"/>';
  var veel = '<path d="M8.5 20.5l-1.5 3M12.5 20.5l-1.5 3M16.5 20.5l-1.5 3" stroke="var(--regen)" stroke-width="1.8"/>';
  var sneeuw = '<g fill="currentColor" stroke="none"><circle cx="8.5" cy="21.6" r="1"/><circle cx="12.5" cy="22.6" r="1"/><circle cx="16.5" cy="21.6" r="1"/></g>';
  var bliksem = '<path d="M13 15l-3 4.5h3.5L11 24" stroke="var(--zon)" stroke-width="1.8"/>';
  var schuif = function (d) { return '<g transform="translate(0 -3)">' + d + '</g>'; };
  return {
    'sunny': svg(zon),
    'clear-night': svg(maan),
    'partlycloudy': svg(kleineZon + wolk),
    'cloudy': svg(wolk),
    'rainy': svg(schuif(wolk) + regen),
    'pouring': svg(schuif(wolk) + veel),
    'lightning': svg(schuif(wolk) + bliksem),
    'lightning-rainy': svg(schuif(wolk) + bliksem + regen),
    'snowy': svg(schuif(wolk) + sneeuw),
    'snowy-rainy': svg(schuif(wolk) + sneeuw + regen),
    'hail': svg(schuif(wolk) + sneeuw),
    'fog': svg('<path d="M4 9h16M3 13h18M5 17h14M8 21h8"/>'),
    'windy': svg('<path d="M3 9h11a3 3 0 1 0-3-3M3 15h15a3 3 0 1 1-3 3M3 12h9"/>'),
    'windy-variant': svg(schuif(wolk) + '<path d="M4 21h9M7 23.5h9" />'),
    'exceptional': svg('<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 16.5v.5"/>')
  };
})();

var WEER_TEKST = {
  'sunny': 'Zonnig', 'clear-night': 'Helder', 'partlycloudy': 'Half bewolkt', 'cloudy': 'Bewolkt',
  'rainy': 'Regen', 'pouring': 'Stortregen', 'lightning': 'Onweer', 'lightning-rainy': 'Onweer en regen',
  'snowy': 'Sneeuw', 'snowy-rainy': 'Natte sneeuw', 'hail': 'Hagel', 'fog': 'Mist', 'windy': 'Winderig',
  'windy-variant': 'Winderig en bewolkt', 'exceptional': 'Uitzonderlijk weer'
};
