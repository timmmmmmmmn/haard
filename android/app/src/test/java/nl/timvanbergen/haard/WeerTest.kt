package nl.timvanbergen.haard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeerTest {
    @Test fun vragen() {
        assertEquals(1, Weer.herken("Wat voor weer wordt het morgen?")?.dagenVooruit)
        assertEquals(2, Weer.herken("Gaat het overmorgen regenen?")?.dagenVooruit)
        assertEquals(1, Weer.herken("Hoe koud wordt het morgen")?.dagenVooruit)
        assertNull(Weer.herken("Wat voor weer is het?"))           // nu: gaat naar Home Assistant
        assertNull(Weer.herken("Wat staat er morgen op mijn agenda?"))
        assertNull(Weer.herken("Zet morgen om drie uur tandarts in mijn agenda"))
    }

    @Test fun vertalen() {
        assertEquals("Nee, die is uit", Opdrachten.vertaalStaat("Nee, die is off"))
        assertEquals("Ja, die is aan", Opdrachten.vertaalStaat("Ja, die is on"))
        assertEquals("De lamp is niet bereikbaar", Opdrachten.vertaalStaat("De lamp is unavailable"))
        assertEquals("Ik ben online", Opdrachten.vertaalStaat("Ik ben online"))
        assertEquals("0 graden en zonnig", Opdrachten.vertaalStaat("0.1 graden en zonnig"))
        assertEquals("Het is 19 graden", Opdrachten.vertaalStaat("Het is 18,6 graden"))
    }
}
