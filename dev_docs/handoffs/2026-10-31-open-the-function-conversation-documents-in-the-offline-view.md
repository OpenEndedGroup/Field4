# Open the function conversation documents in the offline viewer instead of zips

- **Handoff:** `ad4a6a39-b585-4bbe-b6d9-01af940a02d4` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/; also affects stacksl_conversation.md, rectsl_conversation.md
- **Filed by:** agent:claude, 2026-10-31 14:16
- **Close with:** `python tools/docs_notes.py handoff-close ad4a6a39 "what happened"`

## The notes that led here

- `014671de` (public/docs/function_conversation.md:14): for one, remember: many people are reading this _inside field_. Downloading a .zip file to open it is a little nuts

## What was asked

Note 014671de on `public/docs/function_conversation.md` (## Start) points out that many readers are inside Field's docs iframe, so "download a .zip and unzip it into your workspace" is the wrong way to open the starter and the worked example. Marc replied that the page should use the offline viewer and preview that the Agent transcript gallery uses.

## Why it is bigger than a docs edit

The gallery links `offline.html?file=assets/agent_transcripts/<name>/metadata.edn` (`public/docs/agent_transcripts.md:96`), so the document has to be unpacked under `public/docs/assets/`. This page ships only zips (`assets/function_conversation/function_conversation.zip`, `function_conversation_example.zip`), which `tools/package_function_conversation.py` produces. The packager must also emit the unpacked documents (metadata.edn plus sidecars and the saved chat and plot assets), and `tools/check_offline_assets.py` must accept them. The stacksl, rectsl and stackauto conversation pages use the same zip pattern.

## Proposed approach

1. Extend `package_function_conversation.py` to write `assets/function_conversation/{function_conversation,function_conversation_example}/` next to the zips, or in place of them.
2. Replace the two download links with the gallery's preview and `Open →` offline-viewer links.
3. Do the same on the sibling conversation pages.

Alternative: keep the zip as a secondary link for public-site readers who want the document in their own workspace.

## Questions for the human

- Should the zips stay as a secondary download, or go?
- Does the offline viewer need to show the saved AI4 chat, or only the box source and viewer?

## What the page needs afterwards

Rewrite the two "Download ... unzip into your workspace" paragraphs, in ## Start and ## A worked conversation, so they point at the offline previews.
