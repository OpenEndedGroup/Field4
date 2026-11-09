# body_one — tracking and body queries

Connect WiVRn and launch boxes **1, 2, 3** in a fresh Field JVM. Box 1 owns OpenXR, native preview
and the optional browser pane. Box 2 owns the shared stereo draw module and GPU thickening.
Box 3 samples tracking after XR locates this frame's views, updates the markers and drives rendering.

Blue is the left hand, orange the right, pale green the sparse body, purple the controller grips.
RGB axes show local X/Y/Z orientation. Missing positions are hidden immediately. Connections
are drawing guides between valid observations, not extra tracked joints. Forearms connect to
tracked wrists, with controller grips as a fallback. The status disclosure lists availability,
coordinates, joint radii, pinch and controller input; it uses Markdown.

The default inspection copy translates the drawing ahead of you without changing the raw
coordinate table or query inputs. Recenter it after changing your heading. Turn it off to draw
at actual tracking positions.

## Body geometry

`val body = xr.bodyGeometry()` copies the current valid body and head positions. Invalid and
nonfinite positions are omitted. A saved snapshot stays fixed while tracking advances.

- `body.closestJoint(point)` selects a reported body joint centre, excluding the head.
- `body.closestPoint(point, includeHead = false)` queries body joint centres and connected segments.
- `body.closestPoint(point, limbRadius = .04f, headRadius = .09f)` queries finite limb cylinders,
  isolated-joint spheres and a head sphere. Zero-length limbs become spheres.
- `body.bounds(limbRadius = .04f, headRadius = .09f, padding = .01f)` encloses those same primitives.

The geometric helper uses body slots and the optional head, not finger or controller positions.
Empty observations return `null` for hits and bounds. Negative signed distance means the query
is inside the selected primitive. Shapes are independent surfaces, not a watertight Boolean
union: cylinder end caps remain queryable where shapes overlap. Use the returned normal and
identity (`joint`, `segment`, `head`) for creative interaction; do not infer unreported joints.

The query follows the right palm, otherwise its controller grip, otherwise a point ahead of
the head. Pink marks the query; green marks its closest point. The box overlay shows the bounds.
The sphere/cylinder toggle sets query radii to zero for skeleton-only queries.

The current WiVRn mapping has eight named body slots: hips, chest, two lower arms, two lower
legs and two feet. A runtime/configuration can supply a subset. All 26 joints of each active
hand are exposed separately. Quest 3 has no eye tracking. These are runtime observations,
including its body estimates, rather than independent physical measurements of every joint.
