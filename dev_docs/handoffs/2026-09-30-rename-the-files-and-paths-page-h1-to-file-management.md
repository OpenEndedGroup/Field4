# Rename the files_and_paths page H1 to File management

- **Handoff:** `f87c401e-ac4b-417a-a683-c184c7fafcfe` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/files_and_paths/; also affects document_on_disk.md, geometry_from_disk.md, reference/index.md, reference/data.md
- **Filed by:** agent:claude, 2026-09-30 20:39
- **Close with:** `python tools/docs_notes.py handoff-close f87c401e "what happened"`

## The notes that led here

- `e8670a2c` (public/docs/files_and_paths.md:1): let's just call this File management
  > Where Field looks for a file

## What was asked

Note e8670a2c on `public/docs/files_and_paths.md:1` asks for the H1 "Where Field looks for a file"
to become "File management".

## Why it is bigger than a one-file edit

The H1 text is copied into other files, and one set of them is generated and checked at commit:

- `public/docs/reference/index.md` (FileManager.kt row) and `public/docs/reference/data.md`
  ("**Used in:** [Where Field looks for a file]"). These are produced by
  `./gradlew generateCodebaseReference`, which uses a page's H1 as link text. `:checkCodebaseReference`
  runs in the pre-commit guard, so renaming the H1 without regenerating blocks every commit.
- `public/zensical.yml` nav entry: `- Where Field Looks for a File: files_and_paths.md` (and the comment below it).
- Prose link text on other pages: `public/docs/document_on_disk.md` (line 9),
  `public/docs/geometry_from_disk.md` (line 176), plus a code comment in
  `public/docs/fabricate_renderer.md` (line 589) that names the section "Where Field looks for a file"
  under "Working in a Document". That comment sits inside a fence, so changing it changes the capture.

This agent may edit only `files_and_paths.md`, so it reverted the H1.

## Proposed approach

1. Change the H1 in `files_and_paths.md` to `# File management`.
2. Update the nav title in `zensical.yml`.
3. Reword the link text on `document_on_disk.md` and `geometry_from_disk.md`. They could say
   "[File management](files_and_paths.md)", or keep the descriptive wording with only the link target unchanged.
4. Decide whether to change the fenced comment on `fabricate_renderer.md`. That needs a recapture,
   so leaving it alone is also fine.
5. Run `./gradlew --no-daemon generateCodebaseReference` (about 3 minutes cold) and commit the regenerated
   `reference/index.md` and `reference/data.md`.

Keep the file name as it is: a slug rename would break inbound URLs and anchors.

## Questions for the human

- Should the nav title also be "File management", or stay descriptive?
- Should the other pages' link text change, or only the page and the nav?

## What the page needs afterwards

Nothing more. The H1 is the only change.
