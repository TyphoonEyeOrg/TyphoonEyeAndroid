package seamain.org.typhoonEye

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import seamain.org.typhoonEye.data.util.JwtUtils
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class JwtUtilsTest {

    private val keyPair: KeyPair = KeyPairGenerator().apply { initialize(256, SecureRandom()) }.generateKeyPair()

    private fun samplePem(): String = buildString {
        append("-----BEGIN PRIVATE KEY-----\n")
        append(Base64.getEncoder().encodeToString(keyPair.private.encoded))
        append("\n-----END PRIVATE KEY-----")
    }

    private fun part(jwt: String, i: Int) = JSONObject(String(Base64.getUrlDecoder().decode(jwt.split(".")[i])))

    @Test
    fun header_isExactlyAlgAndKid() {
        val jwt = JwtUtils.generateQWeatherJwt("CREDENTIAL_ID", "PROJECT_ID", "DEVELOPER_ID", samplePem())
        val header = part(jwt, 0)
        assertEquals(setOf("alg", "kid"), header.keySet())
        assertEquals("EdDSA", header.getString("alg"))
        assertEquals("CREDENTIAL_ID", header.getString("kid"))
    }

    @Test
    fun payload_isExactlyIssSubIatExp() {
        val nowMs = 1_760_000_000_000L
        val jwt = JwtUtils.generateQWeatherJwt(" CREDENTIAL_ID ", " PROJECT_ID ", " DEVELOPER_ID ", samplePem(), nowMs = nowMs)
        val payload = part(jwt, 1)
        assertEquals(setOf("iss", "sub", "iat", "exp"), payload.keySet())
        assertEquals("DEVELOPER_ID", payload.getString("iss"))
        assertEquals("PROJECT_ID", payload.getString("sub"))
        assertEquals(nowMs / 1000 - 30, payload.getLong("iat"))
        assertEquals(payload.getLong("iat") + JwtUtils.DEFAULT_TTL_SECONDS, payload.getLong("exp"))
    }

    @Test
    fun signature_verifiesWithThePublicKey() {
        val jwt = JwtUtils.generateQWeatherJwt("kid", "sub", "iss", samplePem())
        val (h, p, s) = jwt.split(".")
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initVerify(keyPair.public)
        engine.update("$h.$p".toByteArray())
        assertTrue(engine.verify(Base64.getUrlDecoder().decode(s)))
    }

    @Test
    fun rejectsBlankParts() {
        val pem = samplePem()
        assertThrows(IllegalArgumentException::class.java) { JwtUtils.generateQWeatherJwt("kid", "sub", "iss", "   ") }
        assertThrows(IllegalArgumentException::class.java) { JwtUtils.generateQWeatherJwt("kid", "sub", " ", pem) }
        assertThrows(IllegalArgumentException::class.java) { JwtUtils.generateQWeatherJwt("kid", "", "iss", pem) }
        assertThrows(IllegalArgumentException::class.java) { JwtUtils.generateQWeatherJwt("", "sub", "iss", pem) }
    }

    @Test
    fun acceptsEscapedNewlines() {
        val pem = samplePem().replace("\n", "\\n")
        assertEquals(3, JwtUtils.generateQWeatherJwt("kid", "sub", "iss", pem).split(".").size)
    }
}
