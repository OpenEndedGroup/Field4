# run_field_tests.py: reject or ignore --timeout instead of warning in docs

- **Handoff:** `7d00bd2f-9ad2-4eca-842b-5a6acfb6549d` (project)
- **Filed from:** https://openendedgroup.com/field4/building_and_testing/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 7d00bd2f "what happened"`

Rule 25 sharp edge. The page says "Per-test timeouts come from each test's annotation, so **don't** pass `--timeout` to this runner."

Fix in `tools/run_field_tests.py`: either remove the flag from the smoke-catalog path, or make it an explicit upper cap that cannot override annotation `timeoutSec`, with argparse help saying so. Delete the warning sentence in the same change.
