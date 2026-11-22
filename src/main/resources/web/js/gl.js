import { VERT, SCENE, BLUR, COMPOSITE } from './shaders.js';

const MAX = 16;

function compile(gl, type, src) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) || 'shader compile failed');
  return s;
}
function program(gl, frag) {
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl, gl.VERTEX_SHADER, VERT));
  gl.attachShader(p, compile(gl, gl.FRAGMENT_SHADER, frag));
  gl.bindAttribLocation(p, 0, 'aPos');
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) || 'link failed');
  const u = {};
  const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < n; i++) {
    const info = gl.getActiveUniform(p, i);
    const name = info.name.replace(/\[0\]$/, '');
    u[name] = gl.getUniformLocation(p, info.name);
  }
  return { p, u };
}

class GLRenderer {
  constructor(canvas, gl) {
    this.kind = 'gl';
    this.canvas = canvas;
    this.gl = gl;
    this.scale = 0.55;
    this.lost = false;
    canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); this.lost = true; });
    canvas.addEventListener('webglcontextrestored', () => { this.init(); this.lost = false; });
    this.init();
  }

  init() {
    const gl = this.gl;
    this.progs = { scene: program(gl, SCENE), blur: program(gl, BLUR), comp: program(gl, COMPOSITE) };
    this.vao = gl.createVertexArray();
    gl.bindVertexArray(this.vao);
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    this.glyphTex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.glyphTex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(4));
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    this.sArr = new Float32Array(MAX * 4);
    this.pArr = new Float32Array(MAX * 4);
    this.tArr = new Float32Array(MAX * 3);
    this.targets = null;
    this.resize();
  }

  target(w, h) {
    const gl = this.gl;
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { tex, fbo, w, h };
  }

  resize() {
    const gl = this.gl;
    const dpr = Math.min(window.devicePixelRatio || 1, 1.5);
    this.cssW = window.innerWidth; this.cssH = window.innerHeight;
    this.canvas.width = Math.round(this.cssW * dpr);
    this.canvas.height = Math.round(this.cssH * dpr);
    if (this.targets) for (const t of Object.values(this.targets)) { gl.deleteTexture(t.tex); gl.deleteFramebuffer(t.fbo); }
    const sw = Math.max(2, Math.round(this.cssW * this.scale));
    const sh = Math.max(2, Math.round(this.cssH * this.scale));
    this.targets = {
      scene: this.target(sw, sh),
      a: this.target(Math.max(2, sw >> 1), Math.max(2, sh >> 1)),
      b: this.target(Math.max(2, sw >> 1), Math.max(2, sh >> 1)),
    };
  }

  setQuality(q) { this.scale = q === 'low' ? 0.36 : 0.55; this.resize(); }

  setGlyphs(cv) {
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.glyphTex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, cv);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
  }

  pass(prog, tgt, bind) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, tgt ? tgt.fbo : null);
    gl.viewport(0, 0, tgt ? tgt.w : this.canvas.width, tgt ? tgt.h : this.canvas.height);
    gl.useProgram(prog.p);
    bind(prog.u);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  tex(unit, t, loc) {
    const gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.uniform1i(loc, unit);
  }

  draw(f) {
    if (this.lost) return;
    const gl = this.gl, T = this.targets, P = this.progs;
    gl.bindVertexArray(this.vao);

    this.pass(P.scene, T.scene, (u) => {
      gl.uniform2f(u.uRes, T.scene.w, T.scene.h);
      gl.uniform1f(u.uTime, f.time);
      gl.uniform2f(u.uMouse, f.mouse[0], f.mouse[1]);
      gl.uniform2f(u.uFocus, f.focus[0], f.focus[1]);
      gl.uniform1f(u.uPulse, f.pulse);
      gl.uniform1f(u.uCalm, f.calm);
      gl.uniform3fv(u.uAccent, f.accent);
      gl.uniform1f(u.uGlyphGain, f.glyphGain);
      gl.uniform3fv(u.uFlash, f.flash);
      this.tex(0, this.glyphTex, u.uGlyph);
    });

    const blur = (src, dst, dx, dy) => this.pass(P.blur, dst, (u) => {
      gl.uniform2f(u.uDir, dx / src.w, dy / src.h);
      this.tex(0, src.tex, u.uTex);
    });
    blur(T.scene, T.a, 1.6, 0); blur(T.a, T.b, 0, 1.6);
    blur(T.b, T.a, 2.4, 0);     blur(T.a, T.b, 0, 2.4);

    const n = Math.min(MAX, f.shapes.length);
    for (let i = 0; i < n; i++) {
      const s = f.shapes[i];
      this.sArr.set([s.x, s.y, s.hw, s.hh], i * 4);
      this.pArr.set([s.r, s.energy, s.alpha, 0], i * 4);
      this.tArr.set(s.tint, i * 3);
    }
    this.pass(P.comp, null, (u) => {
      gl.uniform2f(u.uRes, this.cssW, this.cssH);
      gl.uniform1i(u.uCount, n);
      gl.uniform4fv(u.uS, this.sArr);
      gl.uniform4fv(u.uP, this.pArr);
      gl.uniform3fv(u.uTint, this.tArr);
      gl.uniform1f(u.uBlend, f.blend);
      gl.uniform1f(u.uDisp, f.disp);
      gl.uniform1f(u.uRefr, f.refr);
      gl.uniform1f(u.uFrost, f.frost);
      gl.uniform1f(u.uBevel, f.bevel);
      gl.uniform1f(u.uTime, f.time);
      gl.uniform3fv(u.uFlash, f.flash);
      gl.uniform2f(u.uLight, -0.55, -0.83);
      this.tex(0, T.scene.tex, u.uScene);
      this.tex(1, T.b.tex, u.uBlur);
    });
  }
}

// No WebGL2? Same interface, drawn with CSS glass (backdrop-filter) so the app stays usable.
class CssRenderer {
  constructor(canvas) {
    this.kind = 'css';
    canvas.style.display = 'none';
    document.body.classList.add('nogl');
    this.layer = document.createElement('div');
    this.layer.className = 'fallback-layer';
    document.body.prepend(this.layer);
    this.nodes = [];
  }
  resize() {}
  setQuality() {}
  setGlyphs() {}
  draw(f) {
    document.body.style.setProperty('--accent-rgb', f.accent.map((v) => Math.round(v * 255)).join(','));
    while (this.nodes.length < f.shapes.length) {
      const n = document.createElement('div');
      n.className = 'fshape';
      this.layer.appendChild(n);
      this.nodes.push(n);
    }
    this.nodes.forEach((n, i) => {
      const s = f.shapes[i];
      if (!s) { n.style.display = 'none'; return; }
      n.style.display = 'block';
      n.style.width = s.hw * 2 + 'px'; n.style.height = s.hh * 2 + 'px';
      n.style.borderRadius = Math.min(s.r, s.hw, s.hh) + 'px';
      n.style.transform = `translate(${s.x - s.hw}px, ${s.y - s.hh}px)`;
      n.style.background = `rgba(${s.tint.map((v) => Math.round(v * 255)).join(',')},${0.05 + s.energy * 0.12})`;
    });
  }
}

export function createRenderer(canvas) {
  try {
    const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, powerPreference: 'high-performance' });
    if (!gl) throw new Error('no webgl2');
    return new GLRenderer(canvas, gl);
  } catch (e) {
    console.warn('[veil] WebGL2 unavailable, using CSS glass fallback:', e.message);
    return new CssRenderer(canvas);
  }
}
