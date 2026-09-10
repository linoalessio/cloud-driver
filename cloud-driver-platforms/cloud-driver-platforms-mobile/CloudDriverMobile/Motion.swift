import SwiftUI

/// The motion layer of this app's visual language (see `Theme.swift` for the static half):
/// a living, slowly-drifting background, springy press feedback on every tappable row/button,
/// staggered card entrances, a floating 3D logo, and a shimmering storage bar. Every continuous
/// animation in this file checks `accessibilityReduceMotion` and falls back to its static
/// appearance - the same rule the project's homepage applies via `prefers-reduced-motion`.

/// The animated evolution of `CloudTheme.backgroundGradient`'s static blobs: the same diagonal
/// navy-to-royal-blue canvas, but with three soft radial glows that drift and breathe on
/// independent, very slow sine paths - deliberately radial gradients rather than `.blur`ed
/// circles, so re-rendering every frame costs a plain gradient fill instead of a live 70-80pt
/// gaussian blur. With Reduce Motion on, renders one static frame of the exact same composition.
struct AuroraBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [CloudTheme.backgroundTop, CloudTheme.backgroundBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            GeometryReader { proxy in
                if reduceMotion {
                    blobs(in: proxy.size, time: 0)
                } else {
                    TimelineView(.animation(minimumInterval: 1.0 / 24.0)) { context in
                        blobs(in: proxy.size, time: context.date.timeIntervalSinceReferenceDate)
                    }
                }
            }
        }
        .ignoresSafeArea()
    }

    private func blobs(in size: CGSize, time t: TimeInterval) -> some View {
        let w = size.width
        let h = size.height
        return ZStack {
            glow(Color.white, opacity: 0.07, diameter: w * 1.15)
                .scaleEffect(1 + 0.06 * sin(t * 0.23))
                .offset(
                    x: -w * 0.35 + w * 0.04 * sin(t * 0.31),
                    y: -h * 0.18 + h * 0.03 * cos(t * 0.27)
                )
            glow(CloudTheme.accent, opacity: 0.20, diameter: w * 1.0)
                .scaleEffect(1 + 0.08 * sin(t * 0.19 + 2))
                .offset(
                    x: w * 0.55 + w * 0.05 * cos(t * 0.24),
                    y: h * 0.62 + h * 0.035 * sin(t * 0.21 + 1)
                )
            glow(CloudTheme.iconStorage, opacity: 0.12, diameter: w * 0.9)
                .scaleEffect(1 + 0.07 * sin(t * 0.26 + 4))
                .offset(
                    x: w * 0.15 + w * 0.06 * sin(t * 0.17 + 3),
                    y: h * 0.25 + h * 0.04 * cos(t * 0.22 + 2)
                )
        }
    }

    private func glow(_ color: Color, opacity: Double, diameter: CGFloat) -> some View {
        Circle()
            .fill(RadialGradient(
                colors: [color.opacity(opacity), color.opacity(0)],
                center: .center,
                startRadius: 0,
                endRadius: diameter / 2
            ))
            .frame(width: diameter, height: diameter)
    }
}

/// Springy scale-plus-dim press feedback for any tappable surface - replaces the inert
/// `.buttonStyle(.plain)` on rows and icon buttons so every touch visibly responds. Press
/// feedback stays on under Reduce Motion (it is a direct response to the user's own touch,
/// not ambient movement - the same reason system buttons still highlight).
struct CloudPressStyle: ButtonStyle {
    var scale: CGFloat = 0.97

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// Staggered entrance for the widget cards on a screen: each card fades in while sliding up and
/// un-shrinking slightly, delayed by its `index` so a screen's cards cascade instead of popping
/// in as one block. One-shot per view identity (`isShown` guards re-`onAppear` from tab
/// switches, since `TabView` keeps every tab's hierarchy alive). Reduce Motion collapses this
/// to a plain fade with no movement.
private struct CardEntranceModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: Int
    @State private var isShown = false

    func body(content: Content) -> some View {
        content
            .opacity(isShown ? 1 : 0)
            .offset(y: isShown || reduceMotion ? 0 : 24)
            .scaleEffect(isShown || reduceMotion ? 1 : 0.97)
            .onAppear {
                guard !isShown else { return }
                withAnimation(.spring(response: 0.55, dampingFraction: 0.8).delay(Double(index) * 0.07)) {
                    isShown = true
                }
            }
    }
}

/// A slow, continuous vertical bob for decorative glyphs (the empty-state icons) - just enough
/// drift to keep an otherwise-dead screen breathing. Static under Reduce Motion.
private struct GentleFloatModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isUp = false

    func body(content: Content) -> some View {
        content
            .offset(y: reduceMotion ? 0 : (isUp ? -6 : 6))
            .animation(
                reduceMotion ? nil : .easeInOut(duration: 2.2).repeatForever(autoreverses: true),
                value: isUp
            )
            .onAppear {
                if !reduceMotion { isUp = true }
            }
    }
}

/// A soft highlight band sweeping periodically across whatever it's applied to (the storage
/// usage bar) - clipped to the content's own bounds. Renders nothing under Reduce Motion.
private struct ShimmerModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay {
                if !reduceMotion {
                    GeometryReader { proxy in
                        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
                            let period = 2.8
                            let phase = context.date.timeIntervalSinceReferenceDate
                                .truncatingRemainder(dividingBy: period) / period
                            let bandWidth = proxy.size.width * 0.4
                            LinearGradient(
                                colors: [.clear, .white.opacity(0.35), .clear],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                            .frame(width: bandWidth)
                            .offset(x: -bandWidth + phase * (proxy.size.width + 2 * bandWidth))
                        }
                    }
                    .allowsHitTesting(false)
                }
            }
            .clipped()
    }
}

extension View {
    /// See `CardEntranceModifier`.
    func cardEntrance(index: Int) -> some View {
        modifier(CardEntranceModifier(index: index))
    }

    /// See `GentleFloatModifier`.
    func gentleFloat() -> some View {
        modifier(GentleFloatModifier())
    }

    /// See `ShimmerModifier`.
    func shimmer() -> some View {
        modifier(ShimmerModifier())
    }
}

/// The login screen's app mark, made three-dimensional: the same rounded-square cloud glyph,
/// now swaying gently around both the Y and X axes (a real perspective `rotation3DEffect`, not
/// a flat rotation), bobbing vertically, and casting an accent-colored glow whose radius
/// breathes with the bob - reads as a lit object floating in the scene rather than a flat
/// sticker. Under Reduce Motion it renders once, statically, with the same glow.
struct FloatingCloudLogo: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            mark.shadow(color: CloudTheme.accent.opacity(0.5), radius: 18, y: 10)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                mark
                    .rotation3DEffect(.degrees(sin(t * 0.9) * 9), axis: (x: 0, y: 1, z: 0), perspective: 0.6)
                    .rotation3DEffect(.degrees(cos(t * 0.7) * 4), axis: (x: 1, y: 0, z: 0), perspective: 0.6)
                    .offset(y: sin(t * 1.3) * 5)
                    .shadow(
                        color: CloudTheme.accent.opacity(0.5),
                        radius: 18 + 4 * sin(t * 1.3),
                        y: 12 - sin(t * 1.3) * 3
                    )
            }
        }
    }

    private var mark: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(CloudTheme.accent.gradient)
            .frame(width: 72, height: 72)
            .overlay {
                Image(systemName: "cloud.fill")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(.white)
            }
    }
}
