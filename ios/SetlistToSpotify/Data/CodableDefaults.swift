import Foundation

/// Swift's synthesized Decodable throws on a missing key even when the property
/// has a default; kotlinx.serialization (the Android side) silently uses the
/// default. This wrapper restores that behaviour: a missing, null, or
/// wrong-typed key falls back to `Provider.defaultValue`.
protocol DefaultValueProvider {
    associatedtype Value: Decodable
    static var defaultValue: Value { get }
}

@propertyWrapper
struct DefaultCodable<Provider: DefaultValueProvider>: Decodable {
    var wrappedValue: Provider.Value
    init(wrappedValue: Provider.Value) { self.wrappedValue = wrappedValue }
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        wrappedValue = (try? c.decode(Provider.Value.self)) ?? Provider.defaultValue
    }
}

/// Encoding is symmetric and unconditional in shape: the wrapper writes its
/// wrapped value, so `{"id":"a"}` round-trips. Conditional because a provider's
/// value only needs to be Encodable for the types the store actually writes
/// (the Spotify models are decode-only and stay that way).
extension DefaultCodable: Encodable where Provider.Value: Encodable {
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(wrappedValue)
    }
}

extension KeyedDecodingContainer {
    // The piece that makes a *missing* key use the default rather than throw.
    func decode<P>(_ type: DefaultCodable<P>.Type, forKey key: Key) throws -> DefaultCodable<P> {
        (try? decodeIfPresent(type, forKey: key)) ?? nil
            ?? DefaultCodable(wrappedValue: P.defaultValue)
    }
}

enum BoolFalse: DefaultValueProvider { static let defaultValue = false }
enum IntZero: DefaultValueProvider { static let defaultValue = 0 }
enum EmptyString: DefaultValueProvider { static let defaultValue = "" }
enum LongZero: DefaultValueProvider { static let defaultValue: Int64 = 0 }
enum ExpiresDefault: DefaultValueProvider { static let defaultValue: Double = 3600 }
enum EmptyArray<Element: Decodable>: DefaultValueProvider { static var defaultValue: [Element] { [] } }
enum EmptyStringMap: DefaultValueProvider { static var defaultValue: [String: String] { [:] } }
