/*
 * Full-screen image viewer.
 *
 * One image, fit to the screen, with pinch and double-tap zoom, panning, and a
 * swipe away to close. Every gesture is handled here rather than by the
 * WebView's own zoom, which is built for pages of text: it snaps to columns on
 * a double tap, and it stops dead at an image's edge — which is the thing this
 * viewer exists to avoid. Once zoomed in, the image may be dragged a little
 * past each edge, so a detail in a corner can be brought to the middle of the
 * screen rather than looked at pressed against its border.
 *
 * The image is sized with width and height, and moved with a translate. A
 * scale transform would be cheaper, but Chromium rasterises a scaled layer at
 * whatever resolution it had when the gesture began, and the point of zooming
 * in is to see detail the fitted size could not show.
 *
 * Native opens it with `HCV.open(url)`, and hears back through `HcViewer`:
 * `onTap` to show or hide its buttons, `onDismiss` when the image is swiped
 * away.
 */
(function () {
  'use strict';

  var img = document.getElementById('img');
  var status = document.getElementById('status');

  var SLACK = 56;        // px past each edge a zoomed image may be dragged
  var RESIST = 0.35;     // how far the image follows a finger beyond its bounds
  var DOUBLE_TAP = 2.5;  // double-tap zoom, as a multiple of the fitted size
  var DISMISS_PX = 120;  // swipe distance that closes the viewer
  var DISMISS_V = 0.8;   // ... or swipe speed, in px/ms

  var iw = 0, ih = 0;    // natural size
  var fit = 1, maxS = 1; // scale bounds; `fit` shows the whole image
  var s = 1, x = 0, y = 0, dim = 1;
  var anim = 0;

  function post(fn) {
    if (window.HcViewer && window.HcViewer[fn]) {
      try { window.HcViewer[fn](''); } catch (e) {}
    }
  }

  function vw() { return window.innerWidth; }
  function vh() { return window.innerHeight; }

  function render() {
    img.style.width = (iw * s) + 'px';
    img.style.height = (ih * s) + 'px';
    img.style.transform = 'translate(' + x + 'px,' + y + 'px)';
    document.body.style.backgroundColor = 'rgba(0,0,0,' + dim + ')';
  }

  /*
   * Where the image's leading edge may sit along one axis, as [lo, hi].
   *
   * An image smaller than the screen is centred. A larger one may be dragged
   * until SLACK px of background shows past its edge — but that slack grows in
   * from nothing as you zoom, over the first half-again of fit. At exactly fit
   * the image fills the screen along one axis, and slack there would only let
   * the whole picture wobble off-centre.
   */
  function range(size, view, sc) {
    var slack = SLACK * Math.min(1, Math.max(0, (sc / fit - 1) / 0.5));
    var lo = view - size - slack, hi = slack;
    if (lo > hi) {
      var c = (view - size) / 2;
      return [c, c];
    }
    return [lo, hi];
  }

  function clampTo(v, r) { return Math.min(r[1], Math.max(r[0], v)); }

  /** Move by `delta`, but only a fraction of it once past the bounds. */
  function rubber(pos, delta, r) {
    var next = pos + delta;
    if ((next < r[0] && delta < 0) || (next > r[1] && delta > 0)) return pos + delta * RESIST;
    return next;
  }

  function stop() {
    if (anim) cancelAnimationFrame(anim);
    anim = 0;
  }

  function animateTo(ts, tx, ty, td, done) {
    stop();
    var fs = s, fx = x, fy = y, fd = dim, t0 = null;
    function step(now) {
      if (t0 === null) t0 = now;
      var t = Math.min(1, (now - t0) / 220);
      var e = 1 - Math.pow(1 - t, 3);
      s = fs + (ts - fs) * e;
      x = fx + (tx - fx) * e;
      y = fy + (ty - fy) * e;
      dim = fd + (td - fd) * e;
      render();
      if (t < 1) anim = requestAnimationFrame(step);
      else { anim = 0; if (done) done(); }
    }
    anim = requestAnimationFrame(step);
  }

  /** Scale `ts`, keeping the image point under (px, py) where it is, then bounded. */
  function aim(ts, px, py) {
    var tx = px - (px - x) * ts / s;
    var ty = py - (py - y) * ts / s;
    return [clampTo(tx, range(iw * ts, vw(), ts)), clampTo(ty, range(ih * ts, vh(), ts))];
  }

  /** Back inside every bound, from wherever a gesture left things. */
  function settle(px, py) {
    var ts = Math.min(maxS, Math.max(fit, s));
    var t = aim(ts, px, py);
    animateTo(ts, t[0], t[1], 1);
  }

  function layout() {
    // A WebView in a dialog that has not been laid out yet measures 0x0; the
    // resize that follows once it has comes back through here.
    if (!iw || !ih || !vw() || !vh()) return;
    stop();
    fit = Math.min(vw() / iw, vh() / ih);
    // Enough to see an image's own pixels however large it is, and always a
    // good way past fit however small.
    maxS = Math.max(fit * 6, 4);
    s = fit;
    x = (vw() - iw * s) / 2;
    y = (vh() - ih * s) / 2;
    dim = 1;
    render();
    img.style.visibility = 'visible';
  }

  function fling(vx, vy) {
    stop();
    var last = null;
    function step(now) {
      if (last !== null) {
        var dt = Math.min(32, now - last);
        x += vx * dt;
        y += vy * dt;
        var decay = Math.pow(0.995, dt);
        vx *= decay;
        vy *= decay;
        // Running into an edge ends the fling on that axis; settle() brings
        // back the little it overshot, which reads as a bounce.
        var rx = range(iw * s, vw(), s), ry = range(ih * s, vh(), s);
        if (x < rx[0] || x > rx[1]) vx = 0;
        if (y < ry[0] || y > ry[1]) vy = 0;
        render();
      }
      last = now;
      if (Math.abs(vx) + Math.abs(vy) > 0.02) anim = requestAnimationFrame(step);
      else { anim = 0; settle(vw() / 2, vh() / 2); }
    }
    anim = requestAnimationFrame(step);
  }

  // --- taps ------------------------------------------------------------------
  var lastTap = null, tapTimer = 0;

  function tap(px, py) {
    var now = Date.now();
    if (lastTap && now - lastTap.t < 300 && Math.hypot(px - lastTap.x, py - lastTap.y) < 40) {
      clearTimeout(tapTimer);
      lastTap = null;
      if (s > fit * 1.05) {
        animateTo(fit, (vw() - iw * fit) / 2, (vh() - ih * fit) / 2, 1);
      } else {
        var ts = Math.min(maxS, fit * DOUBLE_TAP);
        var t = aim(ts, px, py);
        animateTo(ts, t[0], t[1], 1);
      }
      return;
    }
    // A single tap waits out the double-tap window, or the buttons would
    // flicker away and back on every double tap.
    lastTap = { x: px, y: py, t: now };
    clearTimeout(tapTimer);
    tapTimer = setTimeout(function () { lastTap = null; post('onTap'); }, 300);
  }

  // --- pointers --------------------------------------------------------------
  var pointers = Object.create(null);  // id -> {x, y}
  var mode = null;                     // 'pan' | 'pinch' | 'dismiss'
  var start = null, moved = false;
  var vx = 0, vy = 0, lastT = 0;
  var pinch = null, focus = null;

  function ids() { return Object.keys(pointers); }

  function beginPan(p) {
    mode = 'pan';
    start = { x: p.x, y: p.y };
    vx = vy = 0;
    lastT = performance.now();
  }

  function beginPinch() {
    var k = ids(), a = pointers[k[0]], b = pointers[k[1]];
    mode = 'pinch';
    moved = true;
    pinch = {
      d: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
      mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2,
      s: s, x: x, y: y
    };
    focus = { x: pinch.mx, y: pinch.my };
  }

  document.addEventListener('pointerdown', function (e) {
    if (!iw) return;
    stop();
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
    var n = ids().length;
    if (n === 1) { moved = false; beginPan(pointers[e.pointerId]); }
    else if (n === 2) beginPinch();
  });

  document.addEventListener('pointermove', function (e) {
    var p = pointers[e.pointerId];
    if (!p) return;
    var dx = e.clientX - p.x, dy = e.clientY - p.y;
    p.x = e.clientX;
    p.y = e.clientY;

    if (mode === 'pinch') {
      var k = ids();
      if (k.length < 2) return;
      var a = pointers[k[0]], b = pointers[k[1]];
      var d = Math.hypot(a.x - b.x, a.y - b.y);
      var mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      var ns = pinch.s * d / pinch.d;
      // Past either limit the pinch still answers, just reluctantly, and
      // settles back when released.
      if (ns > maxS) ns = maxS * Math.pow(ns / maxS, 0.3);
      else if (ns < fit) ns = fit * Math.pow(ns / fit, 0.3);
      s = ns;
      x = mx - (pinch.mx - pinch.x) * ns / pinch.s;
      y = my - (pinch.my - pinch.y) * ns / pinch.s;
      focus = { x: mx, y: my };
      render();
      return;
    }

    if (!moved) {
      var tx = p.x - start.x, ty = p.y - start.y;
      if (Math.hypot(tx, ty) < 8) return;
      moved = true;
      // At fit there is nothing to pan to, so a mostly vertical drag is a
      // swipe away — the gesture every gallery has taught people.
      if (s <= fit * 1.01 && Math.abs(ty) > Math.abs(tx)) mode = 'dismiss';
    }

    var now = performance.now(), dt = Math.max(1, now - lastT);
    lastT = now;
    vx = vx * 0.6 + (dx / dt) * 0.4;
    vy = vy * 0.6 + (dy / dt) * 0.4;

    if (mode === 'dismiss') {
      y += dy;
      var off = Math.abs(y - (vh() - ih * s) / 2);
      dim = Math.max(0.15, 1 - off / (vh() * 0.7));
    } else {
      x = rubber(x, dx, range(iw * s, vw(), s));
      y = rubber(y, dy, range(ih * s, vh(), s));
    }
    render();
  });

  function up(e) {
    var p = pointers[e.pointerId];
    if (!p) return;
    delete pointers[e.pointerId];
    var left = ids();

    if (mode === 'pinch') {
      // Lifting one finger of two carries on as a pan with the other, as
      // every photo viewer does; `moved` stays set so it never reads as a tap.
      if (left.length === 1) beginPan(pointers[left[0]]);
      return;
    }
    if (left.length) return;

    if (mode === 'dismiss') {
      var centre = (vh() - ih * s) / 2;
      var off = y - centre;
      if (Math.abs(off) > DISMISS_PX || Math.abs(vy) > DISMISS_V) {
        var dir = (Math.abs(vy) > DISMISS_V ? vy : off) > 0 ? 1 : -1;
        animateTo(s, x, dir > 0 ? vh() : -ih * s, 0, function () { post('onDismiss'); });
      } else {
        animateTo(s, x, centre, 1);
      }
    } else if (!moved) {
      tap(p.x, p.y);
    } else if (Math.hypot(vx, vy) > 0.3 && focus === null) {
      fling(vx, vy);
    } else {
      settle(focus ? focus.x : vw() / 2, focus ? focus.y : vh() / 2);
    }
    mode = null;
    focus = null;
  }

  document.addEventListener('pointerup', up);
  document.addEventListener('pointercancel', up);

  // Rotation or a window resize: start again from fit rather than try to
  // carry a zoom across a change of shape.
  window.addEventListener('resize', layout);

  img.addEventListener('load', function () {
    iw = img.naturalWidth;
    ih = img.naturalHeight;
    status.textContent = '';
    layout();
  });

  img.addEventListener('error', function () {
    status.textContent = 'Couldn’t load this image';
  });

  window.HCV = {
    open: function (url) {
      status.textContent = '';
      // Usually already in the WebView cache from the transcript, in which
      // case this never shows.
      var pending = setTimeout(function () {
        if (!iw) status.textContent = 'Loading…';
      }, 200);
      img.addEventListener('load', function () { clearTimeout(pending); }, { once: true });
      img.src = String(url);
    }
  };

  post('onReady');
})();
