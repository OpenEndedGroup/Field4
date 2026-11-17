import SwiftUI

/// One `three { label(...) }`, drawn as the browser viewer's `.scene-label` CSS draws it: a boxed
/// `chip`, a small dim `tag`, or a `diamond` marker.
struct ThreeLabelView: View {
    let label: ThreeLabel

    var body: some View {
        switch label.style ?? "tag" {
        case "diamond":
            Rectangle()
                .fill(cssColor(label.color) ?? .white)
                .frame(width: 10, height: 10)
                .overlay(Rectangle().stroke(Color.white.opacity(0.54), lineWidth: 1))
                .rotationEffect(.degrees(45))
                .padding(4)
        case "chip":
            Text(label.text ?? "")
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(cssColor(label.color) ?? Color(white: 0.95))
                .padding(.horizontal, 6)
                .frame(minWidth: 26, minHeight: 26)
                .background(cssColor(label.background) ?? Color.black.opacity(0.62))
                .overlay(Rectangle().stroke(cssColor(label.border) ?? Color.white.opacity(0.18), lineWidth: 1))
        default:
            Text(label.text ?? "")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(cssColor(label.color) ?? Color(white: 0.9).opacity(0.72))
                .padding(.horizontal, 5).padding(.vertical, 2)
                .background(cssColor(label.background) ?? Color.black.opacity(0.48))
                .overlay(Rectangle().stroke(cssColor(label.border) ?? Color.white.opacity(0.08), lineWidth: 1))
        }
    }
}

/// `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(...)` and `rgba(...)`; anything else is `nil`, so the
/// style's own colour is used.
func cssColor(_ css: String?) -> Color? {
    guard var s = css?.trimmingCharacters(in: .whitespaces).lowercased(), !s.isEmpty else { return nil }
    if s.hasPrefix("#") {
        s.removeFirst()
        if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
        guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else { return nil }
        let a = s.count == 8 ? Double(v & 0xff) / 255 : 1
        let rgb = s.count == 8 ? v >> 8 : v
        return Color(red: Double((rgb >> 16) & 0xff) / 255, green: Double((rgb >> 8) & 0xff) / 255,
                     blue: Double(rgb & 0xff) / 255, opacity: a)
    }
    if s.hasPrefix("rgb"), let open = s.firstIndex(of: "("), let close = s.lastIndex(of: ")") {
        let parts = s[s.index(after: open)..<close].split(whereSeparator: { $0 == "," || $0 == " " || $0 == "/" })
            .compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count >= 3 else { return nil }
        return Color(red: parts[0] / 255, green: parts[1] / 255, blue: parts[2] / 255, opacity: parts.count > 3 ? parts[3] : 1)
    }
    return nil
}
