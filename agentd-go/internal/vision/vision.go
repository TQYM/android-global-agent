// Package vision turns full-resolution screencap PNGs into compact JPEG
// data URLs suitable for per-step vision LLM requests. A 1.5K phone
// screenshot PNG is multi-MB; vision models downscale server-side anyway,
// so we ship a width-bounded JPEG (~100-200 KB) to keep steps cheap.
package vision

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"os"
)

// DataURL reads pngPath, downscales to at most maxWidth pixels wide
// (aspect preserved, integer box average), JPEG-encodes at the given
// quality and returns a data: URL ready for an image_url content part.
func DataURL(pngPath string, maxWidth, quality int) (string, error) {
	raw, err := os.ReadFile(pngPath)
	if err != nil {
		return "", fmt.Errorf("read screenshot: %w", err)
	}
	img, err := png.Decode(bytes.NewReader(raw))
	if err != nil {
		return "", fmt.Errorf("decode png: %w", err)
	}
	scaled := downscale(img, maxWidth)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, scaled, &jpeg.Options{Quality: quality}); err != nil {
		return "", fmt.Errorf("encode jpeg: %w", err)
	}
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(buf.Bytes()), nil
}

// downscale shrinks img with a 2x2 (repeated) box filter while it is
// wider than maxWidth. Box filtering in halves keeps the math integer,
// artifact-free and fast — good enough for UI screenshots.
func downscale(img image.Image, maxWidth int) image.Image {
	if maxWidth <= 0 {
		maxWidth = 768
	}
	for {
		b := img.Bounds()
		if b.Dx() <= maxWidth {
			return img
		}
		img = halve(img)
	}
}

func halve(img image.Image) image.Image {
	b := img.Bounds()
	w := b.Dx() / 2
	h := b.Dy() / 2
	if w < 1 || h < 1 {
		return img
	}
	out := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			x0, y0 := b.Min.X+x*2, b.Min.Y+y*2
			var r, g, bl, a, n uint32
			for dy := 0; dy < 2; dy++ {
				for dx := 0; dx < 2; dx++ {
					pr, pg, pb, pa := img.At(x0+dx, y0+dy).RGBA()
					r += pr
					g += pg
					bl += pb
					a += pa
					n++
				}
			}
			o := out.PixOffset(x, y)
			out.Pix[o] = uint8(r / n >> 8)
			out.Pix[o+1] = uint8(g / n >> 8)
			out.Pix[o+2] = uint8(bl / n >> 8)
			out.Pix[o+3] = uint8(a / n >> 8)
		}
	}
	return out
}
