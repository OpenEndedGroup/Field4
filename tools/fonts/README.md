# Field fonts

`iosevka-field.toml` is the existing Iosevka Field code-font build plan.

Text frames use **Field Etoile**, derived from the official Iosevka Etoile 34.7.0 webfonts. Etoile's
quasi-proportional slab-serif text faces are retained. The only glyph-default change promotes
`zero.cv10-5` (upstream `cv10=5`, `tall-reverse-slashed`) to U+0030, matching the zero setting in
`iosevka-field.toml`. Glyph outlines are unmodified; the six derivative faces get their own
family/PostScript names. The upstream copyright and SIL OFL travel with the webfonts in
`src/jsMain/resources/woff2/field-etoile-LICENSE.txt` and in their embedded font metadata.

Rebuild light, light italic, regular, italic, bold and bold italic:

```sh
curl -fL -o /tmp/PkgWebFont-IosevkaEtoile-34.7.0.zip \
  https://github.com/be5invis/Iosevka/releases/download/v34.7.0/PkgWebFont-IosevkaEtoile-34.7.0.zip
uv run tools/fonts/build_field_etoile.py --archive /tmp/PkgWebFont-IosevkaEtoile-34.7.0.zip
```

The script pins FontTools and checks the archive SHA256 before deriving WOFF2 files. It reopens each
output to verify the default zero, unchanged outline, distinct advance widths for `i` and `W`, and
family identity. It preserves the full upstream character coverage and OpenType layout tables.

Sources: [Iosevka release](https://github.com/be5invis/Iosevka/releases/tag/v34.7.0),
[upstream variant table](https://github.com/be5invis/Iosevka/blob/v34.7.0/doc/character-variants.md),
[upstream license](https://github.com/be5invis/Iosevka/blob/v34.7.0/LICENSE.md).
