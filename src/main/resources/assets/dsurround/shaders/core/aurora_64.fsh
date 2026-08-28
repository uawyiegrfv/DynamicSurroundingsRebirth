#version 150

// Clean-room aurora fragment shader (Dynamic Surroundings Remake).
// Original work: no third-party shader code is reproduced here. The visual
// language (value-noise FBM curtain, vertical ray structure, spectrum color
// ramp) uses standard public-domain techniques.
//
// Inputs from the vertex stage:
//   texCoord0.x = u along the ribbon (0 at one end, 1 at the other)
//   texCoord0.y = v along the curtain height (0 bottom, 1 top)
//   vertexColor.a = fade in/out of the whole aurora (0-1)

uniform float GameTime;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Set per pipeline variant via withShaderDefine: corrects the noise domain
// for the ribbon's width/height aspect so rays are not stretched.
const float ASPECT_RATIO = 4.5;

// ---------------------------------------------------------------- noise ----

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1130, 0.1379));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amp * vnoise(p);
        p = p * 2.03 + vec2(19.19, 7.57);
        amp *= 0.5;
    }
    return value;
}

// ---------------------------------------------------------------- color ----

const vec3 VIOLET = vec3(0.40, 0.26, 0.95);
const vec3 GREEN  = vec3(0.16, 0.82, 0.38);
const vec3 RED    = vec3(0.95, 0.16, 0.28);

// Overall brightness. Additive blending overexposes fast; raise in +0.1
// steps only if needed (1.32 with the old dark ray gaps read as glaring).
const float BRIGHTNESS = 1.2;

void main() {
    vec2 uv = texCoord0;
    vec2 p = vec2(uv.x * ASPECT_RATIO, uv.y);

    // GameTime is the day fraction; scaled to ~2 units per real second.
    float t = GameTime * 2400.0;

    // Cheap exits first: above the safety sky fade and beyond the ribbon-end
    // fades the output is exactly zero, so skip the whole noise stack there.
    float edgeFade = smoothstep(0.0, 0.18, uv.x) * (1.0 - smoothstep(0.82, 1.0, uv.x));
    float skyFade = 1.0 - smoothstep(0.82, 0.98, p.y);
    if (vertexColor.a * edgeFade * skyFade < 0.004) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // 1) Slow curtain body: gentle domain warp keeps the sheet continuous and
    //    cloth-like without ever folding it into bright blobs.
    vec2 warp = vec2(fbm(p * 0.9 + vec2(0.0, t * 0.040)),
                     fbm(p * 0.9 + vec2(4.7, 1.9) - vec2(t * 0.018, 0.0)));
    float curtain = fbm(p * vec2(1.6, 0.7) + warp * 1.1 + vec2(0.0, t * 0.025));

    // 2) Height breathing: a slow coherent rise/fall (~25 s period) layered on
    //    top of the noise-driven edges, evoking magnetospheric substorm heaving.
    //    Amplitude stays small so it reads as breathing, not bouncing.
    float breath = 0.5 + 0.5 * sin(t * 0.126);

    // 3) Vertical envelope: bottom edge fairly crisp, top edge dissolving.
    //    top + its fade width must stay below the geometric edge, otherwise
    //    the quad shows as a hard cut; a safety skyFade guarantees the
    //    intensity reaches zero before v = 1.
    float bottom = 0.06 + 0.10 * curtain + 0.03 * breath;
    float top = 0.50 + 0.30 * curtain + 0.02 * breath;
    float bottomMask = smoothstep(0.0, 0.12, p.y - bottom);
    float topMask = 1.0 - smoothstep(0.0, 0.40, p.y - top);
    float envelope = bottomMask * topMask * skyFade;

    // 4) Bottom rim: particle precipitation is densest at the lower edge, so
    //    lit rays get a gentle boost near the base. The falloff is wide and
    //    modulated by the curtain so the glow stays patchy along the ribbon —
    //    a uniform, sharp rim would read as a hard horizontal band.
    float rim = (1.0 - smoothstep(0.0, 0.28, p.y - bottom)) * (0.55 + 0.45 * curtain);

    // 5) Vertical rays: high x frequency vs low y frequency stretches the
    //    noise into streaks along the height. A small y tilt and a small
    //    curtain-dependent bend keep the rays organic without losing the
    //    "first glance reads as vertical" look (tilt <= 0.06, bend <= 0.15).
    //    The x frequency stays constant with height: modulating it makes the
    //    noise domain compress vertically and produces Jupiter-like stripes.
    vec2 rayP = p;
    rayP.x += 0.06 * p.y;
    rayP.x += 0.15 * curtain;
    float ray = fbm(vec2(rayP.x * 5.0, rayP.y * 0.35 + t * 0.05));
    // Contrast shaping: plain FBM output is too flat to read as distinct
    // rays, but full contrast carves the sheet into broken segments. The
    // mid-point settings keep rays readable while dark gaps stay lit enough
    // that the curtain reads as one continuous sheet.
    float rayShaped = smoothstep(0.24, 0.76, ray);

    // 6) Traveling highlights: a low-frequency brightness pattern drifting
    //    along the ribbon. Where it peaks, a few vertical rays light up and
    //    visibly sweep a short stretch of the curtain. x frequency 1.6 keeps
    //    the lit stretch narrow (a ray or two, not a wide slab).
    float sweep = fbm(vec2(rayP.x * 1.6 - t * 0.045, 3.1 + t * 0.02));
    float sweepBoost = 0.85 + 0.35 * smoothstep(0.50, 0.92, sweep);

    // 7) Clove-like light/dark variation: a medium-scale, slowly drifting
    //    modulation of the whole sheet for a cloth-like patchiness. Amplitude
    //    stays low so it never collects into a bright blob.
    float lobe = fbm(p * vec2(2.1, 1.05) + warp * 0.9 + vec2(t * 0.012, -t * 0.008));
    float lobeMask = 0.80 + 0.35 * lobe;

    // 8) Soft fade at both ribbon ends (edgeFade was computed for the early-out).

    // 9) Spectrum: violet at the base, green body, red/pink cap, with the
    //    very top drifting slightly warm/white like high-altitude emission.
    float greenMix = smoothstep(0.02, 0.38, p.y + 0.22 * curtain - 0.08 * ray);
    float redMix = smoothstep(0.42, 0.88, p.y + 0.18 * ray - 0.10 * curtain);
    vec3 color = mix(VIOLET, GREEN, greenMix);
    color = mix(color, RED, redMix);
    color = mix(color, color * vec3(1.06, 0.96, 0.90), 0.35 * smoothstep(0.60, 1.0, p.y));

    // 10) Per-band hue shift: vertexColor.rgb carries the band tint around a
    //     neutral mid-gray (byte 128 = 1.0), so multiply by 2.0 re-centers it.
    color *= vertexColor.rgb * 2.0;

    // 11) Ray modulation plus a fast, subtle shimmer (per-region phase, never
    //     a synchronized global blink). rimBoost gently brightens lit rays in
    //     the patchy bottom rim.
    float flicker = fbm(vec2(p.x * 6.0, t * 0.35));
    float rayMask = 0.52 + 0.48 * rayShaped;
    float flickerMask = 0.80 + 0.20 * flicker;
    float rimBoost = 1.0 + 0.22 * rim * rayShaped;

    float intensity = vertexColor.a * edgeFade * envelope * lobeMask * rayMask * sweepBoost * flickerMask * rimBoost * BRIGHTNESS;
    fragColor = vec4(color * intensity, 1.0);
}
