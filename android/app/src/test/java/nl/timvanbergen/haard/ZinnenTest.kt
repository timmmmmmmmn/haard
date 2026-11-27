package nl.timvanbergen.haard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZinnenTest {
    private val zinnen = Zinnen.bouw(
        listOf(Zinnen.Apparaat(listOf("Lamp Tim kamer", "mijn lamp"), "light"), Zinnen.Apparaat(listOf("Temperatuur"), "sensor")),
        listOf("Slaapkamer Tim"),
    )

    @Test fun commandos() {
        assertEquals("zet mijn lamp aan", zinnen["zet mijn lamp aan"])
        assertEquals("zet de lamp tim kamer op 50 procent", zinnen["zet de lamp tim kamer op vijftig procent"])
        assertEquals("zet mijn lamp op rood", zinnen["maak mijn lamp rood"])
        assertEquals("zet een timer van 10 minuten", zinnen["zet een timer van tien minuten"])
        assertEquals("doe het licht in de slaapkamer tim uit", zinnen["doe het licht in de slaapkamer tim uit"])
        assertEquals("zet het volume op 5", zinnen["volume op vijf"])
        assertNull(zinnen["zet temperatuur aan"]) // een sensor zet je niet aan
    }

    @Test fun herkennen() {
        assertEquals("zet mijn lamp aan", Zinnen.herken("[unk] zet mijn lamp aan", listOf(0.6, 1.0, 1.0, 1.0, 1.0), zinnen))
        assertNull(Zinnen.herken("[unk] van [unk]", listOf(0.9, 0.9, 0.9), zinnen)) // een vraag, geen commando
        assertNull(Zinnen.herken("zet mijn [unk] aan", listOf(1.0, 1.0, 1.0, 1.0), zinnen))
        assertNull(Zinnen.herken("zet mijn lamp aan", listOf(0.4, 0.5, 0.6, 0.5), zinnen)) // te onzeker
        assertNull(Zinnen.herken("", emptyList(), zinnen))
    }
}
