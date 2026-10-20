import SwiftUI

/// One small "metric" box: a caption-sized title over a big value, like
/// Streamlit's `st.metric`. Used by Tonight's quick stats and the Map's
/// stats strip.
@MainActor
struct QuickStatTile: View {
    let title: String
    let value: String
    var alignment: HorizontalAlignment

    init(title: String, value: String, alignment: HorizontalAlignment = .leading) {
        self.title = title
        self.value = value
        self.alignment = alignment
    }

    var body: some View {
        VStack(alignment: alignment, spacing: 4) {
            Text(verbatim: title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
            Text(verbatim: value)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: frameAlignment)
        .accessibilityElement(children: .combine)
    }

    /// Centered tiles center their text; everything else hugs the leading edge.
    private var frameAlignment: Alignment {
        alignment == .center ? Alignment.center : Alignment.leading
    }
}
