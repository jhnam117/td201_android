/* TD-201 Manager - Android app bridge.
 * Inside the Android app there is no Web Serial, so this file provides a small
 * navigator.serial replacement that talks to the native USB-serial driver
 * (window.AndroidSerial, see MainActivity.java). In a normal browser this file
 * does nothing and the real Web Serial API is used. */
(function () {
  "use strict";
  window.__td201 = window.__td201 || {};
  if (!window.AndroidSerial || ("serial" in navigator)) return;

  var pending = {}, seq = 0, current = null, clockOffset = Infinity, lastT = 0;
  var events = new EventTarget();

  function call(fn) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      fn(id);
    });
  }

  function Port(info) {
    this._info = info;
    this._open = false;
    this._rc = null;
    this.readable = null;
    this.writable = null;
  }
  Port.prototype.getInfo = function () {
    return { usbVendorId: this._info.vid, usbProductId: this._info.pid, name: this._info.name };
  };
  Port.prototype.open = async function (opt) {
    if (this._open) throw new Error("The port is already open.");
    var self = this, baud = (opt && opt.baudRate) | 0;
    await call(function (id) { AndroidSerial.open(self._info.id, baud, id); });
    this._open = true;
    current = this;
    clockOffset = Infinity;
    this.readable = new ReadableStream({
      start: function (c) { self._rc = c; },
      cancel: function () { self._rc = null; }
    });
    this.writable = {
      getWriter: function () {
        return {
          write: async function (u8) {
            if (!self._open) throw new Error("The port is not open.");
            var s = "";
            for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
            var err = AndroidSerial.write(s);
            if (err) throw new Error(err);
          },
          close: async function () {},
          releaseLock: function () {}
        };
      }
    };
  };
  /* not in Web Serial: change the speed without closing the port */
  Port.prototype.setBaud = async function (baud) {
    var err = AndroidSerial.setBaud(baud | 0);
    if (err) throw new Error(err);
  };
  Port.prototype.close = async function () {
    if (current === this) current = null;
    this._open = false;
    try { if (this._rc) this._rc.close(); } catch (e) {}
    this._rc = null;
    this.readable = this.writable = null;
    AndroidSerial.close();
  };

  var serial = {
    requestPort: async function () {
      var info = await call(function (id) { AndroidSerial.requestPort(id); });
      return new Port(info);
    },
    getPorts: async function () { return []; },
    addEventListener: function (t, f, o) { events.addEventListener(t, f, o); },
    removeEventListener: function (t, f, o) { events.removeEventListener(t, f, o); }
  };
  Object.defineProperty(navigator, "serial", { value: serial, configurable: true });

  /* ---- called from the native side ---- */
  /* result of requestPort / open */
  window.__td201.done = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) { p.resolve(payload); return; }
    var e = new Error(payload && payload.message || "Failed");
    if (payload && payload.name) e.name = payload.name;
    p.reject(e);
  };
  /* received data: [[javaTimeMs, base64], ...]. The native receive time is
     mapped onto performance.now() so graph time stamps do not include the
     delay of handing the data over to the page. */
  window.__td201.rx = function (list) {
    var port = current;
    if (!port || !port._rc) return;
    var now = performance.now();
    for (var k = 0; k < list.length; k++) {
      var t = list[k][0], bin = atob(list[k][1]);
      var u8 = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
      if (now - t < clockOffset) clockOffset = now - t;
      var ts = (t + clockOffset) / 1000;
      if (ts < lastT) ts = lastT;
      lastT = ts;
      u8.t = ts;
      try { port._rc.enqueue(u8); } catch (e) {}
    }
  };
  /* adapter unplugged or I/O error */
  window.__td201.lost = function () {
    if (!current) return;
    var p = current;
    current = null;
    p._open = false;
    try { if (p._rc) p._rc.error(new Error("The device has been lost.")); } catch (e) {}
    events.dispatchEvent(new Event("disconnect"));
  };
})();
