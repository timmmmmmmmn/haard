package nl.timvanbergen.haard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wat Haard zelf herkent: muziek starten en zinnen versimpelen voor Home Assistant. */
class OpdrachtenTest {

    @Test fun speel() {
        assertEquals("Frenna" to null, Opdrachten.speelVerzoek("Speel Frenna."))
        assertEquals("Frenna" to "Sonos", Opdrachten.speelVerzoek("Spel Frenna op de Sonos"))
        assertEquals("Kanye West" to null, Opdrachten.speelVerzoek("Speelmuziek van Kanye West."))
        assertEquals("Drake" to "slaapkamer Tim", Opdrachten.speelVerzoek("Speel iets van Drake op slaapkamer Tim"))
        assertNull(Opdrachten.speelVerzoek("Speel verder"))
        assertNull(Opdrachten.speelVerzoek("Speel muziek"))
    }

    @Test fun versimpel() {
        assertEquals("Zet mijn lamp aan", Opdrachten.versimpel("Zet mijn lamp weer aan."))
        assertEquals("zet mijn lamp op rood", Opdrachten.versimpel("Doe mijn lamp aan en zet hem op rood"))
        assertEquals("zet de lamp op 100 procent", Opdrachten.versimpel("Maak de lamp wat feller"))
        assertEquals("zet de lamp op 30 procent", Opdrachten.versimpel("Dim de lamp"))
        assertEquals("Wat voor weer wordt het morgen", Opdrachten.versimpel("Wat voor weer wordt het morgen?"))
    }
}
