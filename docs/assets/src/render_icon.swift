import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

// App-style rounded-square icon, 512x512, dark background + robot chat face.
let S: CGFloat = 512
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
guard let ctx = CGContext(data: nil, width: Int(S), height: Int(S),
                          bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
else { fatalError("ctx") }
ctx.translateBy(x: 0, y: S); ctx.scaleBy(x: 1, y: -1)

func rgb(_ h: UInt32) -> CGColor { CGColor(colorSpace: cs,
    components: [CGFloat((h>>16)&255)/255, CGFloat((h>>8)&255)/255, CGFloat(h&255)/255, 1])! }
func rgba(_ h: UInt32, _ a: CGFloat) -> CGColor { CGColor(colorSpace: cs,
    components: [CGFloat((h>>16)&255)/255, CGFloat((h>>8)&255)/255, CGFloat(h&255)/255, a])! }
func rr(_ x: CGFloat,_ y: CGFloat,_ w: CGFloat,_ h: CGFloat,_ r: CGFloat) -> CGPath {
    CGPath(roundedRect: CGRect(x:x,y:y,width:w,height:h), cornerWidth:r, cornerHeight:r, transform:nil)
}

// base dark rounded square (transparent-safe: we draw full canvas)
let bgG = CGGradient(colorsSpace: cs, colors: [rgb(0x0A1B33), rgb(0x0F131A)] as CFArray, locations: [0,1])!
ctx.drawLinearGradient(bgG, start: CGPoint(x:0,y:S), end: CGPoint(x:S,y:0), options: [])
// subtle ring
ctx.setStrokeColor(rgba(0x3B82F6, 0.22)); ctx.setLineWidth(20)
ctx.addPath(rr(16,16,S-32,S-32,96)); ctx.strokePath()

let cx = S/2, cy = S/2
// halo
let halo = CGGradient(colorsSpace: cs, colors: [rgba(0x3B82F6,0.45), rgba(0x3B82F6,0)] as CFArray, locations:[0,1])!
ctx.saveGState(); ctx.addRect(CGRect(x:0,y:0,width:S,height:S)); ctx.clip()
ctx.drawRadialGradient(halo, startCenter: CGPoint(x:cx,y:cy), startRadius: 40, endCenter: CGPoint(x:cx,y:cy), endRadius: 300, options: [.drawsAfterEndLocation])
ctx.restoreGState()

// robot head
let hs: CGFloat = 300
ctx.addPath(rr(cx-hs/2, cy-hs/2+10, hs, hs, 84)); ctx.setFillColor(rgb(0x3B82F6)); ctx.fillPath()
// top sheen clipped to head
ctx.saveGState()
ctx.addPath(rr(cx-hs/2, cy-hs/2+10, hs, hs, 84)); ctx.clip()
ctx.setFillColor(rgba(0xFFFFFF,0.10))
ctx.addPath(rr(cx-hs/2, cy-hs/2-46, hs, hs*0.42, 84)); ctx.fillPath()
ctx.restoreGState()

// antenna
ctx.setStrokeColor(rgb(0xCDE3FF)); ctx.setLineWidth(18); ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: cx, y: cy-hs/2+2)); ctx.addLine(to: CGPoint(x: cx, y: cy-hs/2-54)); ctx.strokePath()
ctx.setFillColor(rgb(0xF472B6)); ctx.addPath(rr(cx-16, cy-hs/2-104, 32, 32, 16)); ctx.fillPath()

// eyes
ctx.setFillColor(rgb(0x0A1420))
for s in [-1, 1] { let off = CGFloat(s) * 70; ctx.addPath(rr(cx + off - 16, cy - 18, 32, 58, 16)); ctx.fillPath() }
// smile
ctx.setStrokeColor(rgb(0x0A1420)); ctx.setLineWidth(18); ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: cx-62, y: cy+56)); ctx.addQuadCurve(to: CGPoint(x: cx+62, y: cy+56), control: CGPoint(x: cx, y: cy+128)); ctx.strokePath()

ctx.flush()
let img = ctx.makeImage()!
let out = URL(fileURLWithPath: "docs/assets/icon.png")
let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, img, nil); CGImageDestinationFinalize(dest)
print("OK", out.path)
