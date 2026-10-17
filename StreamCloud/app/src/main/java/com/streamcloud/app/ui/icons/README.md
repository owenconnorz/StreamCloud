# StreamCloud icons

Generic in-app icons use Google's **Material Symbols Rounded**, weight 400,
optical size 24, bundled as native Compose ImageVectors. No font or network
access is needed to display them. Service logos, user avatars, app branding,
system-owned notification artwork and custom non-icon artwork remain separate.

`AppIcons` preserves the existing Kotlin icon names and ImageVector types. UI
files import it as `Icons`, so existing sizes, tints, accessibility descriptions,
click handlers and focus targets are unchanged. Main navigation chooses outlined
symbols when inactive and filled symbols when selected. Border aliases always
remain outlined so favourite/bookmark toggles retain their meanings.

The catalog is generated, split into small source files and cached by variant.
To add another symbol, reference it from the UI, add an explicit semantic alias
to the generator if the legacy name differs, then run from the Android root:

```sh
python scripts/generate-material-symbols.py
python scripts/test_material_symbols.py -v
```

Regeneration uses the upstream revision saved in the manifest. To intentionally
update artwork, supply `--revision` with a full Google repository commit SHA.
The manifest records source URLs and checksums. Google publishes both
font-coordinate and positive-coordinate SVGs; preserve each source viewBox
rather than assuming every glyph uses a 960-unit negative origin.

The Apache 2.0 license and Google notice are packaged in APK assets.
`MaterialSymbolsTest` builds all icon getters, checks RTL mirroring and verifies
toggle aliases, viewports, caching and navigation fill variants.
