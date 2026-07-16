# Iosevka webfonts

These are the normal and 416-unit condensed, unhinted WOFF2 faces for Field's
custom `Iosevka Field` family, built from Iosevka 34.7.0. Field selects the
condensed faces by default. The build enables the experimental `TXTR`
cross-letter texture-adjustment feature and exports glyph names so the same
family can be processed by the Nerd Fonts patcher. Field intentionally does
not build, ship, or register Extended faces.

The reproducible custom build plan is
[`tools/fonts/iosevka-field.toml`](../../../../tools/fonts/iosevka-field.toml).
Copy it to an Iosevka 34.7.0 checkout as `private-build-plans.toml`, then run:

```sh
npm install
npm run build -- woff2-unhinted::IosevkaField ttf-unhinted::IosevkaField --jCmd=6
```

The locally installed `Iosevka Field Nerd Font` faces were produced from the
unhinted TTF output with Nerd Fonts Patcher 3.4.0; the patcher preserves
`TXTR`.

Source: https://github.com/be5invis/Iosevka/tree/v34.7.0

Iosevka is distributed under the SIL Open Font License 1.1.
