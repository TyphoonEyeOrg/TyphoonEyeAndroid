package seamain.org.typhoonEye.data.util

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * QWeather JWT (EdDSA / Ed25519) — aligned with
 * https://dev.qweather.com/en/docs/configuration/authentication/
 *
 * Header: { "alg": "EdDSA", "kid": "<Credential ID>" }
 * Payload: { "iss": "<Developer ID>", "sub": "<Project ID>", "iat": ..., "exp": ... } and
 * nothing else (QWeather treats other claims as reserved).
 * Authorization: Bearer <jwt>
 */
object JwtUtils {

    /** Token lifetime; QWeather allows at most 86,400 s. */
    const val DEFAULT_TTL_SECONDS = 900L

    /**
     * @param kid Credential ID from QWeather Console
     * @param projectId Project ID (`sub`)
     * @param developerId Developer ID from the console's account page (`iss`)
     * @param privateKeyPem Ed25519 PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----` ...)
     */
    fun generateQWeatherJwt(
        kid: String,
        projectId: String,
        developerId: String,
        privateKeyPem: String,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        require(kid.isNotBlank()) { "QWeather kid (Credential ID) is blank" }
        require(projectId.isNotBlank()) { "QWeather projectId is blank" }
        require(developerId.isNotBlank()) { "QWeather developerId is blank" }
        require(privateKeyPem.isNotBlank()) { "QWeather private key is blank" }

        val iat = nowMs / 1000 - 30
        val exp = iat + ttlSeconds

        val headerJson = JSONObject()
            .put("alg", "EdDSA")
            .put("kid", kid.trim())
            .toString()
        val payloadJson = JSONObject()
            .put("iss", developerId.trim())
            .put("sub", projectId.trim())
            .put("iat", iat)
            .put("exp", exp)
            .toString()

        val headerBase64 = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
        val payloadBase64 = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerBase64.$payloadBase64"

        val signature = signEd25519(signingInput.toByteArray(StandardCharsets.UTF_8), privateKeyPem)
        return "$signingInput.${base64UrlEncode(signature)}"
    }

    private fun signEd25519(data: ByteArray, privateKeyPem: String): ByteArray {
        val keyBytes = decodePemPrivateKey(privateKeyPem)
        val privateKey = EdDSAPrivateKey(PKCS8EncodedKeySpec(keyBytes))
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        engine.initSign(privateKey)
        engine.update(data)
        return engine.sign()
    }

    private fun decodePemPrivateKey(pem: String): ByteArray {
        val normalized = pem
            .replace("\\n", "\n")
            .replace("\\r", "")
            .trim()
        val body = normalized
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN ED25519 PRIVATE KEY-----", "")
            .replace("-----END ED25519 PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        require(body.isNotEmpty()) { "QWeather private key PEM is empty after parsing" }
        return Base64.getDecoder().decode(body)
    }

    private fun base64UrlEncode(input: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input)
    }
}
