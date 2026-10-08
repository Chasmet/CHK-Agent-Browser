const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const root=process.argv[2];

const script=fs.readFileSync(root+'/type.js','utf8');
let nativeValue='',events=[];
class Field {set value(value){nativeValue=value;} get value(){return nativeValue;}}
const el=new Field();
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

const Event=class {constructor(type){this.type=type;}};
const selectEvents=[];
const options=[{value:'a',textContent:'A',selected:false},{value:'b',textContent:'B',selected:false}];
const select={tagName:'SELECT',options,value:'a',dispatchEvent:e=>selectEvents.push(e.type)};
const selectDoc={querySelector:s=>s==='#select'?select:null};
assert.equal(vm.runInNewContext(fs.readFileSync(root+'/select.js','utf8'),{document:selectDoc,Event}),'Option sélectionnée : B');
assert.equal(select.value,'b');assert.equal(options[1].selected,true);assert.deepEqual(selectEvents,['input','change']);

const check={tagName:'INPUT',type:'checkbox',checked:false,getAttribute:()=>null,click(){this.checked=!this.checked;}};
assert.equal(vm.runInNewContext(fs.readFileSync(root+'/check.js','utf8'),{document:{querySelector:s=>s==='#check'?check:null}}),'État confirmé');
assert.equal(check.checked,true);

const fileEl={files:[{name:'logo.png',size:1234},{name:'banner.jpg',size:5678}]};
const fileInfo=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/file.js','utf8'),{document:{querySelector:s=>s==='#file'?fileEl:null}}));
assert.equal(fileInfo.ok,true);assert.equal(fileInfo.count,2);assert.deepEqual(Array.from(fileInfo.names),['logo.png','banner.jpg']);

assert.equal(vm.runInNewContext(fs.readFileSync(root+'/exists.js','utf8'),{document:{querySelector:s=>s==='#target'?{}:null}}),true);

const status=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/status.js','utf8'),{
    document:{body:{innerText:'Saved successfully\nNo error'},readyState:'complete',title:'Test',forms:{length:1},querySelectorAll:()=>[]},
    location:{href:'https://example.com'}
}));
assert.equal(status.readyState,'complete');assert.equal(status.forms,1);assert.ok(status.messages.length>=1);
console.log('PASS: retry bounds, native forms, dynamic controls, upload confirmation helpers and bounded page extraction');
