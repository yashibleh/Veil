// Tiny damped-spring helper. zeta < 1 gives the slight overshoot that makes glass feel liquid.
export class Spring {
  constructor(value = 0, stiffness = 170, zeta = 0.62) {
    this.v = value; this.t = value; this.vel = 0; this.k = stiffness; this.zeta = zeta;
  }
  to(t) { this.t = t; return this; }
  snap(v) { this.v = this.t = (v === undefined ? this.t : v); this.vel = 0; return this; }
  kick(impulse) { this.vel += impulse; return this; }
  step(dt) {
    const c = 2 * Math.sqrt(this.k) * this.zeta;
    const n = Math.max(1, Math.ceil(dt / (1 / 120)));
    const h = dt / n;
    for (let i = 0; i < n; i++) {
      this.vel += ((this.t - this.v) * this.k - this.vel * c) * h;
      this.v += this.vel * h;
    }
  }
  get settled() { return Math.abs(this.t - this.v) < 0.05 && Math.abs(this.vel) < 0.05; }
}
