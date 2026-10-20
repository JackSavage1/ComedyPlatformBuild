import SwiftUI
import UIKit

// =============================================================================
// Theme — the app's colors (spec section 1).
//
// Dark-first but follows the system light/dark setting. The accent is
// Streamlit's red (#FF4B4B), also set as the asset catalog's AccentColor.
// =============================================================================
enum Theme {
    /// #FF4B4B — buttons, today's chip, selected states.
    static let accent = Color(rgbHex: 0xFF4B4B)

    // Urgency dots (rule 3)
    static let urgencyHigh = Color(rgbHex: 0xE74C3C)
    static let urgencyMedium = Color(rgbHex: 0xF1C40F)
    static let urgencyNormal = Color(rgbHex: 0x2ECC71)

    // Status / sentiment
    /// Green — "You're going", positive tags.
    static let positive = Color(rgbHex: 0x2ECC71)
    /// Red — "Cancelled", negative tags, destructive.
    static let negative = Color(rgbHex: 0xE74C3C)
    /// Blue — neutral tags, info callouts.
    static let neutral = Color(rgbHex: 0x3498DB)
    /// Yellow/orange — warnings.
    static let warning = Color(rgbHex: 0xF1C40F)

    // Surfaces that adapt to light/dark mode
    static let background = Color(uiColor: .systemBackground)
    static let groupedBackground = Color(uiColor: .systemGroupedBackground)
    static let cardBackground = Color(uiColor: .secondarySystemBackground)
    static let secondaryText = Color(uiColor: .secondaryLabel)

    static func urgencyColor(_ urgency: Urgency) -> Color {
        switch urgency {
        case .high: return urgencyHigh
        case .medium: return urgencyMedium
        case .normal: return urgencyNormal
        }
    }

    /// Rule 17 borough colors (map pins and legend).
    static func boroughColor(_ borough: String?) -> Color {
        color(hex: BoroughRules.colorHex(for: borough))
    }

    /// Rule 14 tag colors.
    static func tagColor(_ tag: String) -> Color {
        color(hex: TagRules.sentiment(of: tag).colorHex)
    }

    static func planColor(_ status: PlanStatus) -> Color {
        switch status {
        case .going: return positive
        case .cancelled: return negative
        }
    }

    /// "#RRGGBB" (or "RRGGBB") → Color. Gray if the text isn't valid hex.
    static func color(hex: String) -> Color {
        var text = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6, let value = UInt32(text, radix: 16) else {
            return Color(rgbHex: 0x808080)
        }
        return Color(rgbHex: value)
    }
}

extension Color {
    /// A color from a 0xRRGGBB literal, e.g. `Color(rgbHex: 0xFF4B4B)`.
    init(rgbHex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((rgbHex >> 16) & 0xFF) / 255,
            green: Double((rgbHex >> 8) & 0xFF) / 255,
            blue: Double(rgbHex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
