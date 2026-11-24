package nl.timvanbergen.haard

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * De eigen agenda (agenda.timvanbergen.nl, via CalDAV in Home Assistant): voorlezen wat er staat en
 * afspraken toevoegen. De zinnen worden hier zelf begrepen (snel en voorspelbaar, zonder taalmodel).
 * Netwerk: alleen vanaf een achtergronddraad aanroepen.
 */
object Agenda {

    sealed class Vraag {
        data class Periode(val van: Calendar, val tot: Calendar, val wanneer: String) : Vraag()
        object Volgende : Vraag()
        data class Nieuw(val titel: String, val start: Calendar, val heleDag: Boolean) : Vraag()
    }

    private val DAGEN = listOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag")
    private val MAANDEN = listOf("januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december")
    private val GETAL = mapOf("een" to 1, "één" to 1, "twee" to 2, "drie" to 3, "vier" to 4, "vijf" to 5, "zes" to 6, "zeven" to 7,
        "acht" to 8, "negen" to 9, "tien" to 10, "elf" to 11, "twaalf" to 12)
    private const val UUR = "(\\d{1,2}|een|één|twee|drie|vier|vijf|zes|zeven|acht|negen|tien|elf|twaalf)"

    // ---------- Zinnen herkennen ----------

    fun herken(zin: String): Vraag? {
        val t = zin.lowercase(Locale("nl")).trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val agenda = Regex("\\b(agenda|kalender|planning)\\b").containsMatchIn(t)
        // Toevoegen: "zet morgen om drie uur tandarts in mijn agenda", "plan vrijdag voetbal", "voeg ... toe aan mijn agenda"
        if (agenda && Regex("^(zet|plan|voeg|noteer|schrijf|maak)\\b").containsMatchIn(t) || Regex("^plan\\b").containsMatchIn(t)) {
            return nieuw(t)
        }
        if (Regex("\\b(volgende|eerstvolgende|eerste) afspraak\\b").containsMatchIn(t)) return Vraag.Volgende
        val vraagt = agenda || Regex("\\b(afspraken|afspraak)\\b").containsMatchIn(t) ||
            Regex("\\b(moet ik|heb ik)\\b.*\\b(doen|iets|gepland|op het programma)\\b").containsMatchIn(t)
        if (!vraagt || !Regex("^(wat|welke|heb|hoe|staat|moet|wanneer)\\b").containsMatchIn(t)) return null
        return periode(t)
    }

    private fun periode(t: String): Vraag.Periode? {
        val van = vandaag()
        return when {
            Regex("\\bovermorgen\\b").containsMatchIn(t) -> dag(van, 2, "overmorgen")
            Regex("\\bmorgen\\b").containsMatchIn(t) -> dag(van, 1, "morgen")
            Regex("\\b(deze week|komende week|de week)\\b").containsMatchIn(t) ->
                Vraag.Periode(van, (van.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }, "de komende zeven dagen")
            Regex("\\bweekend\\b").containsMatchIn(t) -> {
                val za = volgendeDag(van, Calendar.SATURDAY, toestaanVandaag = true)
                Vraag.Periode(za, (za.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 2) }, "dit weekend")
            }
            else -> DAGEN.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(t) }.takeIf { it >= 0 }?.let { d ->
                val c = volgendeDag(van, d + 1, toestaanVandaag = true)
                Vraag.Periode(c, (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }, "op ${DAGEN[d]}")
            } ?: dag(van, 0, "vandaag")
        }
    }

    private fun dag(basis: Calendar, plus: Int, naam: String): Vraag.Periode {
        val c = (basis.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, plus) }
        return Vraag.Periode(c, (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }, naam)
    }

    /** "zet morgen om half vier tandarts in mijn agenda" → titel "Tandarts", morgen 15:30. */
    private fun nieuw(zin: String): Vraag.Nieuw? {
        var t = " $zin "
        val datum = vandaag()
        var datumGezet = false
        fun weg(r: Regex): MatchResult? = r.find(t)?.also { t = t.replaceRange(it.range, " ") }

        weg(Regex(" (in|op|aan|bij) (mijn|de|m'n) (agenda|kalender|planning)( toe)?( zetten)? "))
        weg(Regex("^ (zet|plan|voeg|noteer|schrijf|maak) (een afspraak |even |eens |maar )?"))
        weg(Regex(" toe "))

        weg(Regex(" (voor |op )?overmorgen "))?.let { datum.add(Calendar.DAY_OF_MONTH, 2); datumGezet = true }
        if (!datumGezet) weg(Regex(" (voor |op )?morgen(ochtend|middag|avond)? "))?.let {
            datum.add(Calendar.DAY_OF_MONTH, 1); datumGezet = true
            if (it.value.contains("avond")) t += " 's avonds " else if (it.value.contains("middag")) t += " 's middags " else if (it.value.contains("ochtend")) t += " 's ochtends "
        }
        if (!datumGezet) weg(Regex(" (voor |op )?vandaag "))?.let { datumGezet = true }
        if (!datumGezet) weg(Regex(" (op )?(de )?(\\d{1,2})(e|de|ste)? (${MAANDEN.joinToString("|")}) "))?.let { m ->
            val d = m.groupValues[3].toInt(); val mnd = MAANDEN.indexOf(m.groupValues[5])
            datum.set(Calendar.MONTH, mnd); datum.set(Calendar.DAY_OF_MONTH, d)
            if (datum.before(vandaag())) datum.add(Calendar.YEAR, 1)
            datumGezet = true
        }
        if (!datumGezet) weg(Regex(" (op |aanstaande |komende |volgende week )*(${DAGEN.joinToString("|")}) "))?.let { m ->
            val c = volgendeDag(datum, DAGEN.indexOf(m.groupValues[2]) + 1, toestaanVandaag = false)
            if (m.value.contains("volgende week")) c.add(Calendar.DAY_OF_MONTH, 7)
            datum.timeInMillis = c.timeInMillis; datumGezet = true
        }

        // Tijd: "om 15:30", "om 3 uur", "om half vier", "om kwart over twee", "om kwart voor negen"
        var uur = -1; var minuut = 0
        weg(Regex(" om half $UUR( uur)? "))?.let { uur = getal(it.groupValues[1]) - 1; minuut = 30 }
        if (uur < 0) weg(Regex(" om kwart over $UUR( uur)? "))?.let { uur = getal(it.groupValues[1]); minuut = 15 }
        if (uur < 0) weg(Regex(" om kwart voor $UUR( uur)? "))?.let { uur = getal(it.groupValues[1]) - 1; minuut = 45 }
        if (uur < 0) weg(Regex(" om (\\d{1,2})[:.](\\d{2})( uur)? "))?.let { uur = it.groupValues[1].toInt(); minuut = it.groupValues[2].toInt() }
        if (uur < 0) weg(Regex(" om $UUR( uur)?( (\\d{1,2}))? "))?.let { uur = getal(it.groupValues[1]); minuut = it.groupValues[4].toIntOrNull() ?: 0 }
        val dagdeel = weg(Regex(" ('s |s )?(ochtends|morgens|middags|avonds|nachts)|( in de (ochtend|middag|avond)) "))?.value.orEmpty()
        if (uur in 0..11 && Regex("middag|avond").containsMatchIn(dagdeel)) uur += 12
        // Zonder dagdeel: "om 3 uur" bedoelt bijna altijd de middag
        if (uur in 1..7 && dagdeel.isEmpty()) uur += 12

        val titel = t.replace(Regex("\\b(een|de|het|afspraak met|afspraak voor|afspraak)\\b\\s*$"), "")
            .replace(Regex("^\\s*((een|de|het|afspraak|met|voor|bij)\\s+)+"), "")
            .replace(Regex("\\s+"), " ").trim().trim(',', '.')
        if (titel.isEmpty() || (!datumGezet && uur < 0)) return null
        val start = (datum.clone() as Calendar).apply {
            if (uur >= 0) { set(Calendar.HOUR_OF_DAY, uur.coerceIn(0, 23)); set(Calendar.MINUTE, minuut.coerceIn(0, 59)) }
        }
        return Vraag.Nieuw(titel.replaceFirstChar { it.uppercase() }, start, uur < 0)
    }

    private fun getal(s: String) = s.toIntOrNull() ?: GETAL[s] ?: 0

    private fun vandaag() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun volgendeDag(basis: Calendar, dagVanWeek: Int, toestaanVandaag: Boolean): Calendar {
        val c = basis.clone() as Calendar
        var verschil = (dagVanWeek - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (verschil == 0 && !toestaanVandaag) verschil = 7
        c.add(Calendar.DAY_OF_MONTH, verschil)
        return c
    }

    // ---------- Home Assistant ----------

    private var agendaId: String? = null

    /** De agenda in Home Assistant (die van agenda.timvanbergen.nl heet "persoonlijk"). */
    fun entiteit(): String? {
        agendaId?.let { return it }
        val (code, tekst) = HomeAssistant.vraag("GET", "/api/states", null)
        if (code != 200) return null
        val ids = JSONArray(tekst).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("entity_id") } }.filter { it.startsWith("calendar.") }
        return (ids.firstOrNull { it == "calendar.persoonlijk" } ?: ids.firstOrNull())?.also { agendaId = it }
    }

    data class Afspraak(val titel: String, val start: Calendar, val heleDag: Boolean)

    fun afspraken(van: Calendar, tot: Calendar): List<Afspraak>? {
        val id = entiteit() ?: return null
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val pad = "/api/calendars/$id?start=" + java.net.URLEncoder.encode(f.format(van.time), "UTF-8") +
            "&end=" + java.net.URLEncoder.encode(f.format(tot.time), "UTF-8")
        val (code, tekst) = HomeAssistant.vraag("GET", pad, null)
        if (code != 200) return null
        val a = JSONArray(tekst)
        return (0 until a.length()).mapNotNull { i ->
            val e = a.getJSONObject(i)
            val s = e.optJSONObject("start") ?: return@mapNotNull null
            val heleDag = s.has("date") && !s.has("dateTime")
            val c = Calendar.getInstance()
            c.time = (if (heleDag) SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.getString("date"))
                else f.parse(s.getString("dateTime"))) ?: return@mapNotNull null
            Afspraak(e.optString("summary").ifEmpty { "Afspraak" }, c, heleDag)
        }.sortedBy { it.start.timeInMillis }
    }

    /** Voert de vraag uit en geeft de zin terug die Haard zegt. */
    fun antwoord(v: Vraag): String = when (v) {
        is Vraag.Periode -> {
            val lijst = afspraken(v.van, v.tot) ?: return "Ik kan je agenda nu niet bereiken."
            val meer = v.tot.timeInMillis - v.van.timeInMillis > 86_400_000L * 1.5
            when (lijst.size) {
                0 -> "Je hebt ${v.wanneer} niets in je agenda."
                1 -> "${v.wanneer.replaceFirstChar { it.uppercase() }} heb je ${beschrijf(lijst[0], meer)}."
                else -> "${v.wanneer.replaceFirstChar { it.uppercase() }} heb je ${lijst.size} afspraken: " +
                    lijst.take(5).joinToString("; ") { beschrijf(it, meer) } + "."
            }
        }
        Vraag.Volgende -> {
            val nu = Calendar.getInstance()
            val lijst = afspraken(nu, (nu.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 60) })
            when {
                lijst == null -> "Ik kan je agenda nu niet bereiken."
                lijst.isEmpty() -> "Je hebt de komende twee maanden geen afspraken."
                else -> "Je volgende afspraak is ${relatief(lijst[0].start)} ${beschrijf(lijst[0], false)}."
            }
        }
        is Vraag.Nieuw -> {
            val id = entiteit()
            if (id == null) "Ik kan je agenda nu niet bereiken." else {
                val body = JSONObject().put("entity_id", id).put("summary", v.titel)
                if (v.heleDag) {
                    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    body.put("start_date", f.format(v.start.time))
                        .put("end_date", f.format((v.start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.time))
                } else {
                    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    body.put("start_date_time", f.format(v.start.time))
                        .put("end_date_time", f.format((v.start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }.time))
                }
                val (code, _) = HomeAssistant.vraag("POST", "/api/services/calendar/create_event", body.toString())
                if (code in 200..299) "Ik heb ${v.titel} ${relatief(v.start)}${if (v.heleDag) "" else " om " + tijd(v.start)} in je agenda gezet."
                else "Dat lukte niet: ik kon je agenda niet bijwerken."
            }
        }
    }

    private fun beschrijf(a: Afspraak, metDag: Boolean): String {
        val dag = if (metDag) "${DAGEN[a.start.get(Calendar.DAY_OF_WEEK) - 1]} " else ""
        return if (a.heleDag) "$dag${a.titel}" else "${dag}om ${tijd(a.start)} ${a.titel}"
    }

    /** Zoals je het zegt: "15 uur", "13 uur 15". */
    private fun tijd(c: Calendar): String {
        val u = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        return if (m == 0) "$u uur" else "$u uur $m"
    }

    fun relatief(c: Calendar): String {
        val dagen = ((c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis -
            vandaag().apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis) / 86_400_000L
        return when (dagen) {
            0L -> "vandaag"
            1L -> "morgen"
            2L -> "overmorgen"
            in 3..6 -> "op ${DAGEN[c.get(Calendar.DAY_OF_WEEK) - 1]}"
            else -> "op ${DAGEN[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${MAANDEN[c.get(Calendar.MONTH)]}"
        }
    }
}
