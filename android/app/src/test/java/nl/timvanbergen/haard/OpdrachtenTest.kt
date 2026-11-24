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

class NederlandsTest {
    @org.junit.Test fun engelseRegelWeg() {
        org.junit.Assert.assertEquals("De Noordzee ligt ertussen.", Opdrachten.alleenNederlands("EN: North Sea (Noordzee)\nNL: De Noordzee ligt ertussen.", true))
        org.junit.Assert.assertEquals("", Opdrachten.alleenNederlands("EN: North Sea (Noord", false))
        org.junit.Assert.assertEquals("De Noordzee", Opdrachten.alleenNederlands("EN: North Sea\nNL: De Noordzee", false))
        org.junit.Assert.assertEquals("Het is 20:08", Opdrachten.alleenNederlands("Het is 20:08", true))
        org.junit.Assert.assertEquals("Alleen dit.", Opdrachten.alleenNederlands("EN: only this\nAlleen dit.", true))
    }
}

class BevestigingTest {
    @org.junit.Test fun belletjeOfVoorlezen() {
        listOf("Aangezet", "Uitgezet", "Kleur aangepast", "Helderheid aangepast", "Timer gezet voor 10 minuten", "Timer geannuleerd")
            .forEach { org.junit.Assert.assertTrue(it, Opdrachten.isBevestigingZin(it)) }
        listOf("Het is 20:08", "Het is dinsdag 24 november 2026", "Nee, die is uit", "Het is 3 graden")
            .forEach { org.junit.Assert.assertFalse(it, Opdrachten.isBevestigingZin(it)) }
    }
}
