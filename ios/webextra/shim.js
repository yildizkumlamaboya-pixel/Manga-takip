// iOS köprüsü: Android'deki window.MangaApp ile aynı arayüz.
// Veriler uygulama açılırken yerel taraftan (Swift) verilir; okumalar anında buradan yapılır,
// yazmalar hem burada tutulur hem de Swift'e gönderilip dosyaya kaydedilir.
(function () {
  var D = window.__mtInit || {};
  D.files = D.files || {}; D.settings = D.settings || {}; D.pos = D.pos || {}; D.ls = D.ls || {};
  function post(cmd, o) { o = o || {}; o.cmd = cmd; try { window.webkit.messageHandlers.mt.postMessage(o); } catch (e) {} }
  window.__mtSet = function (k, v) { if (k.indexOf('files.') === 0) D.files[k.slice(6)] = v; else D[k] = v; };

  // localStorage: uygulama içinde güvenilir dursun diye Swift tarafında saklanır
  var ls = D.ls;
  var store = {
    getItem: function (k) { return Object.prototype.hasOwnProperty.call(ls, k) ? ls[k] : null; },
    setItem: function (k, v) { ls[k] = String(v); post('ls', { k: k, v: String(v) }); },
    removeItem: function (k) { delete ls[k]; post('ls', { k: k, v: null }); },
    clear: function () { ls = {}; post('lsClear'); },
    key: function (i) { return Object.keys(ls)[i] || null; },
    get length() { return Object.keys(ls).length; }
  };
  try { Object.defineProperty(window, 'localStorage', { configurable: true, get: function () { return store; } }); } catch (e) {}

  window.MangaApp = {
    isApp: function () { return true; },
    isLocal: function () { return true; },
    isIOS: function () { return true; },
    read: function (n) { var v = D.files[n]; return v == null ? null : v; },
    write: function (n, t) { D.files[n] = t; post('write', { n: n, t: t }); return ''; },
    checkNow: function () { D.checking = true; post('check', { only: false }); },
    checkNew: function () { D.checking = true; post('check', { only: true }); },
    isChecking: function () { return !!D.checking; },
    openReader: function (u, t, c, n) { post('openReader', { u: u, t: t, c: c, n: n }); },
    openSite: function (u, t) { post('openSite', { u: u, t: t }); },
    lastCheck: function () { return D.lastCheck || '|'; },
    getPosition: function (u) { return D.pos[u] || ''; },
    exportBackup: function () { post('export'); },
    importBackup: function () { post('import'); },
    importGithub: function (r) { post('importGithub', { r: r }); },
    share: function (t) { post('share', { t: t }); },
    clearNotifications: function () { post('clearNotifs'); },
    clearNotification: function (u) { post('clearNotif', { u: u }); },
    getSetting: function (k) { var v = D.settings[k]; return v == null ? '' : String(v); },
    setSetting: function (k, v) { D.settings[k] = String(v); post('setSetting', { k: k, v: String(v) }); }
  };
})();
