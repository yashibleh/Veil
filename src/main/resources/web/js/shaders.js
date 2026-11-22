// GLSL ES 3.00. SDF glass, smooth-min blob merging, edge refraction, chromatic dispersion,
// fresnel rim and glare follow the approach of iyinchao/liquid-glass-studio (MIT), written
// from scratch for VEIL's scene: drifting ink curtains + a glyph layer that the glass bends.

export const VERT = `#version 300 es
in vec2 aPos;
out vec2 vUv;
void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }`;

export const SCENE = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform vec2 uRes;
uniform float uTime;
uniform vec2 uMouse;
uniform vec2 uFocus;
uniform float uPulse;
uniform float uCalm;
uniform vec3 uAccent;
uniform sampler2D uGlyph;
uniform float uGlyphGain;
uniform vec3 uFlash;

float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p){
  vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p){
  float a = 0.5, s = 0.0;
  for (int i = 0; i < 4; i++){ s += a * noise(p); p = p * 2.03 + vec2(17.3, 9.1); a *= 0.5; }
  return s;
}
// One layer of silk: thin luminous threads whose phase is warped by noise.
// Returns (thread, glow) so the caller can colour them.
vec2 silk(vec2 p, float density, float t, float seed){
  vec2 q = vec2(fbm(p * 1.05 + vec2(0.0, t) + seed), fbm(p * 1.05 + vec2(4.7, 2.1) - t + seed));
  float phase = p.y * 5.2 + p.x * 1.5 + q.x * 3.4 + q.y * 1.5 + t * 2.0;
  float w = phase * density;
  float dist = abs(fract(w) - 0.5);
  float aa = fwidth(w) * 1.4 + 0.012;
  float thread = smoothstep(0.5 - aa - 0.018, 0.5, dist);
  float glow = exp(-(0.5 - dist) * 9.0) * 0.5;
  float env = smoothstep(0.30, 0.82, fbm(p * 0.85 + q * 1.3 + seed + t * 0.5));
  return vec2(thread, glow) * env;
}
void main(){
  vec2 uv = vUv;
  float asp = uRes.x / uRes.y;
  vec2 p = (uv - 0.5) * vec2(asp, 1.0);
  vec2 fc = (uFocus - 0.5) * vec2(asp, 1.0);
  vec2 d = p - fc;
  float dl = length(d);
  // the cloth is drawn toward whatever the veil is focused on
  p += d / (dl + 0.18) * 0.085 * exp(-dl * 2.0) * (0.7 + uPulse);
  float t = uTime * 0.032;

  vec2 a = silk(p, 7.0, t, 0.0);
  vec2 b = silk(p * 0.62 + vec2(0.3, -0.2), 4.0, t * 0.8, 11.0);

  float hueMix = 0.5 + 0.5 * sin(p.x * 2.4 + p.y * 1.7 + t * 6.0);
  vec3 cool = vec3(0.30, 0.78, 1.00);
  vec3 warm = vec3(1.00, 0.55, 0.85);
  vec3 tone = mix(mix(uAccent, cool, 0.55), warm, hueMix * 0.35);

  vec3 col = vec3(0.006, 0.007, 0.016);
  col += vec3(0.020, 0.016, 0.060) * (1.0 - dl * 0.9);                       // faint violet depth
  col += tone * (a.x * 0.50 + a.y * 0.10);
  col += mix(uAccent, cool, 0.3) * (b.x * 0.22 + b.y * 0.06);
  col += tone * exp(-dl * 3.2) * 0.07 * (0.6 + uPulse);                      // light pooling around the focus

  float md = length((uv - uMouse) * vec2(asp, 1.0));
  col += uAccent * exp(-md * 7.0) * 0.045;
  col *= mix(1.0, 0.45, uCalm);
  col *= 1.0 - dot(p, p) * 0.75;

  // sparse dust
  vec2 g = floor(uv * uRes / 3.0);
  float star = step(0.9965, hash(g)) * (0.5 + 0.5 * sin(uTime * 1.3 + hash(g + 3.0) * 40.0));
  col += star * 0.22 * vec3(0.8, 0.9, 1.0);

  col += texture(uGlyph, uv).a * uGlyphGain * vec3(0.75, 0.80, 1.0) * 0.10;
  col += uFlash;
  col += (hash(uv * uRes + uTime) - 0.5) * 0.010;
  o = vec4(max(col, 0.0), 1.0);
}`;

export const BLUR = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform vec2 uDir;
void main(){
  vec3 c = texture(uTex, vUv).rgb * 0.227027;
  c += texture(uTex, vUv + uDir * 1.3846).rgb * 0.3162162;
  c += texture(uTex, vUv - uDir * 1.3846).rgb * 0.3162162;
  c += texture(uTex, vUv + uDir * 3.2308).rgb * 0.0702703;
  c += texture(uTex, vUv - uDir * 3.2308).rgb * 0.0702703;
  o = vec4(c, 1.0);
}`;

export const COMPOSITE = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 o;
uniform vec2 uRes;
uniform sampler2D uScene;
uniform sampler2D uBlur;
uniform int uCount;
uniform vec4 uS[16];
uniform vec4 uP[16];
uniform vec3 uTint[16];
uniform float uBlend;
uniform float uDisp;
uniform float uRefr;
uniform float uFrost;
uniform float uBevel;
uniform float uTime;
uniform vec3 uFlash;
uniform vec2 uLight;

float sdRBox(vec2 p, vec2 b, float r){
  r = min(r, min(b.x, b.y));
  vec2 q = abs(p) - b + r;
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
float smin(float a, float b, float k){
  float h = max(k - abs(a - b), 0.0) / k;
  return min(a, b) - h * h * k * 0.25;
}
float map(vec2 p, out vec3 tint, out float en, out float bev){
  float d = 1e5, wsum = 0.0, e = 0.0, bv = 0.0;
  vec3 t = vec3(0.0);
  for (int i = 0; i < 16; i++){
    if (i >= uCount) break;
    float di = sdRBox(p - uS[i].xy, uS[i].zw, uP[i].x);
    float w = uP[i].z * exp(-max(di, 0.0) * 0.035) + 1e-4;
    wsum += w; t += uTint[i] * w; e += uP[i].y * w; bv += min(uS[i].z, uS[i].w) * w;
    d = (i == 0) ? di : smin(d, di, uBlend);
  }
  tint = t / wsum; en = e / wsum; bev = bv / wsum;
  return d;
}
void main(){
  vec2 frag = vec2(vUv.x, 1.0 - vUv.y) * uRes;
  vec3 base = texture(uScene, vUv).rgb;
  vec3 tint; float en; float bev;
  float d = map(frag, tint, en, bev);
  vec3 col = base;
  float outside = max(d, 0.0);
  float isOut = step(0.0, d);
  col *= 1.0 - 0.32 * exp(-outside * 0.045) * isOut;
  col += tint * en * 0.10 * exp(-outside * 0.030) * isOut;
  if (d < 1.5){
    vec3 t0; float e0; float b0;
    float dx = map(frag + vec2(1.5, 0.0), t0, e0, b0) - map(frag - vec2(1.5, 0.0), t0, e0, b0);
    float dy = map(frag + vec2(0.0, 1.5), t0, e0, b0) - map(frag - vec2(0.0, 1.5), t0, e0, b0);
    vec2 n = normalize(vec2(dx, dy) + 1e-5);
    float depth = max(-d, 0.0);
    float bevel = clamp(bev * 0.55, 6.0, uBevel);
    float edge = 1.0 - smoothstep(0.0, bevel, depth);
    float lens = edge * edge;
    vec2 off = n * lens * uRefr * bevel;
    vec2 duv = vec2(off.x, -off.y) / uRes;
    vec3 refr;
    refr.r = texture(uScene, vUv + duv * (1.0 + uDisp)).r;
    refr.g = texture(uScene, vUv + duv).g;
    refr.b = texture(uScene, vUv + duv * (1.0 - uDisp)).b;
    vec3 blur = texture(uBlur, vUv + duv * 0.5).rgb;
    vec3 g = mix(refr, blur, uFrost);
    g = mix(g, tint, 0.06 + 0.10 * en);
    g += tint * en * 0.10;
    vec2 L = normalize(uLight);
    float fres  = pow(edge, 3.0);
    float rim   = smoothstep(2.2, 0.0, depth);
    float shim  = 0.85 + 0.15 * sin(uTime * 0.6 + n.x * 3.0);
    float glare = pow(max(dot(n, L), 0.0), 5.0) * edge * shim;
    float glar2 = pow(max(dot(n, -L), 0.0), 8.0) * edge * 0.45;
    g += vec3(0.80, 0.86, 1.0) * (fres * 0.28 + rim * 0.45) + vec3(1.0) * (glare * 0.55 + glar2 * 0.35);
    g += uFlash * (0.6 + edge);
    g *= 1.0 - 0.16 * (1.0 - edge);
    float cov = 1.0 - smoothstep(-0.9, 0.9, d);
    col = mix(col, g, cov);
  }
  o = vec4(col, 1.0);
}`;
