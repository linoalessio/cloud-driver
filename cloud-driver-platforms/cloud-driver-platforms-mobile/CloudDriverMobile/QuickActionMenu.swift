import SwiftUI

/// One action inside a `QuickActionMenu` - the same "title/icon/role/action" shape a native
/// `Menu`'s own `Button`s carry, but as a plain value so the exact same action list can back both
/// a tap-triggered `Menu` (still native, for VoiceOver/keyboard/Slide Over compatibility) and this
/// custom, instantly-appearing dropdown.
struct QuickAction: Identifiable {
    let id = UUID()
    let title: String
    let systemImage: String
    let role: ButtonRole?
    let action: () -> Void

    init(_ title: String, systemImage: String, role: ButtonRole? = nil, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.role = role
        self.action = action
    }
}

/// A plain, instantly-appearing dropdown of `QuickAction`s - deliberately **not** iOS's native
/// long-press "peek and pop" `.contextMenu` interaction (which previews/blurs the pressed view
/// first, with a real delay, before revealing its menu at a position iOS itself chooses) - Lino
/// asked for a menu that appears immediately, right at the exact point pressed, like an ordinary
/// dropdown. See `FileBrowserView`'s own `quickActionGesture`/`quickActionMenuOverlay` for how a
/// press location is captured and this view is actually positioned.
struct QuickActionMenu: View {
    let actions: [QuickAction]
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                Button {
                    onDismiss()
                    action.action()
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: action.systemImage)
                            .frame(width: 20)
                        Text(action.title)
                        Spacer(minLength: 0)
                    }
                    .font(.subheadline)
                    .foregroundStyle(action.role == .destructive ? Color.red : CloudTheme.textPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if index != actions.count - 1 {
                    Divider()
                        .background(CloudTheme.rowDivider)
                }
            }
        }
        .frame(width: QuickActionMenu.width)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(CloudTheme.rowDivider))
        .shadow(color: .black.opacity(0.3), radius: 16, y: 6)
    }

    /// Fixed width used both for rendering and for `FileBrowserView`'s own on-screen clamping math
    /// (which needs to estimate this view's size *before* it's actually laid out, to keep it from
    /// rendering partly off-screen).
    static let width: CGFloat = 220
    /// Per-row height used the same clamping-estimate way `width` is.
    static let rowHeight: CGFloat = 42
}
