package io.meridian.agent_app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Device key in the Android Keystore (channel io.meridian.agent/device_key, Dart PlatformDeviceKeyStore):
 * EC P-256, SHA-256 signing only, non-exportable, StrongBox when the device has it. Returns the public key as
 * SubjectPublicKeyInfo (base64) and DER ECDSA signatures (converted to P1363 in Dart).
 * Not compiled in this repository's CI (no Android SDK); see mobile/README.md.
 */
class MainActivity : FlutterActivity() {
    private val channel = "io.meridian.agent/device_key"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel).setMethodCallHandler { call, result ->
            try {
                val alias = call.argument<String>("alias") ?: "meridian-agent-device-key"
                when (call.method) {
                    "isAvailable" -> result.success(true)
                    "publicKey" -> result.success(publicKey(alias))
                    "generate" -> result.success(generate(alias))
                    "sign" -> result.success(sign(alias, call.argument<ByteArray>("data")!!))
                    "delete" -> { keyStore().deleteEntry(alias); result.success(null) }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("device_key", e.message, null)
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun publicKey(alias: String): String? {
        val cert = keyStore().getCertificate(alias) ?: return null
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    private fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

    private fun generate(alias: String): String {
        keyStore().deleteEntry(alias)
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(alias, true))
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(alias, false))
            gen.generateKeyPair()
        }
        return publicKey(alias)!!
    }

    private fun sign(alias: String, data: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }
}
