# Field Document: madp_ga

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ga · run me

<!-- field-box id="madp_ga_lead" index="0" -->

### Narrative

From `code`

GA: interactive evolution, after Karl Sims. Each tile is a little program run once per pixel,
and you are the fitness function: click two tiles and they become the parents of the next
generation. A child copies one parent, sometimes borrows a few words from the other, and
mutates. Reroll breeds again from the same parents; Fresh Parents starts from noise.

The language is reverse Polish: every word takes its inputs off a stack.
x y          position, 0..1 (y grows downward)
r a          distance from the centre, angle around it (both 0..1)
rgb hsl      three numbers -> a colour
sat hue lum  set one part of a colour
+ - * /      numbers, or colours channel by channel (wrapped into 0..1)
sin cos tan asin acos atan exp ln invert normalize   change one value
perlin       phase -> noise      fbm   phase lacunarity -> layered noise
dot          two values -> a number
a b c if     b when c > 0.5, else a
blend        a number and two colours -> the colours mixed
dup          copy the top
An empty stack reads 0.5 and unknown words are skipped, so every program draws something.

These two parents are the demo's starters. Edit them and run the box again (alt-R); the
parents you pick are printed under the grid, ready to paste back in here.

```edn [field-box id="madp_ga_lead" role="properties"]
{"id" "madp_ga_lead" "index" 0 "name" "ga · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ga · run me" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_ga_lead_code_.kt" "lines" 38 "characters" 1512 "sha256" "3de4f35c3b58b9acc375172e76740f9467e07be53253177f083c1fdc021d43f7"}]}
```

```kotlin [field-property box="madp_ga_lead" name="code" path="madp_ga_lead_code_.kt"]
/*
GA: interactive evolution, after Karl Sims. Each tile is a little program run once per pixel,
and you are the fitness function: click two tiles and they become the parents of the next
generation. A child copies one parent, sometimes borrows a few words from the other, and
mutates. Reroll breeds again from the same parents; Fresh Parents starts from noise.

The language is reverse Polish: every word takes its inputs off a stack.
  x y          position, 0..1 (y grows downward)
  r a          distance from the centre, angle around it (both 0..1)
  rgb hsl      three numbers -> a colour
  sat hue lum  set one part of a colour
  + - * /      numbers, or colours channel by channel (wrapped into 0..1)
  sin cos tan asin acos atan exp ln invert normalize   change one value
  perlin       phase -> noise      fbm   phase lacunarity -> layered noise
  dot          two values -> a number
  a b c if     b when c > 0.5, else a
  blend        a number and two colours -> the colours mixed
  dup          copy the top
An empty stack reads 0.5 and unknown words are skipped, so every program draws something.

These two parents are the demo's starters. Edit them and run the box again (alt-R); the
parents you pick are printed under the grid, ready to paste back in here.
*/
val ga = colorRpnEvolution(
    """
    x 9 * sin 0.18 +
    y 0.55 +
    0.62
    hsl
    """,
    """
    a 14 * cos 0.14 +
    r 0.78 * 0.12 +
    0.48
    hsl
    """,
    ColorRpnBreeding(mutationRate = 0.12, targetLength = 18),
)
```

## 2. fbm bands × noise hues

<!-- field-box id="madp_ga_fbm" index="1" -->

### Narrative

From `code`

Layered noise along a diagonal, and two noises in x and y added into a hue.

```edn [field-box id="madp_ga_fbm" role="properties"]
{"id" "madp_ga_fbm" "index" 1 "name" "fbm bands × noise hues" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fbm bands × noise hues" "_tombstone" false "frame" #afield/rect [40.0 1320.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_ga_fbm_code_.kt" "lines" 5 "characters" 193 "sha256" "ce1e5ab23141a60b5d1b762b679ea836b58373a407542a7d1206fb90c8a16452"}]}
```

```kotlin [field-property box="madp_ga_fbm" name="code" path="madp_ga_fbm_code_.kt"]
// Layered noise along a diagonal, and two noises in x and y added into a hue.
colorRpnEvolution(
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    "x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl",
)
```

## 3. only the numbers mutate

<!-- field-box id="madp_ga_numbers" index="2" -->

### Narrative

From `code`

The breeding rules are settings too. ColorRpnBreeding's weights say how often each kind of
mutation happens (the demo's mix: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%). Here only numbers change and nothing is borrowed from the other
parent, and nudgeLength = false stops the length being topped up with random words, so every
child keeps its parent's shape: the same rings and bands at different frequencies.

```edn [field-box id="madp_ga_numbers" role="properties"]
{"id" "madp_ga_numbers" "index" 2 "name" "only the numbers mutate" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "only the numbers mutate" "_tombstone" false "frame" #afield/rect [860.0 1320.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_ga_numbers_code_.kt" "lines" 17 "characters" 703 "sha256" "dda71e2a653cbcd7182f9f1527978011daae496bf950d563fdba853fccc71e37"}]}
```

```kotlin [field-property box="madp_ga_numbers" name="code" path="madp_ga_numbers_code_.kt"]
/*
The breeding rules are settings too. ColorRpnBreeding's weights say how often each kind of
mutation happens (the demo's mix: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%). Here only numbers change and nothing is borrowed from the other
parent, and nudgeLength = false stops the length being topped up with random words, so every
child keeps its parent's shape: the same rings and bands at different frequencies.
*/
colorRpnEvolution(
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    ColorRpnBreeding(
        mutationRate = 0.25,
        seedSplice = 0.0,
        weights = mapOf("num" to 1.0),
        nudgeLength = false,
    ),
)
```

## 4. noise mask × rings

<!-- field-box id="madp_ga_noise" index="3" -->

### Narrative

From `code`

Two of the demo's example programs as parents: a perlin mask blended with its own
inverse, and rings crossed with spokes.

```edn [field-box id="madp_ga_noise" role="properties"]
{"id" "madp_ga_noise" "index" 3 "name" "noise mask × rings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "noise mask × rings" "_tombstone" false "frame" #afield/rect [860.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "madp_ga_noise_code_.kt" "lines" 6 "characters" 232 "sha256" "e7593388c234bf0af6aaf8558542c5e06a3868876006753e39e554c2d678384e"}]}
```

```kotlin [field-property box="madp_ga_noise" name="code" path="madp_ga_noise_code_.kt"]
// Two of the demo's example programs as parents: a perlin mask blended with its own
// inverse, and rings crossed with spokes.
colorRpnEvolution(
    "x 8 * perlin dup invert 0.3 blend",
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
)
```

