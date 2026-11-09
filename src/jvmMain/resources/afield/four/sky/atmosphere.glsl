// sky/atmosphere.glsl — physically based single scattering in a spherical atmosphere.
//
// #include "sky/atmosphere.glsl" from any Field shader. Distances are kilometres, the planet's centre
// is the origin. The method is Nishita et al. 1993 ("Display of the Earth Taking into Account
// Atmospheric Scattering"): march the view ray through the shell, and at every sample march a second
// ray to the sun for its optical depth. Coefficients are Earth's, as tabulated by Hillaire 2020
// ("A Scalable and Production Ready Sky and Atmosphere Rendering Technique"). The CPU twin of every
// function here is afield.graphics.sky.Atmosphere; keep the two in step (AtmosphereTest compares them).

#ifndef FIELD_SKY_ATMOSPHERE
#define FIELD_SKY_ATMOSPHERE

const float SKY_PI = 3.14159265358979;

struct Atmosphere {
    float planetRadius;      // km
    float atmosphereRadius;  // km, top of the shell
    vec3 rayleighScattering; // per km at sea level
    float rayleighHeight;    // km, exponential scale height
    float mieScattering;     // per km at sea level
    float mieAbsorption;     // per km at sea level
    float mieHeight;         // km
    float mieG;              // Cornette-Shanks asymmetry
    vec3 ozoneAbsorption;    // per km at the peak of the layer
    float ozoneCenter;       // km
    float ozoneHalfWidth;    // km, the tent falls to zero this far either side
    vec3 groundAlbedo;
};

Atmosphere earthAtmosphere() {
    Atmosphere a;
    a.planetRadius = 6360.0;
    a.atmosphereRadius = 6460.0;
    a.rayleighScattering = vec3(5.802e-3, 13.558e-3, 33.1e-3);
    a.rayleighHeight = 8.0;
    a.mieScattering = 3.996e-3;
    a.mieAbsorption = 4.40e-3;
    a.mieHeight = 1.2;
    a.mieG = 0.8;
    a.ozoneAbsorption = vec3(0.650e-3, 1.881e-3, 0.085e-3);
    a.ozoneCenter = 25.0;
    a.ozoneHalfWidth = 15.0;
    a.groundAlbedo = vec3(0.10, 0.12, 0.15); // mostly ocean
    return a;
}

// Distances along ro + t*rd to the two crossings of the sphere |p| = r, or vec2(-1) for a miss.
vec2 skyRaySphere(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float d = b * b - c;
    if (d < 0.0) return vec2(-1.0);
    float s = sqrt(d);
    return vec2(-b - s, -b + s);
}

// Relative densities (Rayleigh, Mie, ozone) at altitude h km.
vec3 skyDensity(Atmosphere a, float h) {
    return vec3(exp(-h / a.rayleighHeight), exp(-h / a.mieHeight),
                max(0.0, 1.0 - abs(h - a.ozoneCenter) / a.ozoneHalfWidth));
}

vec3 skyExtinction(Atmosphere a, vec3 density) {
    return a.rayleighScattering * density.x
         + vec3(a.mieScattering + a.mieAbsorption) * density.y
         + a.ozoneAbsorption * density.z;
}

float skyRayleighPhase(float mu) { return 3.0 / (16.0 * SKY_PI) * (1.0 + mu * mu); }

// Cornette-Shanks: Henyey-Greenstein corrected so the Rayleigh limit (g = 0) is right.
float skyMiePhase(float mu, float g) {
    float g2 = g * g;
    return 3.0 / (8.0 * SKY_PI) * (1.0 - g2) * (1.0 + mu * mu)
         / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * g * mu, 1.5));
}

// Transmittance from p to the top of the atmosphere along dir, by midpoint integration. Zero when the
// ray meets the planet first: the sample is in the planet's shadow.
vec3 skyTransmittanceToTop(Atmosphere a, vec3 p, vec3 dir, int steps) {
    vec2 g = skyRaySphere(p, dir, a.planetRadius);
    if (g.x > 0.0) return vec3(0.0);
    float t = skyRaySphere(p, dir, a.atmosphereRadius).y;
    if (t <= 0.0) return vec3(1.0);
    float dt = t / float(steps);
    vec3 depth = vec3(0.0);
    for (int i = 0; i < steps; i++) {
        vec3 q = p + dir * ((float(i) + 0.5) * dt);
        depth += skyExtinction(a, skyDensity(a, length(q) - a.planetRadius)) * dt;
    }
    return exp(-depth);
}

// Single-scattered radiance along ro + t*rd for t in [t0, t1], for a sun of irradiance sunColor.
// transmittance returns what survives from t1 back to t0.
vec3 skyInScatter(Atmosphere a, vec3 ro, vec3 rd, float t0, float t1, vec3 sunDir, vec3 sunColor,
                  int steps, int lightSteps, out vec3 transmittance) {
    float mu = dot(rd, sunDir);
    float pr = skyRayleighPhase(mu);
    float pm = skyMiePhase(mu, a.mieG);
    float dt = (t1 - t0) / float(steps);
    vec3 sum = vec3(0.0);
    vec3 depth = vec3(0.0);
    for (int i = 0; i < steps; i++) {
        vec3 p = ro + rd * (t0 + (float(i) + 0.5) * dt);
        vec3 d = skyDensity(a, length(p) - a.planetRadius);
        vec3 ext = skyExtinction(a, d);
        vec3 viewT = exp(-(depth + ext * 0.5 * dt));
        vec3 sunT = skyTransmittanceToTop(a, p, sunDir, lightSteps);
        vec3 scatter = a.rayleighScattering * d.x * pr + vec3(a.mieScattering) * d.y * pm;
        sum += viewT * sunT * scatter * dt;
        depth += ext * dt;
    }
    transmittance = exp(-depth);
    return sum * sunColor;
}

// Everything a camera at ro looking along rd sees: atmosphere, the lit planet below, the sun's disc,
// and black space. sunAngularRadius in radians (the Sun's is 0.00465).
vec3 skyRadiance(Atmosphere a, vec3 ro, vec3 rd, vec3 sunDir, vec3 sunColor, float sunAngularRadius,
                 int steps, int lightSteps) {
    vec2 top = skyRaySphere(ro, rd, a.atmosphereRadius);
    if (top.y <= 0.0) {
        return dot(rd, sunDir) > cos(sunAngularRadius) ? sunColor * 1000.0 : vec3(0.0);
    }
    float t0 = max(top.x, 0.0);
    float t1 = top.y;
    vec2 ground = skyRaySphere(ro, rd, a.planetRadius);
    bool hitGround = ground.x > 0.0;
    if (hitGround) t1 = ground.x;
    vec3 trans;
    vec3 L = skyInScatter(a, ro, rd, t0, t1, sunDir, sunColor, steps, lightSteps, trans);
    if (hitGround) {
        vec3 p = ro + rd * t1;
        vec3 n = normalize(p);
        vec3 sunT = skyTransmittanceToTop(a, p + n * 0.01, sunDir, lightSteps);
        L += trans * a.groundAlbedo / SKY_PI * sunColor * sunT * max(dot(n, sunDir), 0.0);
    } else if (dot(rd, sunDir) > cos(sunAngularRadius)) {
        L += trans * sunColor * 1000.0;
    }
    return L;
}

#endif
