# field.motion.v1

All common poses are right handed, metres, seconds, Y-up; headings are radians from +Z towards
+X. Quaternions are normalized **xyzw**, local to the ordered parent hierarchy. World positions
are also stored for direct drawing. Interpolation never crosses an episode boundary. Session time
and global frame index do not restart when the episode changes. Reset/termination reason belongs
in an event, never inferred from a discontinuity. Forces are Newtons, impulses N s, torque N m,
stiffness N/m, damping N s/m. Attachment points are body-local metres with an explicit named
body-to-joint mapping.

Source-to-Field metadata is a row-major orthogonal 3x3 basis B and a metres-per-source-unit scale.
For source position p, rotation R, force F and moment M: p'=sBp, R'=BRB^T, F'=BF,
M'=det(B)BM. For Isaac Gym's Z-up world choose (x,y,z)->(x,z,-y), a proper rotation. SI force
and torque magnitudes do not acquire the source position scale. Rest transforms in recordings are
already in common coordinates; source metadata records provenance, not an instruction to apply a
second conversion. Contacts carry `ardy.predicted`, `simulator.measured`, or `unavailable`
provenance (`fixture` is permitted for explicit tests).

A directory contains:

* `manifest.json`: schema 1, format `field.motion.v1`, skeleton, configuration/provenance,
  ordered `chunks` names and `frameCount`. The skeleton has ID/version, ordered unique names,
  exactly one root, parents preceding children, common rest translations/xyzw rotations,
  coordinates, body mappings and hash. CLoSD reference and executed poses are distinct fields.
* `events.jsonl`: typed commands/events. Commands retain operation, revision, requested time,
  payload, disposition (`requested`, `superseded`, `scheduled`, `applied`) and applied time/frame where known.
  Reset, termination, tether break, diagnostics and actual visibility have separate event types.
* `chunk-000000.bin`, etc.: length-framed pose records. Chunk names are contiguous and validated.

Each chunk is a sequence of records: big-endian uint32 record byte length, then those bytes.
Inside each record: big-endian uint32 JSON-header byte length, UTF-8 JSON header, contiguous
little-endian float32 data. Header fields are `time`, `index`, `episode`, `revision`,
`contactProvenance`, `diagnostics`, `hasIntended`, `intendedHasRotations` and `contactCount`. The last rotation flag defaults to true for earlier recordings. Data order is executed
`root[3]`, `localRotations[J*4]`, `worldPositions[J*3]`, optionally intended `root[3]`, `localRotations[J*4]` only when `intendedHasRotations`, and `worldPositions[J*3]`,
then `contacts[contactCount]`. These arrays store identical float values in Python and Kotlin.
One record is capped at 16 MiB; manifests at 4 MiB; contact count at 1024. Trailing/truncated
payload, non-finite state, non-unit rotation, invalid topology/hash and out-of-order frames fail.

Skeleton hash is SHA-256 over, in order: ID/version/names as null-terminated UTF-8; parents,
rest translations, rest rotations, source basis and metres-per-unit as float64 LE; source
units/handedness/up/forward as null-terminated UTF-8; each body name/joint as strings and mass
as float64 LE (-1 for absent). Strings must not contain NUL. JSON key order and number spelling
do not affect the hash.

Writers create a fresh directory and `incomplete.json`; a completed recording gets an atomically
published manifest. Python exceptions retain the incomplete marker. A cleanly closed session
may contain a partial performance ending with an explicit `workerFailure` event. Readers require
the manifest and do not attempt to unpickle upstream data. CLoSD reference positions have no valid upstream reference rotations; their rotation arrays remain empty.

Live local transport uses an authenticated loopback TCP socket with big-endian uint32 lengths and bounded UTF-8 JSON messages (4 MiB). The parent owns the listener and worker process; the token is an environment variable and never recorded. Request/reply IDs must match. `hello` exports schema, skeleton and capabilities; `command` accepts typed commands; `advance` returns frames/events; `close` releases the simulator. Scheduled acknowledgements have null applied times; actual `applied` events supply the control-boundary timestamp later. Worker output/logging uses a separate file. Common recordings remain binary, independent of this prototype transport.

Break-force thresholds use ramped pre-cap spring/damper demand (`rawForceN`), while `forceN` is the applied capped magnitude. Break-error thresholds use point-to-anchor distance; either criterion must persist for `breakDuration`. A saturating tether never detaches solely because its cap is reached. Mechanical power/work use actual applied force and point velocity, not metabolic effort.
