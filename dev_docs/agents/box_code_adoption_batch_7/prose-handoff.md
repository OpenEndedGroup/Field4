Follow up the documentation-code corrections after landing:
- dynamic_graph_systems_examples.md: opening prose still claims equal reinforcement; code uses routing=1.0, other update laws=4.0 and alpha reinforcement per unit area, with only cloud/clock/ceiling shared.
- statistics_examples.md: update the table/description and regenerate the genuine affected capture for the case now correctly labelled 'sine, 3 cycles' over x=-1..1. Values/calculations are unchanged.
- movement_examples.md: the plotted angular values are absolute/unwrapped angles, not displacement from 3; code's axis label now says so. Check surrounding caption/capture wording.
Code-only pass leaves prose and committed captures unchanged; no synthetic capture replacements.
