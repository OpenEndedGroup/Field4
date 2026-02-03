#!/bin/bash

ASSET_DIR=~/temp/jvm-js-fullstack/public/docs/assets
prefix="screenshot_"
ext=".png"
i=1

# Loop until we find a filename that doesn't already exist
while [ -e "$ASSET_DIR/${prefix}${i}${ext}" ]; do
  i=$((i+1))
done

filename="${prefix}${i}${ext}"
full_path="$ASSET_DIR/$filename"

# Save the clipboard image to the new file, then copy the asset path to the clipboard
pngpaste "$full_path" && echo -n "![](assets/$filename)" | pbcopy

echo -n "![](assets/$filename)"