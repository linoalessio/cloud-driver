// swift-tools-version: 5.9
import PackageDescription

// cloud-driver-swift - the Swift networking/session client library for cloud-driver's
// JWT-authenticated REST/WebSocket API, extracted (2026-09-07) out of
// cloud-driver-platforms-mobile's own "Networking" folder so that app can stay GUI-only - the
// same "client SDK per ecosystem" role cloud-driver-maven (Java) and cloud-driver-python (Python)
// play, all three siblings under cloud-driver-multiplatform. No third-party dependencies -
// Foundation/CryptoKit/Security only, matching this module's pre-extraction source exactly.
let package = Package(
    name: "CloudDriverSwift",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(
            name: "CloudDriverSwift",
            targets: ["CloudDriverSwift"]
        ),
    ],
    targets: [
        .target(
            name: "CloudDriverSwift"
        ),
    ]
)
