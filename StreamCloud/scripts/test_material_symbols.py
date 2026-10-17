"""Offline checks for app-wide icon coverage and bundled artwork provenance."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java"
ICONS = SOURCE / "com/streamcloud/app/ui/icons"


class MaterialSymbolsAssetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((ICONS / "material-symbols-manifest.json").read_text())
        cls.catalog = (ICONS / "RoundedSymbolCatalog.kt").read_text()
        cls.api = (ICONS / "AppIcons.kt").read_text()

    def test_every_screen_icon_has_a_bundled_symbol(self):
        used = set()
        for path in SOURCE.rglob("*.kt"):
            text = path.read_text()
            used.update(re.findall(r"\bIcons\.(?:AutoMirrored\.)?(?:Default|Filled|Outlined|Rounded)\.(\w+)", text))
            self.assertFalse("androidx.compose.material.icons.Icons" in text, f"Legacy icon reference: {path}")
        self.assertTrue(used)
        self.assertFalse(used - self.manifest["aliases"].keys())
        self.assertEqual(len(used), 151)

    def test_both_fill_states_exist_for_every_symbol(self):
        assets = {asset["key"]: asset for asset in self.manifest["assets"]}
        for alias in self.manifest["aliases"].values():
            for fill in (0, 1):
                key = f"{alias['symbol']}:{fill}"
                self.assertIn(key, assets)
                self.assertIn(f'"{key}" ->', self.catalog)
                self.assertEqual(len(assets[key]["sha256"]), 64)

    def test_source_is_pinned_to_google_rounded_artwork(self):
        commit = self.manifest["upstream_commit"]
        self.assertRegex(commit, r"^[a-f0-9]{40}$")
        self.assertEqual(self.manifest["family"], "Material Symbols Rounded")
        self.assertEqual(self.manifest["license"], "Apache-2.0")
        for asset in self.manifest["assets"]:
            self.assertIn(f"githubusercontent.com/google/material-design-icons/{commit}/", asset["url"])
            self.assertIn("/materialsymbolsrounded/", asset["url"])

    def test_toggle_border_aliases_are_always_outlined(self):
        for name in ("FavoriteBorder", "BookmarkBorder"):
            self.assertIs(self.manifest["aliases"][name]["fixed_fill"], False)

    def test_fire_navigation_keeps_its_original_meaning(self):
        self.assertEqual(self.manifest["aliases"]["Whatshot"]["symbol"], "local_fire_department")

    def test_navigation_uses_both_states_without_changing_destinations(self):
        source = (SOURCE / "com/streamcloud/app/ui/StreamCloudApp.kt").read_text()
        self.assertIn("if (selected) tab.icon else tab.outlinedIcon", source)
        self.assertIn("if (currentRoute == tab.route) tab.icon else tab.outlinedIcon", source)
        for name in ("Theaters", "MusicNote", "Bookmarks", "Folder", "LiveTv", "Whatshot", "Settings"):
            self.assertIn(f"Icons.Filled.{name}, Icons.Outlined.{name}", source)
        self.assertIn("selected = selected", source)

    def test_google_license_is_bundled_in_apk_assets(self):
        licenses = ROOT / "app/src/main/assets/licenses"
        self.assertIn("Apache License", (licenses / "google-material-symbols-Apache-2.0.txt").read_text())
        self.assertIn("Google LLC", (licenses / "google-material-symbols-NOTICE.txt").read_text())


if __name__ == "__main__":
    unittest.main()
