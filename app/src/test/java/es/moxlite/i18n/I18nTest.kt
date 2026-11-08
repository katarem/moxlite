package es.moxlite.i18n

import es.moxlite.domain.PreconType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class I18nTest {

    @Test
    fun `resuelve el idioma de la app`() {
        assertSame(Es, I18n.resolve(AppLanguage.ES, Locale.US))
        assertSame(En, I18n.resolve(AppLanguage.EN, Locale("es", "ES")))
        assertSame(Es, I18n.resolve(AppLanguage.SYSTEM, Locale("es", "ES")))
        assertSame(Es, I18n.resolve(AppLanguage.SYSTEM, Locale("ca", "ES")))
        assertSame(En, I18n.resolve(AppLanguage.SYSTEM, Locale.GERMANY))
    }

    @Test
    fun `por defecto en espanol y se puede cambiar`() {
        try {
            I18n.apply(AppLanguage.EN)
            assertEquals("Sideboard", es.moxlite.data.remote.moxfield.Boards.label("sideboard"))
            I18n.apply(AppLanguage.ES)
            assertEquals("Banquillo", es.moxlite.data.remote.moxfield.Boards.label("sideboard"))
        } finally {
            I18n.apply(AppLanguage.ES)
        }
    }

    @Test
    fun `los textos con parametros cambian entre idiomas`() {
        assertEquals("1 mazo encontrado", Es.decksFound(1))
        assertEquals("3 decks found", En.decksFound(3))
        assertNotEquals(Es.errUnreachable, En.errUnreachable)
        assertEquals("Español", Es.cardLanguageName("es"))
        assertEquals("Spanish", En.cardLanguageName("es"))
        assertEquals("zz", En.cardLanguageName("zz"))
    }

    @Test
    fun `precon formats and kinds have labels in both languages`() {
        assertEquals("Precon de Commander", Es.formatLabel("commanderPrecons"))
        assertEquals("Commander precon", En.formatLabel("commanderPrecons"))
        assertEquals("Precon", Es.formatLabel("precons"))
        PreconType.entries.forEach {
            assertNotEquals(Es.preconLabel(it), En.preconLabel(it))
        }
        assertEquals("Buscar en los mazos de @pepe", Es.searchUserDecksPlaceholder("pepe"))
    }
}
