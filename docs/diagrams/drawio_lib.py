"""Minimal draw.io (mxGraph) document builder.

The API contract this project is designing is still moving, so the diagrams
are generated rather than hand-edited: regenerate with
`python docs/diagrams/generate_drawio.py` after a contract change instead of
touching eleven pages by hand.

Only the subset of mxGraph needed here is modelled: vertices, orthogonal
edges, and preformatted code/JSON blocks.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from html import escape as _html_escape

# ---------------------------------------------------------------- styling --

PALETTE = {
    "screen": "#dae8fc",
    "screen_border": "#6c8ebf",
    "decision": "#fff2cc",
    "decision_border": "#d6b656",
    "api": "#e1d5e7",
    "api_border": "#9673a6",
    "actor": "#f5f5f5",
    "actor_border": "#666666",
    "ok": "#d5e8d4",
    "ok_border": "#82b366",
    "fail": "#f8cecc",
    "fail_border": "#b85450",
    "note": "#ffffff",
    "note_border": "#b3b3b3",
    "hw": "#ffe6cc",
    "hw_border": "#d79b00",
}

_BASE = "rounded=1;whiteSpace=wrap;html=1;arcSize=12;fontSize=12;"


def style_box(kind: str) -> str:
    return f"{_BASE}fillColor={PALETTE[kind]};strokeColor={PALETTE[kind + '_border']};"


STYLES = {
    "screen": style_box("screen"),
    "api": style_box("api") + "dashed=0;",
    "actor": style_box("actor") + "rounded=0;",
    "ok": style_box("ok"),
    "fail": style_box("fail"),
    "hw": style_box("hw"),
    "decision": (
        "rhombus;whiteSpace=wrap;html=1;fontSize=11;"
        f"fillColor={PALETTE['decision']};strokeColor={PALETTE['decision_border']};"
    ),
    "note": (
        "shape=note;whiteSpace=wrap;html=1;size=14;fontSize=11;align=left;"
        "verticalAlign=top;spacingLeft=6;spacingTop=2;"
        f"fillColor={PALETTE['note']};strokeColor={PALETTE['note_border']};"
    ),
    "code": (
        "rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;"
        "fontFamily=Courier New;fontSize=10;spacingLeft=8;spacingTop=4;"
        "fillColor=#fbfbfb;strokeColor=#b3b3b3;"
    ),
    "title": (
        "text;html=1;align=left;verticalAlign=middle;fontSize=20;fontStyle=1;"
        "strokeColor=none;fillColor=none;"
    ),
    "subtitle": (
        "text;html=1;align=left;verticalAlign=middle;fontSize=12;"
        "fontColor=#666666;strokeColor=none;fillColor=none;"
    ),
    "lane": (
        "swimlane;html=1;startSize=28;fontSize=13;fontStyle=1;horizontal=0;"
        "fillColor=none;strokeColor=#999999;"
    ),
}

EDGE = (
    "edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;fontSize=10;"
    "jettySize=auto;orthogonalLoop=1;endArrow=block;endFill=1;strokeColor=#555555;"
)
EDGE_WEAK = EDGE + "dashed=1;strokeColor=#999999;"


def code_label(text: str) -> str:
    """Render preformatted text as an mxGraph HTML label.

    Indentation is meaningful in JSON, so spaces become non-breaking and
    newlines become explicit breaks. ElementTree escapes the result for the
    XML attribute, which is the second half of draw.io's usual double
    encoding.
    """
    html = _html_escape(text, quote=False)
    html = html.replace("\n", "<br>").replace(" ", "&nbsp;")
    return html


def rich(text: str) -> str:
    """A normal label that may contain simple <b>/<br> markup already."""
    return text


# ----------------------------------------------------------------- model --


class Page:
    def __init__(self, name: str):
        self.name = name
        self.cells: list[ET.Element] = []
        self._seq = 0

    def _next_id(self, prefix: str) -> str:
        self._seq += 1
        return f"{prefix}{self._seq}"

    def node(
        self,
        label: str,
        x: int,
        y: int,
        w: int = 220,
        h: int = 50,
        kind: str = "screen",
        *,
        code: bool = False,
        parent: str = "1",
    ) -> str:
        cid = self._next_id("n")
        value = code_label(label) if code else rich(label)
        cell = ET.Element(
            "mxCell",
            {
                "id": cid,
                "value": value,
                "style": STYLES[kind],
                "vertex": "1",
                "parent": parent,
            },
        )
        ET.SubElement(
            cell,
            "mxGeometry",
            {"x": str(x), "y": str(y), "width": str(w), "height": str(h), "as": "geometry"},
        )
        self.cells.append(cell)
        return cid

    def code(self, title: str, body: str, x: int, y: int, w: int = 430, h: int | None = None) -> str:
        """A monospaced block; height is derived from the line count."""
        text = f"{title}\n{'-' * min(len(title), 58)}\n{body}"
        lines = text.count("\n") + 1
        height = h if h is not None else max(60, 14 * lines + 16)
        return self.node(text, x, y, w, height, "code", code=True)

    def edge(
        self,
        source: str,
        target: str,
        label: str = "",
        *,
        weak: bool = False,
        exit_side: str | None = None,
        entry_side: str | None = None,
    ) -> str:
        cid = self._next_id("e")
        style = EDGE_WEAK if weak else EDGE
        sides = {
            "n": (0.5, 0), "s": (0.5, 1), "w": (0, 0.5), "e": (1, 0.5),
        }
        if exit_side:
            ex, ey = sides[exit_side]
            style += f"exitX={ex};exitY={ey};exitDx=0;exitDy=0;"
        if entry_side:
            nx, ny = sides[entry_side]
            style += f"entryX={nx};entryY={ny};entryDx=0;entryDy=0;"
        cell = ET.Element(
            "mxCell",
            {
                "id": cid,
                "value": label,
                "style": style,
                "edge": "1",
                "parent": "1",
                "source": source,
                "target": target,
            },
        )
        geo = ET.SubElement(cell, "mxGeometry", {"relative": "1", "as": "geometry"})
        geo.set("as", "geometry")
        self.cells.append(cell)
        return cid

    def title(self, text: str, subtitle: str = "", x: int = 40, y: int = 24) -> None:
        self.node(f"<b>{text}</b>", x, y, 900, 30, "title")
        if subtitle:
            self.node(subtitle, x, y + 28, 900, 22, "subtitle")

    def chain(
        self,
        steps: list[tuple[str, str]],
        x: int,
        y: int,
        w: int = 300,
        h: int = 50,
        gap: int = 28,
        labels: list[str] | None = None,
    ) -> list[str]:
        """Stack `steps` vertically and wire them together in order."""
        ids = []
        cy = y
        for label, kind in steps:
            ids.append(self.node(label, x, cy, w, h, kind))
            cy += h + gap
        for i in range(len(ids) - 1):
            lbl = labels[i] if labels and i < len(labels) else ""
            self.edge(ids[i], ids[i + 1], lbl)
        return ids

    def to_xml(self, diagram_id: str) -> ET.Element:
        diagram = ET.Element("diagram", {"id": diagram_id, "name": self.name})
        model = ET.SubElement(
            diagram,
            "mxGraphModel",
            {
                "dx": "1600", "dy": "900", "grid": "1", "gridSize": "10",
                "guides": "1", "tooltips": "1", "connect": "1", "arrows": "1",
                "fold": "1", "page": "1", "pageScale": "1",
                "pageWidth": "1654", "pageHeight": "1169",
                "math": "0", "shadow": "0",
            },
        )
        root = ET.SubElement(model, "root")
        ET.SubElement(root, "mxCell", {"id": "0"})
        ET.SubElement(root, "mxCell", {"id": "1", "parent": "0"})
        for cell in self.cells:
            root.append(cell)
        return diagram


class Document:
    def __init__(self):
        self.pages: list[Page] = []

    def page(self, name: str) -> Page:
        p = Page(name)
        self.pages.append(p)
        return p

    def write(self, path: str) -> None:
        mxfile = ET.Element(
            "mxfile",
            {"host": "app.diagrams.net", "type": "device", "version": "24.7.17"},
        )
        for i, page in enumerate(self.pages, start=1):
            mxfile.append(page.to_xml(f"page-{i}"))
        ET.indent(mxfile, space="  ")
        tree = ET.ElementTree(mxfile)
        tree.write(path, encoding="utf-8", xml_declaration=True)
