// Package semantics parses the uiautomator hierarchy XML into a compact
// list of interactive nodes and renders it as prompt text for the LLM.
package semantics

import (
	"encoding/xml"
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// Node is one interactive (or text-bearing) element on screen. The JSON
// tags mirror the on-device AccessibilityService protocol (port 8081).
type Node struct {
	Index      int    `json:"index"`
	Text       string `json:"text"`
	Desc       string `json:"desc"`
	ID         string `json:"id"`
	Class      string `json:"class"`
	Clickable  bool   `json:"clickable"`
	Scrollable bool   `json:"scrollable"`
	CenterX    int    `json:"cx"`
	CenterY    int    `json:"cy"`
	HasBounds  bool   `json:"-"`
}

type xmlNode struct {
	XMLName     xml.Name  `xml:"node"`
	Text        string    `xml:"text,attr"`
	ContentDesc string    `xml:"content-desc,attr"`
	ResourceID  string    `xml:"resource-id,attr"`
	Class       string    `xml:"class,attr"`
	Clickable   string    `xml:"clickable,attr"`
	Scrollable  string    `xml:"scrollable,attr"`
	Bounds      string    `xml:"bounds,attr"`
	Children    []xmlNode `xml:"node"`
}

type hierarchy struct {
	Nodes []xmlNode `xml:"node"`
}

var boundsRe = regexp.MustCompile(`\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]`)

func interesting(n *xmlNode) bool {
	return n.Clickable == "true" || n.Scrollable == "true" ||
		strings.TrimSpace(n.Text) != "" || strings.TrimSpace(n.ContentDesc) != ""
}

func walk(n *xmlNode, out *[]Node) {
	if interesting(n) {
		node := Node{
			Text:       strings.TrimSpace(n.Text),
			Desc:       strings.TrimSpace(n.ContentDesc),
			ID:         n.ResourceID,
			Class:      shortClass(n.Class),
			Clickable:  n.Clickable == "true",
			Scrollable: n.Scrollable == "true",
		}
		if m := boundsRe.FindStringSubmatch(n.Bounds); len(m) == 5 {
			l, _ := strconv.Atoi(m[1])
			t, _ := strconv.Atoi(m[2])
			r, _ := strconv.Atoi(m[3])
			b, _ := strconv.Atoi(m[4])
			node.CenterX = (l + r) / 2
			node.CenterY = (t + b) / 2
			node.HasBounds = true
		}
		node.Index = len(*out)
		*out = append(*out, node)
	}
	for i := range n.Children {
		walk(&n.Children[i], out)
	}
}

func shortClass(class string) string {
	if i := strings.LastIndex(class, "."); i >= 0 {
		return class[i+1:]
	}
	return class
}

// Parse extracts interactive nodes from a hierarchy XML document.
func Parse(xmlText string) ([]Node, error) {
	var doc hierarchy
	if err := xml.Unmarshal([]byte(xmlText), &doc); err != nil {
		return nil, fmt.Errorf("parse hierarchy: %w", err)
	}
	var nodes []Node
	for i := range doc.Nodes {
		walk(&doc.Nodes[i], &nodes)
	}
	return nodes, nil
}

// ToPrompt renders nodes as compact numbered lines for the LLM context.
func ToPrompt(nodes []Node, maxNodes int) string {
	if maxNodes <= 0 || len(nodes) <= maxNodes {
		maxNodes = len(nodes)
	}
	var b strings.Builder
	b.WriteString("当前屏幕可交互节点（编号 坐标=点击中心）：\n")
	for _, n := range nodes[:maxNodes] {
		label := strings.TrimSpace(n.Text + " " + n.Desc)
		if label == "" {
			label = n.ID
		}
		if label == "" {
			label = n.Class
		}
		pos := "?"
		if n.HasBounds {
			pos = fmt.Sprintf("%d,%d", n.CenterX, n.CenterY)
		}
		b.WriteString(fmt.Sprintf("[%d] %s @%s %s\n", n.Index, label, pos, n.ID))
	}
	return b.String()
}
