package tech.iamtitan.app

import tech.iamtitan.app.net.jvmSha256Hex
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language TLS-pin contract (AG-TLS/AD-9). The Android/JVM transport pins
 * `sha256(leaf-cert-DER)`; the Python agent computes the same value via
 * `tls.cert_pin = sha256(ssl.PEM_cert_to_DER_cert(pem))`. Both hash the leaf DER,
 * so they MUST agree — a drift would silently un-pin the channel.
 *
 * The fixture cert + EXPECTED_PIN were produced by `openssl` and verified equal by
 * the Python reference (`tests/test_console_pairing.py::test_pin_fixture_cross_language`),
 * 2026-06-11. The same PEM is hashed in both languages.
 */
class CertPinningTest {
    @Test
    fun pin_matches_python_reference() {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(FIXTURE_PEM.encodeToByteArray()))
        assertEquals(EXPECTED_PIN, jvmSha256Hex(cert.encoded))
    }

    companion object {
        const val EXPECTED_PIN =
            "48efea7ab78df9a63d0e78e7628b89d2999b4a4dbd0e9eb67bb5bd5b1e719b68"

        val FIXTURE_PEM =
            """
            -----BEGIN CERTIFICATE-----
            MIIDGTCCAgGgAwIBAgIUIKogW0I9+vMciONn2Bwfke78Mz4wDQYJKoZIhvcNAQEL
            BQAwHDEaMBgGA1UEAwwRdGl0YW4tcGluLWZpeHR1cmUwHhcNMjYwNjExMTkwMTM3
            WhcNMzYwNjA4MTkwMTM3WjAcMRowGAYDVQQDDBF0aXRhbi1waW4tZml4dHVyZTCC
            ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAKtjMSORK1gK0FixAAXZlI+A
            qwsxk89MXVq1ygnKVI/4hQvKGtiopzGfRAq7keLOPWZgvK93fOrqbjLnJU8ZYaaa
            GGVphoblHK9H0tjF6EHp4bGg0A3BodizFGu3NmtCvGi1Bpc9Os9Rn/QOP/JRngcp
            oz6omdGWdilP7+t/FQGhcSUFXhdI9Fu9rsK2VdAQizs98AsFnyagl0U0sxI0WadS
            5inDHuHbvRAbWGhhzM4nR3SXXMMqN+y7do8XW2moQmeCo5dJcz6F4+Qh1QeAZ2UX
            8bndwIuY3pHmS09oYL+e/t0T+1evHWf+lCO7BtO9U/zd1Y1SdgYJ6+q/vkLiRHkC
            AwEAAaNTMFEwHQYDVR0OBBYEFGua3RmJPBwm+jl8Ecc5MZl2IyWnMB8GA1UdIwQY
            MBaAFGua3RmJPBwm+jl8Ecc5MZl2IyWnMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI
            hvcNAQELBQADggEBAKJb/bsA0257AXnoGzfns01HTTx0EkmqqO/WaA/ItIZHTyNH
            H77TgWdcgdKgDUaZBOgQ0sTgiXCcR7fT9aIB1tqaSmiEph2ylYfVBsuGfBGxe/e6
            wF820dUXtRftWKy4aNFbuGhpCyvPD7g5P+AbE9pMNRRyR336IfVW7kanqhWFX+2/
            7INnoxQ44ZVlu11l0ZGsO5LLKrSxafnM96WQjZYmfZQFpA3NpSd6k5/WlZl63Bfj
            N8YPVteNbhlaSbyS7aJWQaItlfMjUTYL2lAiytk6Qbs6DfF6XkKa+KheRAfhDm77
            Rd3Wl+qPFOvy2WB/5g/cwlJsr37l1Ms2rIXGklg=
            -----END CERTIFICATE-----
            """.trimIndent()
    }
}
