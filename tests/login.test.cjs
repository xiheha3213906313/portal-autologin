const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(__dirname + '/../app/src/main/assets/login.js', 'utf8');
function fixture(options = {}) {
  const events = [], state = {clicks:0}, delays = [];
  class Event {constructor(type, opts) {this.type=type; Object.assign(this,opts);}}
  class Input {
    constructor(name) {this.name=name; this.disabled=false; this.saved='';}
    get value() {return this.saved;} set value(v) {this.saved=v;}
    getClientRects() {return [1];}
    focus() {events.push(this.name+':focus');}
    blur() {events.push(this.name+':blur');}
    dispatchEvent(e) {events.push(this.name+':'+e.type); return !(options.cancel && e.type==='beforeinput');}
  }
  const w={HTMLInputElement:Input, Event, InputEvent:Event,
    setTimeout:(fn,ms)=>{delays.push(ms);return setImmediate(fn);}, getComputedStyle:()=>({visibility:'visible'})};
  const f={action:options.badAction?'https://example.org/login':'https://captiveportal-login.bytedance.net:1026/login',method:'post',
    querySelectorAll:()=>options.checkbox?[{checked:false,getClientRects:()=>[1]}]:[],
    checkValidity:()=>!options.invalid};
  const u=new Input('username'), p=new Input('password'), b=new Input('button');
  u.form=p.form=b.form=f;
  b.click=()=>{state.clicks++; events.push('click');};
  const d={hidden:!!options.hidden,defaultView:w,
    location:{origin:'https://captiveportal-login.bytedance.net:1026',href:'https://captiveportal-login.bytedance.net:1026/login.html'},
    getElementById:id=>({LoginForm:f,password:p,sub2:b,accesscode:options.accesscode?new Input('accesscode'):null}[id]),querySelector:()=>u};
  const root={getElementById:()=>({contentDocument:d})};
  const ctx=vm.createContext({URL, document:root,location:{origin:options.wrongOrigin?'https://example.org':d.location.origin}});
  const script=source.replace('__CREDENTIALS__',JSON.stringify(['test-user','test-password'])).replace('__ALLOW_NEW__',options.disallow?'false':'true').replace('__FAST__',options.slow?'false':'true');
  return {run:()=>vm.runInContext(script,ctx),w,u,p,state,events,d,delays};
}
async function settle() {for(let i=0;i<100;i++) await new Promise(setImmediate);}
(async()=>{
  const a=fixture(); assert.equal(a.run(),'typing'); assert.equal(a.run(),'typing');
  await settle();
  assert.equal(a.w.__localPortalState,'clicked'); assert.equal(a.state.clicks,1);
  assert.equal(a.u.value,'test-user'); assert.equal(a.p.value,'test-password');
  assert.equal(a.run(),'clicked'); assert.equal(a.state.clicks,1);
  assert(a.events.indexOf('password:blur')<a.events.indexOf('click'));
  assert.equal(a.events.filter(e=>e==='username:beforeinput').length,9);
  assert.equal(a.delays.reduce((x,y)=>x+y,0),476);
  const slow=fixture({slow:true}); slow.run(); await settle();
  assert.equal(slow.state.clicks,1); assert.equal(slow.delays.reduce((x,y)=>x+y,0),3060);
  for(const [options,status] of [[{wrongOrigin:true},'unrelated'],[{badAction:true},'form_changed'],
    [{checkbox:true},'manual_checkbox'],[{accesscode:true},'manual_accesscode'],[{hidden:true},'waiting'],[{disallow:true},'cooldown']]) {
    const x=fixture(options); assert.equal(x.run(),status); await settle(); assert.equal(x.state.clicks,0);
  }
  for(const [options,status] of [[{invalid:true},'manual_validation'],[{cancel:true},'input_cancelled']]) {
    const x=fixture(options); x.run(); await settle(); assert.equal(x.w.__localPortalState,status); assert.equal(x.state.clicks,0);
  }
  const interrupted=fixture(); interrupted.run(); interrupted.d.hidden=true; await settle();
  assert.equal(interrupted.w.__localPortalState,'paused'); assert.equal(interrupted.state.clicks,0);
  console.log('PASS: gradual input, blur before click, iframe, once per document, cooldown gate, origin/action restrictions, checkbox/access-code/validation/cancel/background stops');
})().catch(e=>{console.error(e);process.exit(1)});
