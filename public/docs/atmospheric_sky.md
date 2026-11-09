# Atmospheric sky

A sky that comes out of the physics of air: sunlight scattered by molecules and haze on its way
through a spherical atmosphere. Blue overhead at noon, orange along the horizon at sunset, a band of
colour after the sun has gone, and a blue rim around the planet seen from space all come from the
same few lines of shader, with nothing painted in. The sun's height, your altitude and the camera
are sliders, and the shader is a box property you can edit while it runs.

The scattering lives in a shader include, `sky/atmosphere.glsl`, which any Field shader can pull in
with `#include "sky/atmosphere.glsl"`; no setup is needed. Its Kotlin twin,
`afield.graphics.sky.Atmosphere`, computes the same numbers on the CPU.

## A sky beneath the renderer

The box draws one triangle that covers the window, beneath a renderer parent (see
[Field 4 Graphics](afield_four_graphics.md) for that arrangement). Its code makes the module, hands
the shader its settings as push constants, and puts sliders in its output:

```diffstatic_runner {data-lang=kotlin screenshot="assets/atmospheric_sky/noon.png" alt="A noon sky seen from 3.5 km: deep blue overhead fading to pale blue and white at the horizon, the small white sun about 30 degrees up, and pale hazy ground below" timeout="120" capture="native-window" native-wait-frames="30" embed=true name="atmospheric-sky-noon" macro-file="../../dev_docs/agents/transcripts/atmospheric_sky.yaml" capture-box="atmospheric_sky"}
/*
A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or edit the numbers
below and re-run.
*/
var sunElevation = 30.0   // degrees above the horizon
var sunAzimuth = 0.0      // degrees, 0 is straight ahead
var altitude = 3.5        // km above the ground
var pitch = 10.0          // degrees the camera looks up (-90 looks straight down)
var exposure = 2.0
var fieldOfView = 90.0    // degrees, vertical

val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
renderer += "atmospheric-sky" to sky

quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), altitude, Math.toRadians(sunElevation)) }
quad.view = { vec(Math.toRadians(sunAzimuth), Math.toRadians(pitch), exposure, Math.toRadians(fieldOfView)) }

controlGroup("Sky") {
    slider("Sun elevation (degrees)", min = -12.0, max = 90.0, initialValue = sunElevation) { sunElevation = it }
    slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = sunAzimuth) { sunAzimuth = it }
    slider("Altitude (log10 km)", min = -3.0, max = 4.5, initialValue = Math.log10(altitude)) { altitude = Math.pow(10.0, it) }
    slider("Camera pitch (degrees)", min = -90.0, max = 90.0, initialValue = pitch) { pitch = it }
    slider("Exposure", min = 0.5, max = 40.0, initialValue = exposure) { exposure = it }
}
displayOpen("Sky")
println("atmospheric sky: sun ${sunElevation}°, altitude ${altitude} km")
```

The fragment shader turns each pixel into a view ray from a camera `altitude` km above the ground,
and asks the include what light arrives along it:

```diffstatic {data-lang=glsl}
#version 450
#include "sky/atmosphere.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, altitude (km), sun elevation (radians)
    vec4 view;     // sun azimuth, camera pitch, exposure, vertical field of view (radians)
} pc;

void main() {
    Atmosphere a = earthAtmosphere();

    // A pinhole camera at the given altitude, looking along -z and pitched up by view.y.
    vec2 uv = (gl_FragCoord.xy - 0.5 * pc.viewport.xy) / pc.viewport.y;
    uv.y = -uv.y;
    float pitch = pc.view.y;
    vec3 forward = vec3(0.0, sin(pitch), -cos(pitch));
    vec3 right = vec3(1.0, 0.0, 0.0);
    vec3 up = cross(right, forward);
    vec3 rd = normalize(forward + (uv.x * right + uv.y * up) * 2.0 * tan(0.5 * pc.view.w));
    vec3 ro = vec3(0.0, a.planetRadius + pc.viewport.z, 0.0);

    float e = pc.viewport.w, az = pc.view.x;
    vec3 sun = vec3(cos(e) * sin(az), sin(e), -cos(e) * cos(az));

    vec3 L = skyRadiance(a, ro, rd, sun, vec3(20.0), 0.00465, 32, 8);

    // Exposure, then a soft shoulder so the sun's disc saturates instead of clipping hue.
    color = vec4(1.0 - exp(-L * pc.view.z), 1.0);
}
```

`quad.viewport = { vec(...) }` fills the push-constant member `viewport` every frame, so moving a
slider changes the sky on the next frame without re-running anything. A slider keeps its position
when the box is re-run; edit the numbers at the top of the code to start somewhere else in a fresh
document.

## Sunset and twilight

Lower the sun and the light reaching you has crossed far more air. Blue is scattered out of it
first, so what is left is yellow and red, and the haze near the ground glows orange. These captures
start the same box with different values:

```diffstatic_runner {data-lang=kotlin screenshot="assets/atmospheric_sky/sunset.png" alt="A sunset from 3.5 km: the sun is a bright yellow glow on the horizon, the sky fades from grey-blue overhead to yellow, and the ground below is lit orange-brown" timeout="120" capture="native-window" native-wait-frames="30" embed=true name="atmospheric-sky-sunset" macro-file="../../dev_docs/agents/transcripts/docs_capture/atmospheric_sky_sunset.yaml" capture-box="atmospheric_sky"}
var sunElevation = 2.0
var pitch = 5.0
var exposure = 4.0
```

With the sun four degrees below the horizon the ground is in the planet's shadow, but the upper air
is still lit: a band of orange along the horizon under a violet sky. The exposure is ten times
higher, as your eye would adapt.

```diffstatic_runner {data-lang=kotlin screenshot="assets/atmospheric_sky/twilight.png" alt="Twilight: a black ground below a curved horizon, a thin band of orange and yellow along it, then pink and violet sky above" timeout="120" capture="native-window" native-wait-frames="30" embed=true name="atmospheric-sky-twilight" macro-file="../../dev_docs/agents/transcripts/docs_capture/atmospheric_sky_twilight.yaml" capture-box="atmospheric_sky"}
var sunElevation = -4.0
var pitch = 5.0
var exposure = 40.0
```

## The planet from space

The same function works from outside the atmosphere. At 20,000 km, looking straight down, rays that
miss the shell see black space, rays that graze it pick up a thin blue rim, and rays that meet the
ground return its sunlit colour through the air. Day fades into night gradually, because the
sunlight reaching the edge of day has itself crossed a long path of air.

```diffstatic_runner {data-lang=kotlin screenshot="assets/atmospheric_sky/space.png" alt="The planet from 20,000 km against black space: a grey-white disc lit from the top, with a faint blue rim at its edge and the lower part fading through purple into night" timeout="120" capture="native-window" native-wait-frames="30" embed=true name="atmospheric-sky-space" macro-file="../../dev_docs/agents/transcripts/docs_capture/atmospheric_sky_space.yaml" capture-box="atmospheric_sky"}
var sunElevation = 20.0
var altitude = 20000.0
var pitch = -90.0
var exposure = 0.6
var fieldOfView = 40.0
```

## What the include computes

Distances are in kilometres and the planet's centre is the origin. `earthAtmosphere()` returns an
`Atmosphere` struct with Earth's values; change any field before you call the functions below, for
an alien sky or a thicker haze:

| Field | Earth | Meaning |
| --- | --- | --- |
| `planetRadius`, `atmosphereRadius` | 6360, 6460 km | the ground and the top of the air |
| `rayleighScattering`, `rayleighHeight` | (5.8, 13.6, 33.1)×10⁻³ /km, 8 km | molecules: scatter blue most, thin out with height |
| `mieScattering`, `mieAbsorption`, `mieHeight`, `mieG` | 4.0×10⁻³, 4.4×10⁻³ /km, 1.2 km, 0.8 | haze: grey, low, scatters forward |
| `ozoneAbsorption`, `ozoneCenter`, `ozoneHalfWidth` | (0.65, 1.88, 0.085)×10⁻³ /km, 25 km, 15 km | absorbs orange, which keeps twilight blue-violet |
| `groundAlbedo` | 0.3 | how much light the ground reflects |

- `skyRadiance(a, ro, rd, sunDir, sunColor, sunAngularRadius, steps, lightSteps)`: everything a camera
  at `ro` sees along `rd`, meaning scattered light, the lit ground, the sun's disc and black space.
- `skyInScatter(...)`: the scattered light along one segment of a ray, and the transmittance across it.
- `skyTransmittanceToTop(a, p, dir, steps)`: the fraction of light that gets from `p` out of the
  atmosphere along `dir`; zero in the planet's shadow.
- `skyRayleighPhase(mu)`, `skyMiePhase(mu, g)`: how much light turns through an angle whose cosine
  is `mu`.

`steps` and `lightSteps` (32 and 8 in the box) trade speed for smoothness: the view ray is cut into
`steps` pieces, and from each a second ray toward the sun is cut into `lightSteps`.

On the CPU, `Atmosphere.radiance(Atmosphere.Params(), ro, rd, sunDir)` returns the value
`skyRadiance` would, which is useful for colouring a light by the sun's elevation.

## How it works

The method is single scattering, after Nishita and colleagues. At each sample along the view ray, air
scatters sunlight toward the camera in proportion to its density and the phase function, and that
light is dimmed twice: by the air between the sample and the sun (the second march), and by the air
between the sample and the camera. Rayleigh scattering by molecules goes as the inverse fourth power
of wavelength, which is why the sky is blue and the low sun red. Mie scattering by haze uses the
Cornette-Shanks phase function, which brightens the sky around the sun. Ozone only absorbs.

Light that scatters more than once is left out, so deep twilight comes out darker than a real one
and the sky close to the horizon slightly too dark.

- Tomoyuki Nishita, Takao Sirai, Katsumi Tadamura, Eihachiro Nakamae, "Display of the Earth Taking
  into Account Atmospheric Scattering", SIGGRAPH 1993.
  [doi:10.1145/166117.166140](https://doi.org/10.1145/166117.166140)
- Sébastien Hillaire, "A Scalable and Production Ready Sky and Atmosphere Rendering Technique",
  Computer Graphics Forum (EGSR) 2020, for the Earth coefficients and the ozone profile.
  [Paper](https://sebh.github.io/publications/egsr2020.pdf)
- Maxime Heckel, ["On rendering the sky, sunsets and planets"](https://blog.maximeheckel.com/posts/on-rendering-the-sky-sunsets-and-planets),
  a walkthrough of the same method, which this page follows.
