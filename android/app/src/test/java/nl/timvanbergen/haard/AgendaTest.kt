package nl.timvanbergen.haard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** De Nederlandse zinnen voor de agenda: wat Haard ervan maakt. */
class AgendaTest {

    private fun nieuw(z: String) = Agenda.herken(z) as Agenda.Vraag.Nieuw
    private fun uurMin(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 100 + c.get(Calendar.MINUTE)
    private fun dagenVanaf(c: Calendar): Int {
        val v = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12) }
        val d = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12) }
        return Math.round((d.timeInMillis - v.timeInMillis) / 86_400_000.0).toInt()
    }

    @Test fun toevoegen() {
        nieuw("Zet morgen om drie uur tandarts in mijn agenda.").let { assertEquals("Tandarts", it.titel); assertEquals(1500, uurMin(it.start)); assertEquals(1, dagenVanaf(it.start)) }
        nieuw("Zet overmorgen om half vier voetbal in mijn agenda").let { assertEquals("Voetbal", it.titel); assertEquals(1530, uurMin(it.start)); assertEquals(2, dagenVanaf(it.start)) }
        nieuw("Zet een afspraak met de kapper op vrijdag om 10 uur 's ochtends in mijn agenda").let { assertEquals("Kapper", it.titel); assertEquals(1000, uurMin(it.start)) }
        nieuw("Zet op 15 december om 19:30 kerstdiner in de agenda").let { assertEquals("Kerstdiner", it.titel); assertEquals(1930, uurMin(it.start)); assertEquals(Calendar.DECEMBER, it.start.get(Calendar.MONTH)); assertEquals(15, it.start.get(Calendar.DAY_OF_MONTH)) }
        nieuw("Zet morgen verjaardag oma in mijn agenda").let { assertEquals("Verjaardag oma", it.titel); assertTrue(it.heleDag) }
        nieuw("Zet morgenavond om 8 uur film kijken in mijn agenda").let { assertEquals("Film kijken", it.titel); assertEquals(2000, uurMin(it.start)) }
        nieuw("Zet morgen om kwart over twee wiskunde toets in mijn agenda").let { assertEquals("Wiskunde toets", it.titel); assertEquals(1415, uurMin(it.start)) }
    }

    @Test fun vragen() {
        assertTrue(Agenda.herken("Wat staat er morgen op mijn agenda?") is Agenda.Vraag.Periode)
        assertEquals("morgen", (Agenda.herken("Wat moet ik morgen doen?") as Agenda.Vraag.Periode).wanneer)
        assertEquals("vandaag", (Agenda.herken("Wat staat er vandaag in mijn agenda") as Agenda.Vraag.Periode).wanneer)
        assertEquals("op vrijdag", (Agenda.herken("Heb ik vrijdag afspraken?") as Agenda.Vraag.Periode).wanneer)
        assertTrue(Agenda.herken("Wat is mijn volgende afspraak?") === Agenda.Vraag.Volgende)
    }

    @Test fun nietDeAgenda() {
        assertNull(Agenda.herken("Heb ik de lamp aangezet?"))
        assertNull(Agenda.herken("Wat voor weer wordt het morgen?"))
        assertNull(Agenda.herken("Zet mijn lamp op rood"))
        assertNull(Agenda.herken("Hoe laat is het?"))
    }
}
