const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const root=process.argv[2];
const script=fs.readFileSync(root+'/type.js','utf8');
let nativeValue='',events=[];
class Field {set value(value){nativeValue=value;} get value(){return nativeValue;}}
const el=new Field();
// React-like own setter must not intercept the native value update.
Object.defineProperty(el,'value',{set(){throw Error('wrong setter');},get(){return nativeValue;}});
el.focus=()=>{};el.dispatchEvent=e=>events.push(e.type);
const context={document:{querySelector:()=>el},Event:class {constructor(type){this.type=type;}}};
assert.equal(vm.runInNewContext(script,context),'Saisie effectuée');
assert.equal(nativeValue,'hello\nworld');assert.deepEqual(events,['input','change']);
el.type='password';events=[];
assert.equal(vm.runInNewContext(script,context),'Champ sensible bloqué');assert.equal(events.length,0);
el.type='text';el.readOnly=true;
assert.equal(vm.runInNewContext(script,context),'Champ non modifiable');
const makeNode=(type,label)=>({tagName:'INPUT',nodeType:1,type,name:label,placeholder:'',innerText:'',
    getClientRects:()=>[{}],getAttribute:()=>null,parentElement:null,previousElementSibling:null});
const read=fs.readFileSync(root+'/read.js','utf8');
const nodes=[makeNode('password','secret'),makeNode('hidden','token'),makeNode('text','title')];
const page=JSON.parse(vm.runInNewContext(read,{document:{body:{innerText:'Page'},querySelectorAll:()=>nodes}}));
assert.equal(page.text,'Page');assert.equal(page.elements.length,1);
assert.equal(page.elements[0].selector,'input:nth-of-type(1)');
const many=Array.from({length:100},()=>makeNode('text','x'.repeat(500)));
const bounded=vm.runInNewContext(read,{document:{body:{innerText:'a'.repeat(20000)},querySelectorAll:()=>many}});
assert.ok(bounded.length<9500);assert.equal(JSON.parse(bounded).text.length,5000);
console.log('PASS: network retry bounds, native form setter, blocked fields, page selectors and result size');
