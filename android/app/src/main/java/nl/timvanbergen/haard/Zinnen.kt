package nl.timvanbergen.haard

/**
 * De vaste commando's die de tablet zelf verstaat (zoals Speech-to-Phrase op de server deed): voor elk apparaat
 * en elke kamer die Home Assistant aan de assistent laat zien. Elke gesproken zin ("zet de lamp op vijftig procent")
 * hoort bij de opdracht die naar Home Assistant gaat ("zet de lamp op 50 procent"). Vosk mag alleen kiezen uit deze
 * zinnen; komt er iets anders uit, dan was het geen commando.
 */
object Zinnen {

    data class Apparaat(val namen: List<String>, val domein: String)

    private val AAN_UIT = setOf("light", "switch", "fan", "input_boolean")
    private val KLEUREN = listOf("rood", "oranje", "geel", "groen", "blauw", "paars", "roze", "wit")
    private val PROCENTEN = listOf(10, 20, 25, 30, 40, 50, 60, 70, 75, 80, 90, 100)
    private val MINUTEN = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, 40, 45, 50, 60, 90)
    private val SECONDEN = listOf(10, 15, 20, 30, 40, 45, 50, 60, 90)

    private val GETALLEN = mapOf(
        0 to "nul", 1 to "een", 2 to "twee", 3 to "drie", 4 to "vier", 5 to "vijf", 6 to "zes", 7 to "zeven", 8 to "acht",
        9 to "negen", 10 to "tien", 15 to "vijftien", 20 to "twintig", 25 to "vijfentwintig", 30 to "dertig", 40 to "veertig",
        45 to "vijfenveertig", 50 to "vijftig", 60 to "zestig", 70 to "zeventig", 75 to "vijfenzeventig", 80 to "tachtig",
        90 to "negentig", 100 to "honderd",
    )

    /** Zoals Vosk het zegt: kleine letters, geen leestekens. */
    fun normaal(s: String): String = s.lowercase()
        .replace('é', 'e').replace('ë', 'e').replace('ï', 'i').replace('ö', 'o')
        .replace("'", "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Gesproken zin → opdracht voor Home Assistant (of voor de tablet zelf). */
    fun bouw(apparaten: List<Apparaat>, kamers: List<String>): Map<String, String> {
        val z = LinkedHashMap<String, String>()
        fun zin(gezegd: String, opdracht: String = gezegd) { normaal(gezegd).takeIf { it.isNotEmpty() }?.let { z.putIfAbsent(it, opdracht) } }

        for (a in apparaten) {
            if (a.domein !in AAN_UIT) continue
            val namen = a.namen.map(::normaal).filter { it.isNotEmpty() && !it.any(Char::isDigit) }.distinct()
            for (n in namen) {
                val varianten = if (Regex("^(de|het|mijn) ").containsMatchIn(n)) listOf(n) else listOf(n, "de $n", "het $n")
                for (x in varianten) {
                    for (w in listOf("aan", "uit")) {
                        zin("zet $x $w"); zin("doe $x $w"); zin("$x $w", "zet $x $w")
                        zin("schakel $x ${if (w == "aan") "in" else "uit"}", "zet $x $w")
                    }
                    if (a.domein != "light") continue
                    for (k in KLEUREN) { zin("zet $x op $k"); zin("maak $x $k", "zet $x op $k") }
                    for (p in PROCENTEN) zin("zet $x op ${GETALLEN[p]} procent", "zet $x op $p procent")
                    zin("dim $x", "zet $x op 30 procent")
                    for (w in listOf("feller", "lichter")) zin("maak $x $w", "zet $x op 100 procent")
                    for (w in listOf("zachter", "donkerder")) zin("maak $x $w", "zet $x op 30 procent")
                }
            }
        }

        for (w in listOf("aan", "uit")) {
            zin("doe het licht $w"); zin("zet het licht $w"); zin("licht $w", "doe het licht $w")
            for (k in kamers.map(::normaal).filter { it.isNotEmpty() }.distinct()) {
                zin("doe het licht in de $k $w"); zin("doe het licht in $k $w"); zin("zet het licht in de $k $w")
                zin("doe de lampen in de $k $w"); zin("doe alle lampen in de $k $w")
            }
            zin("doe alle lampen $w"); zin("doe alle lichten $w")
        }

        zin("hoe laat is het"); zin("wat is de tijd", "hoe laat is het")
        zin("welke dag is het"); zin("wat is de datum"); zin("welke datum is het", "wat is de datum")
        for (v in listOf("van", "voor")) {
            for (m in MINUTEN) zin("zet een timer $v ${GETALLEN[m]} ${if (m == 1) "minuut" else "minuten"}", "zet een timer van $m minuten")
            for (s in SECONDEN) zin("zet een timer $v ${GETALLEN[s]} seconden", "zet een timer van $s seconden")
            zin("zet een timer $v een uur", "zet een timer van 1 uur"); zin("zet een timer $v twee uur", "zet een timer van 2 uur")
            zin("zet een timer $v een half uur", "zet een timer van 30 minuten"); zin("zet een timer $v een kwartier", "zet een timer van 15 minuten")
        }
        for (t in listOf("annuleer de timer", "stop de timer", "pauzeer de timer", "hervat de timer")) zin(t)

        // Muziek en volume: die doet de tablet zelf ([Opdrachten.herken] kent ze)
        for (t in listOf("stop", "pauze", "pauzeer", "zet de muziek op pauze", "muziek op pauze", "stop de muziek", "muziek uit",
            "speel verder", "ga verder", "hervat", "muziek aan", "speel de muziek verder",
            "volgende", "volgend nummer", "het volgende nummer", "volgende nummer", "sla over",
            "vorige", "vorig nummer", "het vorige nummer", "vorige nummer",
            "harder", "zachter", "zet het geluid harder", "zet het geluid zachter", "zet de muziek harder", "zet de muziek zachter",
            "volume omhoog", "volume omlaag")) zin(t)
        for (n in 0..10) { zin("zet het volume op ${GETALLEN[n]}", "zet het volume op $n"); zin("volume op ${GETALLEN[n]}", "zet het volume op $n") }
        return z
    }

    /**
     * Wat Vosk met deze zinnen hoorde ({"text": ..., "result": [{"conf": ...}]}) → de opdracht, of null als het
     * geen (zeker) commando was. [unk] aan het begin of eind is ruis; [unk] in het midden betekent: iets anders gezegd.
     */
    fun herken(tekst: String, zekerheden: List<Double>, zinnen: Map<String, String>): String? {
        val woorden = tekst.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val van = woorden.indexOfFirst { it != "[unk]" }
        val tot = woorden.indexOfLast { it != "[unk]" }
        if (van < 0) return null
        val kern = woorden.subList(van, tot + 1)
        if ("[unk]" in kern) return null
        val zeker = zekerheden.drop(van).take(kern.size)
        if (zeker.isNotEmpty() && (zeker.average() < 0.8 || zeker.min() < 0.5)) return null
        return zinnen[kern.joinToString(" ")]
    }
}
