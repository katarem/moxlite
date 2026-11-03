package es.moxlite.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthResultTest {

    @Test
    fun `lee la respuesta de startup authenticated`() {
        val body = """
            {"refresh":{"access_token":"aaa.bbb.ccc","refresh_token":"rrr","token_type":"Bearer",
             "user_name":"Katarem","user_id":"u-1","expiration":"2026-11-03T12:00:00Z","expires_in_minutes":60,
             "permissions":["adult"]},"wishList":{"deck":{"id":"x"}}}
        """.trimIndent()
        val r = AuthResult.parse(body)!!
        assertEquals("aaa.bbb.ccc", r.accessToken)
        assertEquals("rrr", r.refreshToken)
        assertEquals("Katarem", r.userName)
        assertEquals("u-1", r.userId)
        assertEquals(java.time.Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(), r.expiresAt)
    }

    @Test
    fun `sin fecha usa los minutos de validez`() {
        val r = AuthResult.parse("""{"refresh":{"access_token":"t","expires_in_minutes":30}}""", now = 1_000L)!!
        assertEquals(1_000L + 30 * 60_000, r.expiresAt)
    }

    @Test
    fun `acepta el objeto sin envolver y fechas sin zona`() {
        val r = AuthResult.parse("""{"access_token":"t","userName":"yo","expiration":"2026-01-01T10:00:00.123"}""")!!
        assertEquals("yo", r.userName)
        assertEquals(java.time.Instant.parse("2026-01-01T10:00:00.123Z").toEpochMilli(), r.expiresAt)
    }

    @Test
    fun `sin token o con basura devuelve null`() {
        assertNull(AuthResult.parse("""{"refresh":null}"""))
        assertNull(AuthResult.parse("""{"refresh":{"access_token":""}}"""))
        assertNull(AuthResult.parse("<html>Cloudflare</html>"))
        assertNull(AuthResult.parse(""))
    }
}
