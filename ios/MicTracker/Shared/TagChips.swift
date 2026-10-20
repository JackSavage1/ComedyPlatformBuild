import SwiftUI

/// One tag as a colored pill (rule 14 colors: green positive, red negative,
/// blue other). Unselected pills (in pickers) are outlined instead of filled.
@MainActor
struct TagPill: View {
    let tag: String
    var isSelected: Bool

    init(tag: String, isSelected: Bool = true) {
        self.tag = tag
        self.isSelected = isSelected
    }

    var body: some View {
        let color = Theme.tagColor(tag)
        Text(tag)
            .font(.caption.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .foregroundStyle(isSelected ? Color.white : color)
            .background(Capsule().fill(isSelected ? color : Color.clear))
            .overlay(Capsule().stroke(color, lineWidth: 1))
    }
}

/// A read-only, wrapping row of tag pills (Set History rows, Set Detail).
@MainActor
struct TagChips: View {
    let tags: [String]

    init(tags: [String]) {
        self.tags = tags
    }

    /// Convenience for the raw comma-separated `my_sets.tags` value.
    init(rawTags: String?) {
        self.tags = TagRules.parse(rawTags)
    }

    var body: some View {
        FlowLayout(spacing: 6, lineSpacing: 6) {
            ForEach(Array(tags.enumerated()), id: \.offset) { item in
                TagPill(tag: item.element)
            }
        }
    }
}

/// A multi-select chip picker (Log a Set → Tags, History → tag filter).
/// Tap a chip to toggle it.
@MainActor
struct TagSelector: View {
    let options: [String]
    @Binding var selection: Set<String>

    init(options: [String] = TagRules.options, selection: Binding<Set<String>>) {
        self.options = options
        self._selection = selection
    }

    var body: some View {
        FlowLayout(spacing: 8, lineSpacing: 8) {
            ForEach(options, id: \.self) { tag in
                let isOn = selection.contains(tag)
                Button {
                    if isOn {
                        selection.remove(tag)
                    } else {
                        selection.insert(tag)
                    }
                } label: {
                    TagPill(tag: tag, isSelected: isOn)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tag)
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
    }
}
