import CryptoKit
import Foundation

/// Client-side implementation of the server's chunked AES-256-GCM content scheme, for presigned
/// direct-to-client transfers: the server issues a per-file content key over the authenticated
/// API (`POST /files/upload-url`'s `encryption` object) and this type produces/reads the exact
/// stored-object layout the server's own `ChunkedAesGcmStreamingService` does - the same scheme
/// `cloud-driver-multiplatform-java`'s `ChunkedContentCipher` implements, ported to CryptoKit.
///
/// Wire format (everything big-endian), after the server-supplied header written verbatim at
/// offset 0:
///
///     baseNonce               4 random bytes (12-byte GCM nonce minus the 8-byte counter)
///     repeated chunk frames:
///       flags                 1 byte - 0x01 marks the final chunk, 0x00 any other
///       ciphertextLength      4-byte int
///       ciphertext            chunk ciphertext, 16-byte GCM tag appended
///
/// Chunk `i`'s nonce is `baseNonce || bigEndianUInt64(i)`; its associated data is
/// `associatedDataPrefix (UTF-8) || bigEndianUInt64(i) || flags`. The stream always ends with a
/// final-flagged chunk (empty when the plaintext length is an exact chunk-size multiple), and
/// `decrypt` fails closed - truncation, reordering, or trailing data all throw rather than
/// yielding a shorter or altered "valid" file.
public enum ChunkedContentCipher {

    /// A stream that violates the format or fails a chunk's authentication check.
    public enum CipherError: Error {
        /// The stream ended before a final-flagged chunk was seen, or mid-structure.
        case truncated
        /// A structural field (flags byte, chunk length) carried an impossible value.
        case malformed(String)
        /// A chunk's GCM tag did not verify - tampered, reordered, or wrong key/prefix.
        case authenticationFailed
    }

    /// GCM nonce length, in bytes.
    private static let nonceLength = 12
    /// Bytes of each chunk's nonce taken by the big-endian chunk counter.
    private static let counterLength = 8
    /// Length, in bytes, of each stream's random nonce base.
    private static let baseNonceLength = nonceLength - counterLength
    /// GCM authentication tag length, in bytes.
    private static let tagLength = 16
    /// `flags` value marking the final chunk of a stream.
    private static let flagFinal: UInt8 = 0x01
    /// `flags` value for every chunk before the final one.
    private static let flagNotFinal: UInt8 = 0x00
    /// Hard upper bound on a declared chunk ciphertext length during decryption (64 MiB).
    private static let maxChunkCiphertextLength = 1 << 26

    /// Encrypts the file at `source` into a complete stored object at `destination` (created,
    /// must not already exist as meaningful data - it is truncated): `header` verbatim, then the
    /// nonce base and chunk frames. Memory use is O(`chunkSizeBytes`).
    ///
    /// - Parameters:
    ///   - source: the plaintext file to encrypt
    ///   - destination: where the stored object is written
    ///   - keyMaterial: the raw content key from the server's `encryption.contentKeyBase64`
    ///   - header: the server-supplied header (`encryption.headerBase64`), written verbatim first
    ///   - associatedDataPrefix: the server-supplied `encryption.associatedDataPrefix`
    ///   - chunkSizeBytes: the server-supplied `encryption.chunkSizeBytes`
    public static func encrypt(source: URL, destination: URL, keyMaterial: Data, header: Data,
                               associatedDataPrefix: String, chunkSizeBytes: Int) throws {
        guard chunkSizeBytes > 0 else { throw CipherError.malformed("chunkSizeBytes must be positive") }
        let key = SymmetricKey(data: keyMaterial)
        let prefix = Data(associatedDataPrefix.utf8)

        var baseNonce = Data(count: baseNonceLength)
        let status = baseNonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, baseNonceLength, $0.baseAddress!) }
        guard status == errSecSuccess else { throw CipherError.malformed("SecRandomCopyBytes failed: \(status)") }

        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        try output.truncate(atOffset: 0)

        try output.write(contentsOf: header)
        try output.write(contentsOf: baseNonce)

        var chunkIndex: UInt64 = 0
        while true {
            let chunk = try input.read(upToCount: chunkSizeBytes) ?? Data()
            // A short read only ever means EOF, so a full chunk stays non-final - the next round
            // serves the remainder, or an empty final chunk on an exact chunk-size multiple.
            let flags = chunk.count < chunkSizeBytes ? flagFinal : flagNotFinal
            let sealed = try seal(chunk, key: key, baseNonce: baseNonce, chunkIndex: chunkIndex, flags: flags, prefix: prefix)
            var frame = Data([flags])
            frame.append(bigEndianUInt32(UInt32(sealed.count)))
            frame.append(sealed)
            try output.write(contentsOf: frame)
            chunkIndex += 1
            if flags == flagFinal { return }
        }
    }

    /// Decrypts a stored object fetched from a presigned download URL into `destination`
    /// (truncated first), verifying every chunk's authentication tag before writing any of its
    /// plaintext. The leading `headerLengthBytes` bytes (the server's wrapped-key header - the
    /// raw key arrives via the API instead) are skipped, not interpreted. Fails closed.
    ///
    /// - Parameters:
    ///   - source: the fetched stored object
    ///   - destination: where the verified plaintext is written
    ///   - keyMaterial: the raw content key from the server's `encryption.contentKeyBase64`
    ///   - associatedDataPrefix: the server-supplied `encryption.associatedDataPrefix`
    ///   - headerLengthBytes: the server-supplied `encryption.headerLengthBytes`
    public static func decrypt(source: URL, destination: URL, keyMaterial: Data,
                               associatedDataPrefix: String, headerLengthBytes: Int) throws {
        guard headerLengthBytes >= 0 else { throw CipherError.malformed("headerLengthBytes cannot be negative") }
        let key = SymmetricKey(data: keyMaterial)
        let prefix = Data(associatedDataPrefix.utf8)

        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        try output.truncate(atOffset: 0)

        try input.seek(toOffset: UInt64(headerLengthBytes))
        guard let baseNonce = try input.read(upToCount: baseNonceLength), baseNonce.count == baseNonceLength else {
            throw CipherError.truncated
        }

        var chunkIndex: UInt64 = 0
        while true {
            guard let flagsData = try input.read(upToCount: 1), flagsData.count == 1 else {
                throw CipherError.truncated
            }
            let flags = flagsData[flagsData.startIndex]
            guard flags == flagFinal || flags == flagNotFinal else {
                throw CipherError.malformed("unknown chunk flags value \(flags)")
            }
            guard let lengthData = try input.read(upToCount: 4), lengthData.count == 4 else {
                throw CipherError.truncated
            }
            let ciphertextLength = Int(bigEndianUInt32(from: lengthData))
            guard ciphertextLength >= tagLength, ciphertextLength <= maxChunkCiphertextLength else {
                throw CipherError.malformed("implausible chunk ciphertext length \(ciphertextLength)")
            }
            guard let sealed = try input.read(upToCount: ciphertextLength), sealed.count == ciphertextLength else {
                throw CipherError.truncated
            }
            let plaintext = try open(sealed, key: key, baseNonce: baseNonce, chunkIndex: chunkIndex, flags: flags, prefix: prefix)
            try output.write(contentsOf: plaintext)
            chunkIndex += 1
            if flags == flagFinal {
                if let trailing = try input.read(upToCount: 1), !trailing.isEmpty {
                    throw CipherError.malformed("trailing data after the final chunk")
                }
                return
            }
        }
    }

    /// The exact stored-object length a plaintext of `plaintextLength` bytes produces - mirrors
    /// the server's formula, letting the client verify its encrypted file against the ticket's
    /// `encryption.objectLengthBytes` before uploading.
    public static func encryptedLength(headerLengthBytes: Int, plaintextLength: Int64, chunkSizeBytes: Int) -> Int64 {
        let frames = plaintextLength / Int64(chunkSizeBytes) + 1
        let perFrameOverhead = Int64(1 + 4 + tagLength)
        return Int64(headerLengthBytes) + Int64(baseNonceLength) + frames * perFrameOverhead + plaintextLength
    }

    /// One chunk's AES-GCM seal: returns `ciphertext || tag`, the frame's payload layout.
    private static func seal(_ chunk: Data, key: SymmetricKey, baseNonce: Data, chunkIndex: UInt64,
                             flags: UInt8, prefix: Data) throws -> Data {
        let nonce = try AES.GCM.Nonce(data: baseNonce + bigEndianUInt64(chunkIndex))
        let sealedBox = try AES.GCM.seal(chunk, using: key, nonce: nonce,
                                         authenticating: associatedData(prefix: prefix, chunkIndex: chunkIndex, flags: flags))
        return sealedBox.ciphertext + sealedBox.tag
    }

    /// One chunk's AES-GCM open - the inverse of `seal`, throwing `CipherError.authenticationFailed`
    /// on a tag mismatch.
    private static func open(_ sealed: Data, key: SymmetricKey, baseNonce: Data, chunkIndex: UInt64,
                             flags: UInt8, prefix: Data) throws -> Data {
        let nonce = try AES.GCM.Nonce(data: baseNonce + bigEndianUInt64(chunkIndex))
        let ciphertext = sealed.prefix(sealed.count - tagLength)
        let tag = sealed.suffix(tagLength)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        do {
            return try AES.GCM.open(sealedBox, using: key,
                                    authenticating: associatedData(prefix: prefix, chunkIndex: chunkIndex, flags: flags))
        } catch {
            throw CipherError.authenticationFailed
        }
    }

    /// This chunk's associated data: `prefix || bigEndianUInt64(chunkIndex) || flags`.
    private static func associatedData(prefix: Data, chunkIndex: UInt64, flags: UInt8) -> Data {
        prefix + bigEndianUInt64(chunkIndex) + Data([flags])
    }

    /// `value` as 8 big-endian bytes.
    private static func bigEndianUInt64(_ value: UInt64) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    /// `value` as 4 big-endian bytes.
    private static func bigEndianUInt32(_ value: UInt32) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    /// The big-endian `UInt32` at the start of `data`.
    private static func bigEndianUInt32(from data: Data) -> UInt32 {
        data.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }
}
