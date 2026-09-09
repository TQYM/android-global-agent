import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

// Deterministic 1200x630 PNG banner.
let W: CGFloat = 1200
let H: CGFloat = 630
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
guard let ctx = CGContext(data: nil, width: Int(W), height: Int(H),
                          bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
else { fatalError("ctx") }
// flip
ctx.translateBy(x: 0, y: H); ctx.scaleBy(x: 1, y: -1)

func rgb(_ h: UInt32) -> CGColor {
    CGColor(colorSpace: cs, components: [CGFloat((h>>16)&255)/255, CGFloat((h>>8)&255)/255, CGFloat(h&255)/255, 1])!
}
func rgba(_ h: UInt32, _ a: CGFloat) -> CGColor {
    CGColor(colorSpace: cs, components: [CGFloat((h>>16)&255)/255, CGFloat((h>>8)&255)/255, CGFloat(h&255)/255, a])!
}
func rrPath(_ x: CGFloat,_ y: CGFloat,_ w: CGFloat,_ h: CGFloat,_ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: r, cornerHeight: r, transform: nil)
}
func F(_ s: CGFloat) -> CTFont { CTFontCreateWithName("PingFangSC-Semibold" as CFString, s, nil) }
func tline(_ s: String,_ sz: CGFloat,_ color: CGColor) -> CTLine {
    CTLineCreateWithAttributedString(CFAttributedStringCreate(nil, s as CFString,
        [kCTFontAttributeName: F(sz), kCTForegroundColorAttributeName: color] as CFDictionary)!)
}
@discardableResult
func drawAt(_ s: String,_ sz: CGFloat,_ color: CGColor,_ x: CGFloat,_ y: CGFloat) -> CGFloat {
    let l = tline(s, sz, color)
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(l, ctx)
    return CGFloat(CTLineGetTypographicBounds(l, nil, nil, nil))
}
func wOf(_ s: String,_ sz: CGFloat,_ color: CGColor) -> CGFloat {
    CGFloat(CTLineGetTypographicBounds(tline(s, sz, color), nil, nil, nil))
}

// backdrop
let bg = CGGradient(colorsSpace: cs, colors: [rgb(0x06090F), rgb(0x0F131A), rgb(0x0A2036)] as CFArray, locations: [0, 0.5, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: H), end: CGPoint(x: W, y: 0), options: [])

let halo = CGGradient(colorsSpace: cs, colors: [rgba(0x3B82F6, 0.32), rgba(0x3B82F6, 0)] as CFArray, locations: [0, 1])!
ctx.saveGState()
ctx.addEllipse(in: CGRect(x: 560, y: -120, width: 820, height: 820)); ctx.clip()
ctx.drawRadialGradient(halo, startCenter: CGPoint(x: 900, y: 40), startRadius: 0,
                       endCenter: CGPoint(x: 900, y: 40), endRadius: 560, options: [.drawsAfterEndLocation])
ctx.restoreGState()

ctx.setStrokeColor(rgba(0x33405A, 0.20)); ctx.setLineWidth(0.7)
for i in 1..<7 { let y = CGFloat(i)*H/7; ctx.move(to: .init(x:0,y:y)); ctx.addLine(to: .init(x:W,y:y)) }
for i in 1..<15 { let x = CGFloat(i)*W/15; ctx.move(to: .init(x:x,y:0)); ctx.addLine(to: .init(x:x,y:H)) }
ctx.strokePath()

// ==== top pill ====
let pillStr = "v0.6.0 · STABLE RELEASE"
let pillW = wOf(pillStr, 20, .white) + 46
let pillY: CGFloat = 104
ctx.addPath(rrPath(64, pillY, pillW, 46, 23)); ctx.setFillColor(rgb(0x3B82F6)); ctx.fillPath()
ctx.setFillColor(rgb(0xFFFFFF)); ctx.textPosition = .zero
let pillLine = tline(pillStr, 20, rgb(0xFFFFFF))
ctx.textPosition = CGPoint(x: 64 + (pillW - CGFloat(CTLineGetTypographicBounds(pillLine,nil,nil,nil)))/2, y: pillY + 13)
CTLineDraw(pillLine, ctx)

// ==== title / sub / desc ====
drawAt("Android Global Agent", 62, rgb(0xEDF2FA), 62, 224)
drawAt("Lite", 40, rgb(0x8FD0FF), 64, 280)
drawAt(" · 零 Root 手机助手", 40, rgb(0x8FD0FF), 64 + wOf("Lite",40,rgb(0x8FD0FF)) + 6, 280)

// description lines
drawAt("视觉 + 语义驱动的 Android 自动化 Agent", 25, rgb(0xC7D2E2), 64, 372)
drawAt("无障碍感知 · 点击/滑动/输入 · 语音 · 任务规划", 23, rgb(0x9AA9C4), 64, 406)

// pink accent line
ctx.setFillColor(rgb(0xF472B6))
ctx.addPath(rrPath(64, 430, 150, 4, 2)); ctx.fillPath()

// ==== chips ====
struct Chip { let x: CGFloat; let w: CGFloat; let label: String; let accent: Bool }
let chY: CGFloat = 486
let chips = [
    Chip(x:64, w:180, label:"Android 11+", accent:false),
    Chip(x:64+180+12, w:250, label:"OpenAI 兼容接口", accent:false),
    Chip(x:64+180+12+250+12, w:180, label:"无需 Root", accent:true),
]
for c in chips {
    ctx.addPath(rrPath(c.x, chY, c.w, 46, 23))
    ctx.setFillColor(c.accent ? rgb(0x18344F) : rgb(0x151C2B)); ctx.fillPath()
    ctx.setStrokeColor(rgba(c.accent ? 0xF472B6 : 0x3B82F6, c.accent ? 0.95 : 0.3))
    ctx.setLineWidth(1.5)
    ctx.addPath(rrPath(c.x, chY, c.w, 46, 23)); ctx.strokePath()
    let lw = wOf(c.label, 19, .white)
    drawAt(c.label, 19, c.accent ? rgb(0xFBA9D2) : rgb(0xE4EBF5), c.x + (c.w - lw)/2, chY + 14)
}

// ==== right robot ====
let px: CGFloat = 858, py: CGFloat = 96, ps: CGFloat = 330
ctx.addPath(rrPath(px, py, ps, ps, 44))
ctx.setFillColor(rgba(0x131A28, 0.85)); ctx.fillPath()
ctx.setStrokeColor(rgba(0x3F4D68, 0.6)); ctx.setLineWidth(2)
ctx.addPath(rrPath(px, py, ps, ps, 44)); ctx.strokePath()

let hx = px + ps/2
let hy = py + ps/2 + 8
let hs: CGFloat = 190
ctx.addPath(rrPath(hx - hs/2, hy - hs/2 - 16, hs, hs, 54)); ctx.setFillColor(rgb(0x3B82F6)); ctx.fillPath()
// antenna
ctx.setStrokeColor(rgb(0x9AA9C4)); ctx.setLineWidth(7); ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: hx, y: hy - hs/2 - 16 - 6)); ctx.addLine(to: CGPoint(x: hx, y: hy - hs/2 - 46)); ctx.strokePath()
ctx.setFillColor(rgb(0xF472B6)); ctx.addPath(rrPath(hx-8, hy - hs/2 - 74, 16, 16, 8)); ctx.fillPath()
// eyes
ctx.setFillColor(rgb(0x0C1320))
for ex in [-1.0, 1.0] {
    ctx.addPath(rrPath(hx + ex*42 - 9, hy - 10, 18, 30, 9)); ctx.fillPath()
}
// smile
ctx.setStrokeColor(rgb(0x0C1320)); ctx.setLineWidth(9); ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: hx - 36, y: hy + 40)); ctx.addQuadCurve(to: CGPoint(x: hx + 36, y: hy + 40), control: CGPoint(x: hx, y: hy + 84)); ctx.strokePath()

// small floating dots
ctx.setFillColor(rgb(0x10B981))
ctx.addPath(rrPath(px+ps-58, py+22, 10, 10, 5)); ctx.fillPath()
ctx.setFillColor(rgb(0xF472B6))
ctx.addPath(rrPath(px+40, py+ps-36, 8, 8, 4)); ctx.fillPath()

// bottom repo tag
let tag = tline("github.com/TQYM/android-global-agent", 17, rgb(0x7590AE))
let tw = CGFloat(CTLineGetTypographicBounds(tag, nil, nil, nil))
ctx.textPosition = CGPoint(x: W - tw - 88, y: H - 60)
CTLineDraw(tag, ctx)

ctx.flush()
let img = ctx.makeImage()!
let out = URL(fileURLWithPath: "docs/assets/banner-v0.6.0.png")
let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, img, nil)
CGImageDestinationFinalize(dest)
print("OK", out.path)
