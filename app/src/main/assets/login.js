(function () {
  'use strict';
  const origin = 'https://captiveportal-login.bytedance.net:1026';
  if (location.origin !== origin) return 'unrelated';
  const frame = document.getElementById('leftFrame');
  const d = frame ? frame.contentDocument : document;
  if (!d || d.location.origin !== origin) return 'waiting';
  const w = d.defaultView;
  if (w.__localPortalState) return w.__localPortalState;
  if (!__ALLOW_NEW__) return 'cooldown';
  if (d.hidden) return 'waiting';
  const f = d.getElementById('LoginForm');
  const u = d.querySelector('input[name="username"]');
  const p = d.getElementById('password');
  const b = d.getElementById('sub2');
  if (!f || !u || !p || !b) return 'waiting';
  const target = new URL(f.action, d.location.href);
  if (target.origin !== origin || target.pathname !== '/login' ||
      f.method.toLowerCase() !== 'post' || u.form !== f || p.form !== f || b.form !== f)
    return 'form_changed';
  const visible = e => e.getClientRects().length > 0 &&
    w.getComputedStyle(e).visibility !== 'hidden';
  if (!visible(u) || !visible(p) || !visible(b) || b.disabled) return 'waiting';
  if (Array.from(f.querySelectorAll('input[type="checkbox"]')).some(e => visible(e) && !e.checked))
    return 'manual_checkbox';
  const code = d.getElementById('accesscode');
  if (code && visible(code) && !code.disabled && !code.value) return 'manual_accesscode';
  const credentials = __CREDENTIALS__;
  const pace = __FAST__ ? [30, 8, 60, 120] : [150, 80, 250, 500];
  w.__localPortalState = 'typing';
  const pause = ms => new Promise(resolve => w.setTimeout(resolve, ms));
  const setter = Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype, 'value').set;
  (async function () {
    for (const [el, value] of [[u,credentials[0]],[p,credentials[1]]]) {
      if (d.hidden) { w.__localPortalState = 'paused'; return; }
      el.focus();
      await pause(pace[0]);
      setter.call(el, '');
      el.dispatchEvent(new w.InputEvent('input', {bubbles:true, inputType:'deleteContentBackward', data:null}));
      for (const ch of value) {
        if (d.hidden) { w.__localPortalState = 'paused'; return; }
        if (!el.dispatchEvent(new w.InputEvent('beforeinput', {
          bubbles:true, cancelable:true, inputType:'insertText', data:ch
        }))) { w.__localPortalState = 'input_cancelled'; return; }
        setter.call(el, el.value + ch);
        el.dispatchEvent(new w.InputEvent('input', {bubbles:true, inputType:'insertText', data:ch}));
        await pause(pace[1]);
      }
      el.dispatchEvent(new w.Event('change', {bubbles:true}));
      el.blur();
      await pause(pace[2]);
    }
    await pause(pace[3]);
    if (d.hidden) { w.__localPortalState = 'paused'; return; }
    if (!f.checkValidity()) { w.__localPortalState = 'manual_validation'; return; }
    w.__localPortalState = 'clicked';
    b.click();
  })().catch(() => { w.__localPortalState = 'script_error'; })
      .finally(() => { credentials[0] = ''; credentials[1] = ''; });
  return 'typing';
})()
