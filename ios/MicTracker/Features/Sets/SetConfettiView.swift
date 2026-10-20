import SwiftUI

// =============================================================================
// SetConfettiView — a quick celebratory burst after logging a set (the
// mobile version of Streamlit's st.balloons()).
//
// About 30 emoji start just above the screen and fall past the bottom with
// a little spin, each with its own random position, size, speed and delay.
// The random values are stored in @State so they're picked only once, when
// the view first appears — otherwise every redraw would reshuffle them.
// Add it as an overlay and remove it after a couple of seconds.
// =============================================================================
@MainActor
struct SetConfettiView: View {
    @State private var pieces: [SetConfettiPiece] = SetConfettiPiece.makeBurst(count: 30)
    @State private var hasFallen = false

    init() {}

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                ForEach(pieces) { piece in
                    Text(verbatim: piece.symbol)
                        .font(.system(size: piece.size))
                        .rotationEffect(.degrees(hasFallen ? piece.spin : 0))
                        .position(
                            x: proxy.size.width * piece.x,
                            y: hasFallen ? proxy.size.height + 60 : -40
                        )
                        .animation(.easeIn(duration: piece.duration).delay(piece.delay), value: hasFallen)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear {
            hasFallen = true
        }
    }
}

/// One falling emoji.
struct SetConfettiPiece: Identifiable {
    let id: Int
    let symbol: String
    /// Horizontal position as a fraction of the width (0…1).
    let x: CGFloat
    let size: CGFloat
    /// Degrees of rotation by the time it lands.
    let spin: Double
    /// Seconds to fall.
    let duration: Double
    /// Seconds before it starts falling.
    let delay: Double

    private static let symbols = ["🎉", "🎤", "⭐️", "🔥", "😂", "✨", "🎊"]

    static func makeBurst(count: Int) -> [SetConfettiPiece] {
        (0..<count).map { index in
            SetConfettiPiece(
                id: index,
                symbol: symbols[index % symbols.count],
                x: CGFloat.random(in: 0.05...0.95),
                size: CGFloat.random(in: 18...32),
                spin: Double.random(in: -360...360),
                duration: Double.random(in: 1.2...2.0),
                delay: Double.random(in: 0...0.4)
            )
        }
    }
}
