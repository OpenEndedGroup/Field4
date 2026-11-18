import AppKit
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

func reviewFont(_ size: CGFloat) -> NSFont {
    NSFont(name: "Iosevka-Light", size: size) ?? NSFont.monospacedSystemFont(ofSize: size, weight: .light)
}
func registerReviewFont(root: URL, output: URL) throws {
    let font = root.appendingPathComponent("DrawingOn/Resources/Iosevka-Light.ttf")
    if FileManager.default.fileExists(atPath: font.path) {
        CTFontManagerRegisterFontsForURL(font as CFURL, .process, nil)
        let folder = output.appendingPathComponent("fonts")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: font, to: folder.appendingPathComponent(font.lastPathComponent))
        let license = root.appendingPathComponent("DrawingOn/Resources/Iosevka-LICENSE.txt")
        if FileManager.default.fileExists(atPath: license.path) { try FileManager.default.copyItem(at: license, to: folder.appendingPathComponent("LICENSE.txt")) }
    }
}
func makeContext(width: Int, height: Int) throws -> CGContext {
    guard width > 0, height > 0, width <= 20_000, height <= 30_000,
          let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { throw ReviewFailure("Cannot allocate rendering bitmap") }
    return context
}
func writePNG(_ image: CGImage, to url: URL) throws {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else { throw ReviewFailure("Cannot create \(url.path)") }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw ReviewFailure("Cannot write \(url.path)") }
}
func readPNG(_ url: URL) throws -> CGImage {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw ReviewFailure("Cannot read frame \(url.path)") }
    return image
}

func renderFrame(_ state: ReviewState, assets: FieldReferenceAssets, width: Int) throws -> CGImage {
    let projectedHeight = Double(width) * state.height / state.width
    guard projectedHeight.isFinite, projectedHeight >= 1, projectedHeight <= 30_000 else { throw ReviewFailure("Viewport aspect ratio exceeds the bitmap size limit; reduce --frame-width") }
    let height = max(1, Int(projectedHeight.rounded()))
    let context = try makeContext(width: width, height: height)
    context.setFillColor(CGColor(gray: state.light ? 1 : 0, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    // UIKit drawing is Y-down. Leave the surrounding bitmap and its PNG orientation conventional.
    context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: CGFloat(width) / state.width, y: -CGFloat(height) / state.height)
    let t = state.scene.transform
    context.concatenate(CGAffineTransform(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
    context.setBlendMode(state.light ? .plusDarker : .plusLighter)
    context.setLineCap(.round); context.setLineJoin(.round); context.setLineWidth(state.lineWidth)
    let visible = Set(state.scene.visibleLayers)
    context.saveGState()
    for layer in state.scene.layers where visible.contains(layer.id) {
        guard let reference = state.scene.references[layer.id] else { continue }
        let bytes = try reference.asset.map { try assets.read($0) } ?? Data()
        let drawing = try FieldReferenceBinding.bind(reference, bytes: bytes)
        for batch in drawing.batches {
            for start in stride(from: 0, to: batch.indices.count, by: batch.arity) {
                let vertices = (0..<batch.arity).map { batch.vertices[batch.indices[start + $0]] }
                let first = vertices[0]
                func c(_ value: Double) -> CGFloat { CGFloat(min(1, max(0, state.light ? 1-value : value))) }
                let color = CGColor(red: c(first.r), green: c(first.g), blue: c(first.b), alpha: CGFloat(min(1, max(0, first.a))))
                context.setStrokeColor(color); context.setFillColor(color)
                if batch.arity == 1 { context.fillEllipse(in: CGRect(x: first.x-1.5, y: first.y-1.5, width: 3, height: 3)) }
                else {
                    context.move(to: CGPoint(x: first.x, y: first.y))
                    for vertex in vertices.dropFirst() { context.addLine(to: CGPoint(x: vertex.x, y: vertex.y)) }
                    if batch.arity == 3 { context.closePath(); context.fillPath() } else { context.strokePath() }
                }
            }
        }
    }
    context.restoreGState()
    let ink = CGColor(gray: state.light ? 0 : 1, alpha: state.opacity)
    context.setStrokeColor(ink); context.setFillColor(ink)
    for stroke in state.scene.strokes where stroke.visible && !stroke.cancelled && visible.contains(stroke.layer) {
        guard let first = stroke.samples.first else { continue }
        if let geometry = stroke.geometry {
            if stroke.tool == .circle {
                let radius = geometry.radius ?? 0
                if radius > 0 { context.strokeEllipse(in: CGRect(x: first.pointX-radius, y: first.pointY-radius, width: 2*radius, height: 2*radius)) }
            } else {
                context.move(to: CGPoint(x: geometry.start.x, y: geometry.start.y)); context.addLine(to: CGPoint(x: geometry.end.x, y: geometry.end.y)); context.strokePath()
            }
        } else if stroke.samples.count == 1 {
            context.fillEllipse(in: CGRect(x: first.pointX-state.lineWidth/2, y: first.pointY-state.lineWidth/2, width: state.lineWidth, height: state.lineWidth))
        } else {
            context.move(to: CGPoint(x: first.pointX, y: first.pointY))
            for sample in stroke.samples.dropFirst() { context.addLine(to: CGPoint(x: sample.pointX, y: sample.pointY)) }
            context.strokePath()
        }
    }
    guard let image = context.makeImage() else { throw ReviewFailure("Cannot finish frame rendering") }
    return image
}
func shortTime(_ time: Double) -> String {
    let value = max(0, time)
    return String(format: "%02d:%04.1f", Int(value) / 60, value.truncatingRemainder(dividingBy: 60))
}
func drawText(_ text: String, in rect: CGRect, size: CGFloat, gray: CGFloat, context: CGContext) {
    context.saveGState()
    let paragraph = NSMutableParagraphStyle(); paragraph.lineBreakMode = .byTruncatingTail
    let string = NSAttributedString(string: text, attributes: [.font: reviewFont(size), .foregroundColor: NSColor(white: gray, alpha: 1), .paragraphStyle: paragraph])
    let framesetter = CTFramesetterCreateWithAttributedString(string)
    let path = CGPath(rect: rect, transform: nil)
    let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: 0, length: string.length), path, nil)
    CTFrameDraw(frame, context)
    context.restoreGState()
}
func renderContactSheets(frames: [[String: Any]], output: URL, title: String, options: ReviewOptions) throws -> [String] {
    var pages: [String] = []
    let perPage = options.columns * options.rows
    let tile = options.tileWidth, gap = 16, margin = 24, header = 88, caption = 72
    for pageStart in stride(from: 0, to: frames.count, by: perPage) {
        let page = Array(frames[pageStart..<min(frames.count, pageStart+perPage)])
        let images = try page.map { try readPNG(output.appendingPathComponent($0["image"] as! String)) }
        var rowHeights: [Int] = []
        for rowStart in stride(from: 0, to: page.count, by: options.columns) {
            rowHeights.append((rowStart..<min(page.count, rowStart+options.columns)).map { Int(Double(tile) * Double(images[$0].height) / Double(images[$0].width)) }.max()! + caption)
        }
        let width = 2*margin + options.columns*tile + (options.columns-1)*gap
        let height = header + margin + rowHeights.reduce(0,+) + (rowHeights.count-1)*gap
        let context = try makeContext(width: width, height: height)
        context.setFillColor(CGColor(gray: 0.025, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        drawText(title, in: CGRect(x: margin, y: height-51, width: width-2*margin, height: 29), size: 23, gray: 0.94, context: context)
        drawText("DRAWING ON   /   \(pageStart+1)–\(pageStart+page.count) OF \(frames.count)   /   RECORDED VIEWPORTS", in: CGRect(x: margin, y: height-76, width: width-2*margin, height: 19), size: 12, gray: 0.48, context: context)
        var rowTop = height-header
        for (index, frame) in page.enumerated() {
            let row = index / options.columns, column = index % options.columns
            if column == 0, row > 0 { rowTop -= rowHeights[row-1] + gap }
            let image = images[index], imageHeight = Int(Double(tile) * Double(image.height) / Double(image.width))
            let x = margin + column*(tile+gap), y = rowTop-imageHeight
            context.draw(image, in: CGRect(x: x, y: y, width: tile, height: imageHeight))
            context.setStrokeColor(CGColor(gray: 0.18, alpha: 1)); context.setLineWidth(0.5)
            context.stroke(CGRect(x: x, y: y, width: tile, height: imageHeight))
            let range = frame["range"] as! [String: Double]
            let label = String(format: "%03d", pageStart+index+1) + "   " + shortTime(range["start"]!) + " — " + shortTime(range["end"]!)
            drawText(label, in: CGRect(x: x, y: y-24, width: tile, height: 18), size: 13, gray: 0.88, context: context)
            let transcript = frame["text"] as? String ?? ""
            let subtitle = transcript.isEmpty ? (frame["reasons"] as! [String]).joined(separator: " · ") : transcript
            drawText(subtitle, in: CGRect(x: x, y: y-58, width: tile, height: 30), size: 12, gray: 0.52, context: context)
        }
        guard let image = context.makeImage() else { throw ReviewFailure("Cannot finish contact sheet") }
        let path = String(format: "contact-sheet-%03d.png", pages.count+1)
        try writePNG(image, to: output.appendingPathComponent(path)); pages.append(path)
    }
    return pages
}
func escapeHTML(_ text: String) -> String {
    text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
}
func writeReviewHTML(frames: [[String: Any]], speech: [[String: Any]], sheets: [String], output: URL, title: String) throws {
    let frameHTML = frames.map { frame -> String in
        let image = frame["image"] as! String, time = shortTime(frame["time"] as! Double)
        let text = escapeHTML(frame["text"] as? String ?? "")
        return "<figure><a href=\"\(image)\"><img loading=\"lazy\" src=\"\(image)\" alt=\"Viewport at \(time)\"></a><figcaption>\(time)<p>\(text)</p></figcaption></figure>"
    }.joined(separator: "\n")
    let speechHTML = speech.map { span -> String in
        let text = escapeHTML((span["transcript"] as? [String: Any])?["text"] as? String ?? "Not transcribed")
        return "<article><span>\(shortTime(span["start"] as! Double)) — \(shortTime(span["end"] as! Double))</span><p>\(text)</p><audio controls preload=\"none\" src=\"\(span["audio"] as! String)\"></audio></article>"
    }.joined(separator: "\n")
    let links = sheets.map { "<a href=\"\($0)\">\($0)</a>" }.joined(separator: " · ")
    let html = """
    <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' file:; media-src 'self' file:; font-src 'self' file:; style-src 'unsafe-inline'"><title>\(escapeHTML(title))</title>
    <style>@font-face{font-family:Iosevka;src:url('fonts/Iosevka-Light.ttf')}*{box-sizing:border-box}html{color-scheme:dark}body{margin:32px;background:#080808;color:#eee;font:16px Iosevka,monospace}h1{font-size:28px;font-weight:300}h2{font-size:20px;font-weight:300;margin-top:40px}a{color:#aaa}header p{color:#777;max-width:75ch;line-height:1.5}.frames{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:24px}figure{margin:0}img{width:100%;height:auto;display:block;border:1px solid #292929}figcaption{padding:12px 0;color:#ddd}figcaption p{font-size:14px;line-height:1.4;color:#999}article{border-top:1px solid #292929;padding:18px 0;max-width:900px}article p{line-height:1.5;color:#bbb}audio{width:min(100%,600px)}</style>
    <header><h1>\(escapeHTML(title))</h1><p>Recorded canvas states in their original viewports. Predicted ink is omitted. Speech candidates use adaptive energy detection; café noise can count as speech and quiet speech can be missed. All files are local.</p><p><a href="manifest.json">Machine-readable manifest</a> · \(links)</p></header>
    <main><h2>Drawing</h2><section class="frames">\(frameHTML)</section><h2>Speech candidates</h2>\(speechHTML.isEmpty ? "<p>No speech candidates detected.</p>" : speechHTML)</main></html>
    """
    try Data(html.utf8).write(to: output.appendingPathComponent("index.html"), options: .atomic)
}
