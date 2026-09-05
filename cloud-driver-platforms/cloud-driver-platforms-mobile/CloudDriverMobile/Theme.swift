import SwiftUI

/// Visual language for this app, deliberately modeled on Apple's own iCloud.com web dashboard
/// (a deep blue gradient canvas behind translucent "glass" widget cards, each with a colored
/// glyph-icon + title header) rather than default iOS list/form chrome. Every screen renders on
/// top of `CloudTheme.backgroundGradient`; grouped content lives inside a `CloudCard`, and a
/// card's rows use `CloudRow` - see those two types below for the shared shape every screen reuses.
enum CloudTheme {
    static let backgroundTop = Color(red: 0.06, green: 0.11, blue: 0.27)
    static let backgroundBottom = Color(red: 0.14, green: 0.29, blue: 0.60)

    static let cardFill = Color(red: 0.09, green: 0.14, blue: 0.26)
    static let cardBorder = Color.white.opacity(0.09)
    static let rowDivider = Color.white.opacity(0.07)

    static let textPrimary = Color(red: 0.96, green: 0.97, blue: 0.99)
    static let textSecondary = Color(red: 0.58, green: 0.65, blue: 0.78)

    /// Matches `Assets.xcassets/AccentColor` exactly (0x0A84FF) - the one color this design shares
    /// with plain iOS system chrome (links, the tint on system controls).
    static let accent = Color(red: 0.039, green: 0.518, blue: 1.0)

    // Per-card glyph colors - mirrors the reference screenshot's convention of a distinct color
    // per widget (Photos = colorful, Drive = blue, Notes = amber, Mail = blue), not one flat tint
    // repeated everywhere.
    static let iconFolder = Color(red: 0.06, green: 0.52, blue: 0.97)
    static let iconFile = Color(red: 0.35, green: 0.58, blue: 0.98)
    static let iconAccount = Color(red: 0.98, green: 0.62, blue: 0.16)
    static let iconStorage = Color(red: 0.58, green: 0.35, blue: 0.93)
    static let iconJoined = Color(red: 0.20, green: 0.74, blue: 0.48)
    static let iconAdmin = Color(red: 0.94, green: 0.32, blue: 0.38)

    /// Headline/title font - SF Pro **Rounded**, the same design Apple's own widgets/Music/News
    /// chrome reach for, deliberately distinct from the plain-SF body/detail text below.
    static func headline(_ style: Font.TextStyle = .headline) -> Font {
        .system(style, design: .rounded).weight(.semibold)
    }

    /// The gradient canvas every screen sits on - two soft, blurred color washes over a diagonal
    /// navy-to-royal-blue gradient, echoing the reference's own organic background blobs without
    /// copying its exact shapes.
    static var backgroundGradient: some View {
        ZStack {
            LinearGradient(
                colors: [backgroundTop, backgroundBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            GeometryReader { proxy in
                Circle()
                    .fill(Color.white.opacity(0.05))
                    .frame(width: proxy.size.width * 1.1)
                    .blur(radius: 70)
                    .offset(x: -proxy.size.width * 0.35, y: -proxy.size.height * 0.18)
                Circle()
                    .fill(accent.opacity(0.16))
                    .frame(width: proxy.size.width * 0.95)
                    .blur(radius: 80)
                    .offset(x: proxy.size.width * 0.55, y: proxy.size.height * 0.62)
            }
        }
        .ignoresSafeArea()
    }
}

/// The preset colors a user can assign to an individual folder (`FileBrowserView`'s "Set color"
/// row action, added 2026-09-05) - the mobile counterpart to cloud-driver-platforms-desktop's own
/// `FolderColorOption`, using the same nine hex values so a folder recolored on one client looks
/// the same on the other. `rawValue`/`storageName` is the opaque string actually persisted
/// server-side (`Folder#getColor()`); `forName(_:)` falls back to `.blue` for `nil`/unrecognized -
/// "default blue" per this feature's own spec, matching what an unset folder already rendered as
/// via `CloudTheme.iconFolder` before per-folder color existed.
enum FolderColorOption: String, CaseIterable, Identifiable {
    case blue = "BLUE"
    case teal = "TEAL"
    case green = "GREEN"
    case indigo = "INDIGO"
    case purple = "PURPLE"
    case pink = "PINK"
    case orange = "ORANGE"
    case red = "RED"
    case gray = "GRAY"

    var id: String { rawValue }
    var storageName: String { rawValue }

    var color: Color {
        switch self {
        case .blue: return Color(red: 0.039, green: 0.518, blue: 1.0)
        case .teal: return Color(red: 0.251, green: 0.769, blue: 0.878)
        case .green: return Color(red: 0.188, green: 0.820, blue: 0.345)
        case .indigo: return Color(red: 0.369, green: 0.361, blue: 0.902)
        case .purple: return Color(red: 0.749, green: 0.353, blue: 0.949)
        case .pink: return Color(red: 1.0, green: 0.216, blue: 0.373)
        case .orange: return Color(red: 1.0, green: 0.624, blue: 0.039)
        case .red: return Color(red: 1.0, green: 0.271, blue: 0.227)
        case .gray: return Color(red: 0.557, green: 0.557, blue: 0.576)
        }
    }

    /// - Returns: the option matching `name`, or `.blue` if `nil`/unrecognized.
    static func forName(_ name: String?) -> FolderColorOption {
        guard let name, let match = FolderColorOption(rawValue: name) else { return .blue }
        return match
    }
}

/// A translucent "glass" widget card with an icon + title (+ optional subtitle) header - the one
/// grouping container every screen uses instead of a plain `List`/`Form` section, matching the
/// reference screenshot's Photos/Drive/Notes/Mail widget shape.
struct CloudCard<Content: View>: View {
    let icon: String
    let iconColor: Color
    let title: String
    var subtitle: String? = nil
    let content: Content

    init(icon: String, iconColor: Color, title: String, subtitle: String? = nil, @ViewBuilder content: () -> Content) {
        self.icon = icon
        self.iconColor = iconColor
        self.title = title
        self.subtitle = subtitle
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(iconColor.gradient)
                    .frame(width: 30, height: 30)
                    .overlay {
                        Image(systemName: icon)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(CloudTheme.headline())
                        .foregroundStyle(CloudTheme.textPrimary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(CloudTheme.textSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 10)

            content
        }
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(.ultraThinMaterial)
                .background(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(CloudTheme.cardFill.opacity(0.6))
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(CloudTheme.cardBorder, lineWidth: 1)
        )
    }
}

/// One row inside a `CloudCard` - a leading glyph, a title (+ optional subtitle), and trailing
/// content (a chevron, a byte count, an action menu, ...). `showDivider` draws the hairline
/// separator every row but the last uses.
struct CloudRow<Trailing: View>: View {
    let icon: String
    let iconColor: Color
    let title: String
    var subtitle: String? = nil
    var showDivider = true
    let trailing: Trailing

    init(icon: String, iconColor: Color, title: String, subtitle: String? = nil, showDivider: Bool = true, @ViewBuilder trailing: () -> Trailing) {
        self.icon = icon
        self.iconColor = iconColor
        self.title = title
        self.subtitle = subtitle
        self.showDivider = showDivider
        self.trailing = trailing()
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(iconColor)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(CloudTheme.textPrimary)
                        .lineLimit(1)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(CloudTheme.textSecondary)
                    }
                }
                Spacer(minLength: 8)
                trailing
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)

            if showDivider {
                Rectangle()
                    .fill(CloudTheme.rowDivider)
                    .frame(height: 1)
                    .padding(.leading, 48)
            }
        }
    }
}

/// A single translucent field, styled like one row of a `CloudCard` - used anywhere a bare text
/// field needs this design's "glass" treatment without a full `CloudCard` header around it (the
/// auth screens, and every modal sheet: `MoveToFolderSheet`, `ShareSheet`, `ChangeEmailSheet`).
struct GlassField<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .foregroundStyle(CloudTheme.textPrimary)
            .tint(CloudTheme.accent)
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(CloudTheme.cardFill.opacity(0.5))
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(CloudTheme.cardBorder, lineWidth: 1)
            )
    }
}

/// The one filled, accent-colored call-to-action button shape every screen uses (auth screens,
/// and every modal sheet).
struct PrimaryButton: View {
    let title: String
    let busy: Bool
    let disabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if busy {
                ProgressView()
                    .tint(.white)
                    .frame(maxWidth: .infinity)
            } else {
                Text(title)
                    .font(CloudTheme.headline(.body))
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 14)
        .foregroundStyle(.white)
        .background(CloudTheme.accent.gradient, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .opacity(busy || disabled ? 0.5 : 1)
        .disabled(busy || disabled)
    }
}

/// A flat label/value row for `DashboardView`'s account fields - no glyph, just two lines of text
/// left/right, the shape the reference screenshot's own account card uses (name, then email,
/// stacked, no per-field icon).
struct CloudFieldRow: View {
    let label: String
    let value: String
    var showDivider = true

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(label)
                    .foregroundStyle(CloudTheme.textSecondary)
                Spacer()
                Text(value)
                    .foregroundStyle(CloudTheme.textPrimary)
                    .multilineTextAlignment(.trailing)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)

            if showDivider {
                Rectangle()
                    .fill(CloudTheme.rowDivider)
                    .frame(height: 1)
                    .padding(.leading, 16)
            }
        }
    }
}
