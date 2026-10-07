import Flutter
import Security
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "MeridianDeviceKey") {
      DeviceKeyChannel.register(messenger: registrar.messenger())
    }
  }
}

/// Device key in the Secure Enclave (channel io.meridian.agent/device_key, Dart PlatformDeviceKeyStore): P-256,
/// non-exportable, usable only on this device after first unlock. Returns SubjectPublicKeyInfo (base64) and DER
/// ECDSA signatures (converted to P1363 in Dart). Falls back to "unavailable" on the simulator (no Secure Enclave),
/// where the Dart side uses the software key. Not compiled in this repository's CI (no Xcode); see mobile/README.md.
enum DeviceKeyChannel {
  static func register(messenger: FlutterBinaryMessenger) {
    let channel = FlutterMethodChannel(name: "io.meridian.agent/device_key", binaryMessenger: messenger)
    channel.setMethodCallHandler { call, result in
      let args = call.arguments as? [String: Any] ?? [:]
      let tag = ((args["alias"] as? String) ?? "meridian-agent-device-key").data(using: .utf8)!
      do {
        switch call.method {
        case "isAvailable":
          #if targetEnvironment(simulator)
            result(false)
          #else
            result(true)
          #endif
        case "publicKey":
          result(try find(tag).map(spki))
        case "generate":
          delete(tag)
          result(try spki(generate(tag)))
        case "sign":
          guard let key = try find(tag), let data = (args["data"] as? FlutterStandardTypedData)?.data else {
            throw NSError(domain: "device_key", code: 1)
          }
          var error: Unmanaged<CFError>?
          guard let sig = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, data as CFData, &error) else {
            throw error!.takeRetainedValue() as Error
          }
          result(FlutterStandardTypedData(bytes: sig as Data))
        case "delete":
          delete(tag)
          result(nil)
        default:
          result(FlutterMethodNotImplemented)
        }
      } catch {
        result(FlutterError(code: "device_key", message: error.localizedDescription, details: nil))
      }
    }
  }

  private static func generate(_ tag: Data) throws -> SecKey {
    let access = SecAccessControlCreateWithFlags(
      nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, nil)!
    let attrs: [String: Any] = [
      kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
      kSecAttrKeySizeInBits as String: 256,
      kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
      kSecPrivateKeyAttrs as String: [
        kSecAttrIsPermanent as String: true,
        kSecAttrApplicationTag as String: tag,
        kSecAttrAccessControl as String: access,
      ],
    ]
    var error: Unmanaged<CFError>?
    guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &error) else {
      throw error!.takeRetainedValue() as Error
    }
    return key
  }

  private static func find(_ tag: Data) throws -> SecKey? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassKey,
      kSecAttrApplicationTag as String: tag,
      kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
      kSecReturnRef as String: true,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    return (item as! SecKey)
  }

  private static func delete(_ tag: Data) {
    let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag]
    SecItemDelete(query as CFDictionary)
  }

  /// SubjectPublicKeyInfo (base64) of the private key's public half (X9.63 point with the P-256 SPKI prefix).
  private static func spki(_ privateKey: SecKey) throws -> String {
    guard let pub = SecKeyCopyPublicKey(privateKey),
      let point = SecKeyCopyExternalRepresentation(pub, nil) as Data?
    else { throw NSError(domain: "device_key", code: 2) }
    let prefix: [UInt8] = [
      0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
      0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ]
    return (Data(prefix) + point).base64EncodedString()
  }
}
