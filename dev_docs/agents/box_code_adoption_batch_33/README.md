# Typed study sources and inherited study handles

Baseline `ac1bd079c`. Three frozen findings addressed:

- `a3a5be415f98-01`: the Rings transcript initializes its typed source with `once.rings`, publishes a separate typed `ringsStudy`, and inherits that handle into its children. The study itself registers its source for persistence. No raw property calls or manual global persistence registration remain.
- `f9f433271058-01`: the studies page uses typed editable sources for rings, loot, plan and rooms. Explicit reset assignments remain editable. The rooms loop names its numeric counter separately from the source property.
- `d05cb6b8df0f-01`: the Rules example reads its typed source with an explicit empty default, preserving the missing-source behavior.

Actual import/ktfmt formatting covers three transcript bodies and nineteen Markdown states across the two pages; three transcript collection constructors become literals. The idiom/collection checker is clean across all three authored files. Reviewed tokens, surrounding prose, container properties and existing fragment boundaries are preserved by verify.py. No GLSL occurs in these edited examples.

Real verification on the private Field host:

- Launch all three Rings boxes, edit through the actual study, relaunch the parent, save, copy the saved document into a fresh session, and relaunch. Edited source survives both parent rerun and reopening; its child edits the inherited handle successfully.
- Make a real kit through field_kit.LiveHost, change and rehash the authored source, migrate, and inspect the parsed rules and updated frozen baseline. A later local source edit is preserved when migration refuses it. A separate box without source yields an empty rules map. Initial custom source/baseline are installed with the actual property tool after opening the document; they are not assumed to survive an unregistered scratch save.
- Execute the ten standalone states of studies.md: indices 0,1,2,3,7,8,9,10,11,12. All typed-source states are included. State 4 explicitly requires an existing design study, state 5 is a constructor argument, and state 6 deliberately throws; those three receive token checks, not a standalone execution claim. The external Python example is submitted as authored; this is not an assertion of its asynchronous final completion.

The rehearsal initially used a whitespace-sensitive selector, then exposed missing source in its scratch-save setup. Both harness assumptions are repaired. An initial attempt to execute context-dependent state 4 standalone failed as expected from its documented prerequisite; the final coverage records the distinction. Failure diagnostics are retained with successful evidence. No published captures are replaced and no fake output is introduced.

The scoped transcript compiler checks all three Rings boxes. Reusable mechanical, preservation and live rehearsal harnesses plus evidence.tar.gz retain the exact checks. The corpus sweep remains incomplete.
