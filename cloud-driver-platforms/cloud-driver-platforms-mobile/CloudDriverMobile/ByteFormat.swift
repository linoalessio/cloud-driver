import Foundation

/// A plain, always-numeric byte-count formatter - the Swift counterpart to
/// cloud-driver-platforms-desktop's `ByteFormat.kt#formatBytes` (itself a hand-ported copy of the
/// server's `Constraints#resolveBytesToUnit`, per that module's own "never depend on
/// cloud-driver-api" boundary - the same reasoning applies here).
///
/// Deliberately **not** `ByteCountFormatter`: Apple's formatter spells a zero byte count out as
/// the word "Zero" (e.g. `"Zero KB"`) instead of `"0 KB"` - documented Apple behavior, not a bug
/// in this app, but the wrong choice for a value like account storage usage that should always
/// read as a plain number.
func formatBytes(_ bytes: Int64) -> String {
    let units = ["B", "KB", "MB", "GB", "TB"]
    var value = Double(bytes)
    var unitIndex = 0
    while value >= 1024, unitIndex < units.count - 1 {
        value /= 1024
        unitIndex += 1
    }
    if unitIndex == 0 {
        return "\(Int(value)) \(units[unitIndex])"
    }
    return String(format: "%.2f %@", value, units[unitIndex])
}
