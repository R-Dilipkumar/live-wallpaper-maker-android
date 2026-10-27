package io.github.rdilipkumar.livewallpapermaker.engine.render

/**
 * GLSL ES 3.0 sources. Quads are -0.5..0.5 with y growing downwards (screen style);
 * vUv follows GL texture convention (v = 1 at the top).
 */
internal object Shaders {

  const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uMvp;
out vec2 vUv;
void main() {
  vUv = vec2(aPos.x + 0.5, 0.5 - aPos.y);
  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
}
"""

  private const val COMMON = """
float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
"""

  /** Layer masks (shape cut-outs and edge fades) and the time-of-day colour, shared by layer and gradient shaders. */
  private const val MASK_AND_DAY = """
uniform int uMask;          // 0 none, 1 circle, 2 rounded, 3 heart, 4 star, 5 soft edges, 6 fade top, 7 fade bottom
uniform float uFeather;     // 0..1
uniform vec4 uDay;          // rgb tint, amount
uniform float uDim;

float dot2(vec2 v) { return dot(v, v); }

float sdHeart(vec2 p) {
  p.x = abs(p.x);
  if (p.y + p.x > 1.0) return sqrt(dot2(p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
  return sqrt(min(dot2(p - vec2(0.00, 1.00)), dot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
}

float sdStar5(vec2 p, float r, float rf) {
  const vec2 k1 = vec2(0.809016994375, -0.587785252292);
  const vec2 k2 = vec2(-k1.x, k1.y);
  p.x = abs(p.x);
  p -= 2.0 * max(dot(k1, p), 0.0) * k1;
  p -= 2.0 * max(dot(k2, p), 0.0) * k2;
  p.x = abs(p.x);
  p.y -= r;
  vec2 ba = rf * vec2(-k1.y, k1.x) - vec2(0.0, 1.0);
  float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
  return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
}

/** Mask coverage 0..1 at layer position uv (GL convention) for a layer of size px. */
float maskAlpha(vec2 uv, vec2 size) {
  if (uMask == 0) return 1.0;
  vec2 q = vec2(uv.x, 1.0 - uv.y);
  float f = max(uFeather, 0.002);
  float s = min(size.x, size.y);
  vec2 sq = vec2((q.x - 0.5) * size.x / s, (0.5 - q.y) * size.y / s);  // square units, y up
  if (uMask == 1) {
    float d = length(sq) - 0.5;
    return 1.0 - smoothstep(-f * 0.5, 0.0, d);
  }
  if (uMask == 2) {
    vec2 h = 0.5 * size / s;
    float rad = 0.12;
    vec2 d = abs(sq) - (h - rad);
    float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - rad;
    return 1.0 - smoothstep(-f * 0.25, 0.0, dist);
  }
  if (uMask == 3) {
    float d = sdHeart(sq * 1.25 + vec2(0.0, 0.5));
    return 1.0 - smoothstep(-f * 0.3 - 0.004, 0.0, d);
  }
  if (uMask == 4) {
    float d = sdStar5(sq, 0.48, 0.45);
    return 1.0 - smoothstep(-f * 0.2 - 0.004, 0.0, d);
  }
  if (uMask == 5) {
    float e = min(min(q.x, 1.0 - q.x) * size.x, min(q.y, 1.0 - q.y) * size.y) / s;
    return smoothstep(0.0, f * 0.5 + 0.02, e);
  }
  if (uMask == 6) return smoothstep(0.0, f * 1.5 + 0.05, q.y);
  if (uMask == 7) return smoothstep(0.0, f * 1.5 + 0.05, 1.0 - q.y);
  return 1.0;
}

vec3 applyDay(vec3 rgb) {
  if (uDay.a <= 0.0 && uDim <= 0.0) return rgb;
  vec3 t = uDay.rgb / max(max(uDay.r, uDay.g), max(uDay.b, 0.001));
  return mix(rgb, rgb * t, uDay.a) * (1.0 - uDim);
}
"""

  /** Media/text layers: sampling plus every per-layer effect, in one pass. */
  fun layerFs(oes: Boolean): String {
    val header = if (oes) {
      "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\nuniform samplerExternalOES uTex;\n"
    } else {
      "#version 300 es\nprecision highp float;\nuniform sampler2D uTex;\n"
    }
    return header + COMMON + MASK_AND_DAY + """
in vec2 vUv;
uniform mat4 uTexMatrix;
uniform float uOpacity;
uniform vec2 uSize;      // drawn size in pixels
uniform float uTime;
uniform vec4 uAdjust;    // brightness, contrast, saturation, hue (radians)
uniform vec4 uTint;      // rgb, amount
uniform vec4 uFx;        // vignette, grain, pixelate cell (px), chromatic offset (px)
uniform vec2 uWave;      // amplitude (px), speed
out vec4 fragColor;

const mat3 TO_YIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
const mat3 TO_RGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);

vec4 tex(vec2 uv) {
  return texture(uTex, (uTexMatrix * vec4(clamp(uv, 0.0, 1.0), 0.0, 1.0)).xy);
}

void main() {
  vec2 uv = vUv;
  if (uFx.z > 1.0) {
    vec2 cell = vec2(uFx.z) / uSize;
    uv = (floor(uv / cell) + 0.5) * cell;
  }
  if (uWave.x > 0.0) {
    float t = uTime * uWave.y;
    uv.x += sin(uv.y * 16.0 + t * 2.1) * uWave.x / uSize.x;
    uv.y += cos(uv.x * 12.0 + t * 1.7) * uWave.x * 0.6 / uSize.y;
  }
  vec4 c;
  if (uFx.w > 0.0) {
    vec2 o = vec2(uFx.w / uSize.x, 0.0);
    vec4 r = tex(uv + o);
    vec4 g = tex(uv);
    vec4 b = tex(uv - o);
    c = vec4(r.r, g.g, b.b, max(g.a, max(r.a, b.a)));
  } else {
    c = tex(uv);
  }
  float a = c.a;
  if (a < 0.002) discard;
  vec3 rgb = c.rgb / a;

  rgb += uAdjust.x;
  rgb = (rgb - 0.5) * uAdjust.y + 0.5;
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  rgb = mix(vec3(l), rgb, uAdjust.z);
  if (uAdjust.w != 0.0) {
    vec3 yiq = TO_YIQ * rgb;
    float cs = cos(uAdjust.w);
    float sn = sin(uAdjust.w);
    yiq.yz = vec2(yiq.y * cs - yiq.z * sn, yiq.y * sn + yiq.z * cs);
    rgb = TO_RGB * yiq;
  }
  if (uTint.a > 0.0) {
    float tl = dot(rgb, vec3(0.299, 0.587, 0.114));
    rgb = mix(rgb, uTint.rgb * (tl * 1.3 + 0.08), uTint.a);
  }
  if (uFx.x > 0.0) {
    float d = distance(vUv, vec2(0.5)) * 1.4142;
    rgb *= 1.0 - uFx.x * smoothstep(0.3, 1.0, d);
  }
  if (uFx.y > 0.0) {
    rgb += (hash12(vUv * uSize + fract(uTime * 7.31) * 517.0) - 0.5) * uFx.y * 0.35;
  }
  rgb = clamp(applyDay(rgb), 0.0, 1.0);
  float outA = a * uOpacity * maskAlpha(vUv, uSize);
  if (outA < 0.002) discard;
  fragColor = vec4(rgb * outA, outA);
}
"""
  }

  val GRADIENT_FS = """#version 300 es
precision highp float;
""" + COMMON + MASK_AND_DAY + """
in vec2 vUv;
uniform vec4 uColors[4];
uniform int uCount;
uniform vec2 uDir;       // unit direction in pixels (y down)
uniform int uRadial;
uniform float uPhase;    // 0..1, flowing colours
uniform int uFlow;
uniform float uOpacity;
uniform vec2 uSize;
out vec4 fragColor;

vec4 ramp(float t) {
  float x = clamp(t, 0.0, 1.0) * float(uCount - 1);
  int i = min(int(floor(x)), uCount - 2);
  float f = x - float(i);
  f = f * f * (3.0 - 2.0 * f);
  return mix(uColors[i], uColors[i + 1], f);
}

void main() {
  vec2 q = vec2(vUv.x, 1.0 - vUv.y);
  vec2 p = (q - 0.5) * uSize;
  float t;
  if (uRadial == 1) {
    t = length(p) / (0.5 * length(uSize));
  } else {
    float span = abs(uDir.x) * uSize.x + abs(uDir.y) * uSize.y;
    t = dot(p, uDir) / span + 0.5;
  }
  if (uFlow == 1) {
    float x = fract(t * 0.5 + uPhase);
    t = 1.0 - abs(2.0 * x - 1.0);
  }
  vec4 c = ramp(t);
  vec3 rgb = applyDay(c.rgb) + (hash12(gl_FragCoord.xy) - 0.5) / 255.0;
  float a = c.a * uOpacity * maskAlpha(vUv, uSize);
  fragColor = vec4(rgb * a, a);
}
"""

  /** Water ripples from taps: bends the finished scene around expanding rings. */
  val RIPPLE_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec4 uRipples[6];   // centre (uv), age 0..1, strength
uniform int uCount;
uniform vec2 uSize;
out vec4 fragColor;
void main() {
  vec2 asp = vec2(uSize.x / uSize.y, 1.0);
  vec2 offset = vec2(0.0);
  float light = 0.0;
  for (int i = 0; i < 6; i++) {
    if (i >= uCount) break;
    vec4 r = uRipples[i];
    vec2 d = (vUv - r.xy) * asp;
    float dist = length(d);
    float radius = r.z * 0.75;
    float x = dist - radius;
    float fade = (1.0 - r.z) * (1.0 - r.z) * r.w;
    float wave = sin(x * 70.0) * exp(-x * x * 160.0) * fade;
    vec2 dir = dist > 0.0001 ? d / dist : vec2(0.0);
    offset += dir / asp * wave * 0.024;
    light += wave * 0.16;
  }
  vec4 c = texture(uScene, vUv + offset);
  fragColor = vec4(c.rgb + light, 1.0);
}
"""

  /** Sparks flying out of a tap (points built from gl_VertexID). */
  const val BURST_VS = """#version 300 es
uniform mat4 uMvp;
uniform vec2 uOrigin;   // pixels
uniform float uAge;     // seconds
uniform float uScale;   // pixel scale for this screen
out float vAlpha;
out float vSeed;
float h(float n) { return fract(sin(n * 91.3458 + 17.17) * 47453.5453); }
void main() {
  float id = float(gl_VertexID);
  float a = h(id) * 6.2831853;
  float sp = (180.0 + 520.0 * h(id + 3.1)) * uScale;
  float t = uAge;
  vec2 dir = vec2(cos(a), sin(a));
  vec2 p = uOrigin + dir * sp * t * (1.0 - 0.45 * t) + vec2(0.0, 260.0 * uScale) * t * t;
  float life = 0.7 + 0.6 * h(id + 7.7);
  float k = clamp(t / life, 0.0, 1.0);
  vAlpha = pow(1.0 - k, 1.6) * (0.6 + 0.4 * sin(t * 30.0 + id));
  vSeed = h(id + 9.9);
  gl_Position = uMvp * vec4(p, 0.0, 1.0);
  gl_PointSize = (6.0 + 10.0 * h(id + 5.5)) * uScale * (1.0 - 0.5 * k);
}
"""

  const val BURST_FS = """#version 300 es
precision mediump float;
in float vAlpha;
in float vSeed;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
  vec2 pc = gl_PointCoord - 0.5;
  float d = length(pc);
  float star = max(smoothstep(0.08, 0.0, abs(pc.x)) * smoothstep(0.5, 0.0, abs(pc.y)),
                   smoothstep(0.08, 0.0, abs(pc.y)) * smoothstep(0.5, 0.0, abs(pc.x)));
  float a = (smoothstep(0.5, 0.0, d) * 0.7 + star * 0.6) * vAlpha * uColor.a;
  vec3 rgb = mix(uColor.rgb, vec3(1.0), 0.35 * vSeed);
  fragColor = vec4(rgb * a, a);
}
"""

  /** A soft light that blooms where you tap. */
  const val GLOW_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform vec4 uColor;
uniform float uFade;
out vec4 fragColor;
void main() {
  float d = length(vUv - 0.5) * 2.0;
  float a = exp(-d * d * 4.0) * (1.0 - smoothstep(0.85, 1.0, d)) * uFade * uColor.a;
  fragColor = vec4(uColor.rgb * a, a);
}
"""

  const val PARTICLE_VS = """#version 300 es
uniform mat4 uMvp;
uniform float uTime;
uniform int uKind;
uniform float uSizeScale;
uniform float uWind;
out float vAlpha;
out float vSeed;

float h(float n) { return fract(sin(n * 127.1 + 311.7) * 43758.5453); }

void main() {
  float id = float(gl_VertexID);
  float r1 = h(id + 0.13);
  float r2 = h(id + 1.71);
  float r3 = h(id + 2.93);
  float r4 = h(id + 4.17);
  float t = uTime;
  vec2 p;
  float size;
  float alpha;
  if (uKind == 0) {            // snow
    p.y = fract(r2 + t * (0.035 + 0.06 * r3));
    p.x = fract(r1 + uWind * t * 0.04 + 0.015 * sin(t * (0.6 + r4) + r1 * 6.283));
    size = 7.0 + 15.0 * r3 * r3;
    alpha = 0.55 + 0.45 * r4;
  } else if (uKind == 1) {     // rain
    p.y = fract(r2 + t * (0.7 + 0.6 * r3));
    p.x = fract(r1 + uWind * p.y * 0.12);
    size = 22.0 + 26.0 * r3;
    alpha = 0.22 + 0.33 * r4;
  } else if (uKind == 2) {     // bokeh
    p.y = fract(r2 - t * (0.006 + 0.012 * r3));
    p.x = fract(r1 + 0.02 * sin(t * 0.25 + r4 * 6.283) + uWind * t * 0.01);
    size = 50.0 + 150.0 * r3;
    alpha = 0.10 + 0.22 * r4;
  } else if (uKind == 3) {     // stars
    p = vec2(r1, r2);
    size = 2.0 + 4.0 * r3 * r3;
    alpha = 0.35 + 0.65 * (0.5 + 0.5 * sin(t * (0.8 + 2.5 * r4) + r1 * 40.0));
  } else if (uKind == 4) {     // fireflies
    p = fract(vec2(
      r1 + 0.06 * sin(t * (0.15 + 0.2 * r3) + r4 * 6.283),
      r2 + 0.06 * cos(t * (0.12 + 0.2 * r4) + r3 * 6.283)));
    size = 10.0 + 14.0 * r3;
    alpha = pow(0.5 + 0.5 * sin(t * (0.7 + 1.3 * r4) + r1 * 20.0), 3.0);
  } else {                     // bubbles
    p.y = fract(r2 - t * (0.025 + 0.045 * r3));
    p.x = fract(r1 + 0.02 * sin(t * (0.7 + r4) + r1 * 6.283) + uWind * t * 0.01);
    size = 14.0 + 40.0 * r3;
    alpha = 0.55 + 0.3 * r4;
  }
  if (uKind == 0 || uKind == 1 || uKind == 2 || uKind == 5) {
    alpha *= smoothstep(0.0, 0.04, p.y) * smoothstep(1.0, 0.96, p.y);
  }
  vAlpha = alpha;
  vSeed = r4;
  gl_Position = uMvp * vec4(p - 0.5, 0.0, 1.0);
  gl_PointSize = max(1.0, size * uSizeScale);
}
"""

  const val PARTICLE_FS = """#version 300 es
precision mediump float;
in float vAlpha;
in float vSeed;
uniform int uKind;
uniform vec4 uColor;
uniform float uOpacity;
uniform float uWind;
out vec4 fragColor;
void main() {
  vec2 pc = gl_PointCoord - 0.5;
  float d = length(pc);
  float a;
  if (uKind == 1) {
    float x = pc.x + pc.y * uWind * 0.35;
    a = smoothstep(0.05, 0.0, abs(x)) * smoothstep(0.5, 0.15, abs(pc.y));
  } else if (uKind == 2) {
    a = smoothstep(0.5, 0.38, d) * (0.75 + 0.25 * smoothstep(0.3, 0.46, d));
  } else if (uKind == 4) {
    a = exp(-d * d * 28.0);
  } else if (uKind == 5) {
    a = smoothstep(0.5, 0.44, d) * (0.18 + 0.82 * smoothstep(0.3, 0.44, d));
  } else if (uKind == 3) {
    a = smoothstep(0.5, 0.0, d);
    a = a * a;
  } else {
    a = smoothstep(0.5, 0.1, d);
  }
  a *= vAlpha * uColor.a * uOpacity;
  vec3 rgb = uColor.rgb;
  if (uKind == 2) rgb = mix(rgb, vec3(1.0, 0.85 + 0.15 * vSeed, 0.7 + 0.3 * vSeed), 0.35);
  fragColor = vec4(rgb * a, a);
}
"""

  /** 9-tap Gaussian done with 5 bilinear fetches; run horizontally then vertically. */
  const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uStep;
out vec4 fragColor;
void main() {
  vec4 c = texture(uTex, vUv) * 0.2270270270;
  c += texture(uTex, vUv + uStep * 1.3846153846) * 0.3162162162;
  c += texture(uTex, vUv - uStep * 1.3846153846) * 0.3162162162;
  c += texture(uTex, vUv + uStep * 3.2307692308) * 0.0702702703;
  c += texture(uTex, vUv - uStep * 3.2307692308) * 0.0702702703;
  fragColor = c;
}
"""

  /** 2D scene transitions. 3D flips/cubes are drawn with tilted quads instead. */
  val TRANSITION_FS = """#version 300 es
precision highp float;
""" + COMMON + """
in vec2 vUv;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform float uP;
uniform int uType;
uniform vec2 uDir;
uniform vec4 uColor;
uniform vec2 uSize;
out vec4 fragColor;

bool inside(vec2 uv) { return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0; }
vec4 from(vec2 uv) { return inside(uv) ? texture(uFrom, uv) : vec4(0.0, 0.0, 0.0, 1.0); }
vec4 to(vec2 uv) { return inside(uv) ? texture(uTo, uv) : vec4(0.0, 0.0, 0.0, 1.0); }

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash12(i);
  float b = hash12(i + vec2(1.0, 0.0));
  float c = hash12(i + vec2(0.0, 1.0));
  float d = hash12(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

void main() {
  vec2 uv = vUv;
  float p = uP;
  vec2 c = vec2(0.5);
  if (uType == 2) {          // fade through colour
    fragColor = p < 0.5 ? mix(from(uv), uColor, p * 2.0) : mix(uColor, to(uv), (p - 0.5) * 2.0);
  } else if (uType == 3) {   // slide in over the old scene
    vec2 tuv = uv + uDir * (1.0 - p);
    fragColor = inside(tuv) ? to(tuv) : from(uv);
  } else if (uType == 4) {   // push the old scene out
    vec2 fuv = uv - uDir * p;
    vec2 tuv = uv - uDir * (p - 1.0);
    fragColor = inside(tuv) ? to(tuv) : from(fuv);
  } else if (uType == 5) {   // zoom in
    vec4 a = from(c + (uv - c) / (1.0 + p * 0.8));
    vec4 b = to(c + (uv - c) / (1.25 - 0.25 * p));
    fragColor = mix(a, b, smoothstep(0.15, 0.85, p));
  } else if (uType == 6) {   // zoom out
    vec4 a = from(c + (uv - c) / (1.0 - p * 0.35));
    vec4 b = to(c + (uv - c) / (0.8 + 0.2 * p));
    fragColor = mix(a, b, smoothstep(0.15, 0.85, p));
  } else if (uType == 9) {   // circle reveal
    vec2 asp = vec2(uSize.x / uSize.y, 1.0);
    float d = length((uv - c) * asp);
    float r = p * length(asp) * 0.55;
    fragColor = mix(to(uv), from(uv), smoothstep(r - 0.015, r + 0.015, d));
  } else if (uType == 10) {  // wipe
    float along = dot(uv - c, uDir) + 0.5;
    float e = 0.08;
    float m = 1.0 - smoothstep(p * (1.0 + e) - e, p * (1.0 + e), along);
    fragColor = mix(from(uv), to(uv), m);
  } else if (uType == 11) {  // noise dissolve
    float n = noise(uv * uSize / 28.0) * 0.7 + noise(uv * uSize / 7.0) * 0.3;
    float m = smoothstep(n - 0.06, n + 0.06, p * 1.12 - 0.06);
    fragColor = mix(from(uv), to(uv), m);
  } else if (uType == 12) {  // blur through
    vec4 a = textureLod(uFrom, uv, p * 7.0);
    vec4 b = textureLod(uTo, uv, (1.0 - p) * 7.0);
    fragColor = mix(a, b, smoothstep(0.3, 0.7, p));
  } else {                   // crossfade
    fragColor = mix(from(uv), to(uv), p);
  }
}
"""
}
