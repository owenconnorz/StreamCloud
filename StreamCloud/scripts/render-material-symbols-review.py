#!/usr/bin/env python3
"""Render a temporary artwork-only contact sheet, not an Android UI mockup."""
import hashlib
import html
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "app/src/main/java/com/streamcloud/app/ui/icons/material-symbols-manifest.json").read_text())
cache = Path("/tmp/streamcloud-material-symbols") / manifest["upstream_commit"]
assets = {asset["key"]: asset for asset in manifest["assets"]}


def tile(alias, filled):
    symbol = manifest["aliases"][alias]["symbol"]
    asset = assets[f"{symbol}:{int(filled)}"]
    raw = (cache / asset["url"].rsplit("/", 1)[1]).read_bytes()
    if hashlib.sha256(raw).hexdigest() != asset["sha256"]:
        raise ValueError(f"Artwork checksum mismatch: {alias}")
    svg = ET.fromstring(raw)
    svg.attrib["style"] = "fill:currentColor;width:28px;height:28px"
    markup = ET.tostring(svg, encoding="unicode").replace("ns0:", "").replace(":ns0", "")
    return f'<div class="tile">{markup}<span>{html.escape(alias)}</span></div>'


navigation = ["Theaters", "MusicNote", "Bookmarks", "Folder", "LiveTv", "Whatshot", "Settings"]
page = ['''<!doctype html><meta charset="utf-8"><title>Material Symbols asset review</title>
<style>
body{background:#101318;color:#e3e7ef;font:14px system-ui;margin:32px}
h1{font-size:24px;margin-bottom:6px}h2{font-size:17px;margin:24px 0 14px}p{color:#a8b2c3}
.grid{display:grid;grid-template-columns:repeat(11,1fr);gap:8px}.nav{display:flex;gap:28px}
.tile{height:62px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;min-width:76px}
.tile span{font-size:9px;text-align:center;max-width:105px;overflow-wrap:anywhere}
.active{color:#adc6ff;background:#202c42;border-radius:18px;max-width:900px;padding:8px}
.inactive{color:#aab2c0}.grid .tile{background:#191e27;border-radius:10px}
</style><h1>Material Symbols Rounded</h1>
<p>Offline artwork review — not a screenshot of the Android UI.</p>
<h2>Inactive navigation · outlined</h2><div class="nav inactive">''']
page.extend(tile(alias, False) for alias in navigation)
page.append('</div><h2>Selected navigation · filled</h2><div class="nav active">')
page.extend(tile(alias, True) for alias in navigation)
page.append('</div><h2>All 151 app icon names · default/toggle variants</h2><div class="grid">')
for alias, info in manifest["aliases"].items():
    page.append(tile(alias, info["fixed_fill"] if info["fixed_fill"] is not None else True))
page.append("</div>")
destination = Path("/tmp/streamcloud-icon-review")
destination.mkdir(exist_ok=True)
(destination / "index.html").write_text("".join(page))
print(f"Artwork-only review: {destination}/index.html")
