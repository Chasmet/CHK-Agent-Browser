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
const page=JSON.parse(vm.runInNewContext(read,{document:{body:{innerText:'Page'},querySelectorAll:()=>nodes,querySelector:()=>nodes[2]}}));
assert.equal(page.text,'Page');assert.equal(page.elements.length,1);
assert.equal(page.elements[0].selector,'input:nth-of-type(1)');
const many=Array.from({length:100},()=>makeNode('text','x'.repeat(500)));
const bounded=vm.runInNewContext(read,{document:{body:{innerText:'a'.repeat(20000)},querySelectorAll:()=>many,querySelector:()=>many[0]}});
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
const condition=fs.readFileSync(root+'/condition.js','utf8');
const conditionPage={body:{innerText:'Saving...'},querySelector:()=>null,querySelectorAll:()=>[]};
const location={href:'https://example.com/working'};
assert.equal(vm.runInNewContext(condition,{document:conditionPage,location}),'WAIT');
conditionPage.querySelector=s=>s==='#done'?{}:null;
conditionPage.body.innerText='Saved successfully';
location.href='https://example.com/done';
assert.equal(vm.runInNewContext(condition,{document:conditionPage,location}),'DONE');
conditionPage.body.innerText='Saving again';
assert.equal(vm.runInNewContext(condition,{document:conditionPage,location}),'WAIT');


/** Modern upload controls are often outside HTML forms (Google Play Console). */
function uploadFixture(labels) {
  const doc={};
  const win={getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
  doc.defaultView=win;
  const buttons=labels.map((text,i)=>({
    nodeType:1,tagName:'BUTTON',type:'button',innerText:text,ownerDocument:doc,
    id:'',getAttribute:()=>null,getClientRects:()=>[{}],hasAttribute:()=>false,
    getBoundingClientRect:()=>({left:10+i*150,top:40,width:140,height:44}),
    scrollIntoView(){},contains:()=>false,closest:()=>null
  }));
  const scope={parentElement:null,querySelectorAll:()=>buttons};
  const input={
    nodeType:1,tagName:'INPUT',type:'file',disabled:false,multiple:false,
    ownerDocument:doc,parentElement:scope,labels:[],getAttribute:()=>null,
    getBoundingClientRect:()=>({left:0,top:0,width:0,height:0}),
    getClientRects:()=>[],closest:()=>null,getRootNode:()=>doc,
    id:'media',hasAttribute:()=>false,addEventListener(type,callback){this.changeListener=callback;}
  };
  doc.fileListeners={};
  doc.addEventListener=(type,fn,capture)=>{assert.equal(capture,true);doc.fileListeners[type]=fn;};
  doc.removeEventListener=(type,fn,capture)=>{if(doc.fileListeners[type]===fn)delete doc.fileListeners[type];};
  doc.querySelector=s=>s==='#media'?input:null;
  doc.querySelectorAll=s=>s==='*'?[input,...buttons]:
    s==='form'?[]:[input,...buttons];
  return {document:doc,window:win,innerWidth:430,innerHeight:800,CSS:{escape:s=>s},buttons,input};
}
const nativeTarget=fs.readFileSync(root+'/native-target.js','utf8');
let upload=JSON.parse(vm.runInNewContext(nativeTarget,uploadFixture(['Upload APK'])));
assert.equal(upload.ok,true);
assert.equal(upload.multiple,false);
assert.ok(upload.x>0&&upload.x<430);
assert.equal(upload.method,'button');
upload=JSON.parse(vm.runInNewContext(nativeTarget,uploadFixture(['Importer un fichier'])));
assert.equal(upload.ok,true);
upload=JSON.parse(vm.runInNewContext(nativeTarget,uploadFixture(['Upload','Upload'])));
assert.equal(upload.ok,false);assert.match(upload.error,/Plusieurs/);
upload=JSON.parse(vm.runInNewContext(nativeTarget,uploadFixture(['Publier'])));
assert.equal(upload.ok,false);assert.match(upload.error,/introuvable/);
const dynamicForm=JSON.parse(vm.runInNewContext(
  fs.readFileSync(root+'/form-dynamic.js','utf8'),uploadFixture(['Upload APK'])));
assert.equal(dynamicForm.count,0);
assert.ok(dynamicForm.dynamic_controls.some(x=>x.label==='Upload APK'));
assert.ok(dynamicForm.dynamic_controls.some(x=>x.type==='file'));

// Reactive submit arrows must not be clicked while unavailable.
const clickState=fs.readFileSync(root+'/click-state.js','utf8');
const semanticClick=fs.readFileSync(root+'/semantic-click.js','utf8');
let semanticClicks=0;
const sendButton={tagName:'BUTTON',getAttribute:k=>k==='aria-label'?'Envoyer':null,
    getClientRects:()=>[{}],scrollIntoView(){},click(){semanticClicks++;}};
const semanticDocument={querySelectorAll:()=>[sendButton],querySelector:()=>null};
assert.equal(vm.runInNewContext(semanticClick,{document:semanticDocument}),'Clic effectué');
assert.equal(semanticClicks,1);
semanticDocument.querySelectorAll=()=>[sendButton,{...sendButton}];
assert.match(vm.runInNewContext(semanticClick,{document:semanticDocument}),/Libellé ambigu/);
assert.equal(semanticClicks,1);
// YouTube Studio uses role=menuitem (not a button). A stable semantic label
// must target exactly the observed menu item, never an unrelated item.
const menuDoc={};
let uploadsOpened=0;
const uploadMenu={tagName:'DIV',nodeType:1,innerText:'Importer des vidéos',disabled:false,
  getAttribute:k=>k==='role'?'menuitem':null,
  getClientRects:()=>[{}],scrollIntoView(){},click(){uploadsOpened++;}};
const otherMenu={...uploadMenu,innerText:'Créer un post',click(){throw Error('wrong menu item');}};
menuDoc.querySelectorAll=()=>[uploadMenu,otherMenu];menuDoc.querySelector=()=>null;
const clickUpload=fs.readFileSync(root+'/youtube-menu-click.js','utf8');
assert.equal(vm.runInNewContext(clickUpload,{document:menuDoc}),'Clic effectué');
assert.equal(uploadsOpened,1);
menuDoc.querySelectorAll=()=>[uploadMenu,{...uploadMenu}];
assert.match(vm.runInNewContext(clickUpload,{document:menuDoc}),/Libellé ambigu/);
assert.equal(uploadsOpened,1);
// Suno / ProseMirror: paragraphs carry visible line breaks that textContent omits.
const richDoc={},richSelection={removeAllRanges(){},addRange(){}};
const richField={nodeType:1,tagName:'DIV',isContentEditable:true,disabled:false,readOnly:false,
  innerText:'',textContent:'',getAttribute:k=>k==='aria-label'?'Lyrics editor':null,
  getClientRects:()=>[{}],scrollIntoView(){},focus(){},dispatchEvent(){},
  ownerDocument:richDoc};
richDoc.querySelectorAll=()=>[richField];richDoc.querySelector=()=>null;
richDoc.createRange=()=>({selectNodeContents(){}});
richDoc.getSelection=()=>richSelection;
richDoc.execCommand=(operation,unused,txt)=>{
  richField.textContent=txt.replace(/\n/g,'');richField.innerText=txt;return true;
};
const richScript=fs.readFileSync(root+'/rich-lyrics-type.js','utf8');
assert.equal(vm.runInNewContext(richScript,{document:richDoc,Event}), 'Saisie effectuée');
richDoc.execCommand=()=>{richField.textContent='ancien';richField.innerText='ancien';return true;};
assert.equal(vm.runInNewContext(richScript,{document:richDoc,Event}), 'Saisie riche non confirmée');
const submit={disabled:true,getAttribute:()=>null,closest:()=>null};
const clickDoc={querySelector:s=>s==='#submit'?submit:null,querySelectorAll:()=>[]};
assert.equal(vm.runInNewContext(clickState,{document:clickDoc}),'WAIT');
submit.disabled=false;
assert.equal(vm.runInNewContext(clickState,{document:clickDoc}),'READY');
submit.getAttribute=key=>key==='aria-disabled'?'true':null;
assert.equal(vm.runInNewContext(clickState,{document:clickDoc}),'WAIT');
assert.equal(vm.runInNewContext(clickState,{document:{querySelector:()=>null,querySelectorAll:()=>[]}}),'MISSING');
console.log('PASS: reactive button readiness before single verified click');

console.log('PASS: retry bounds, native forms, dynamic controls, upload confirmation helpers and bounded page extraction and verified-click postconditions');

// A website may clear/remove its file input immediately after consuming change.
const receiptContext=uploadFixture(['Upload APK']);
vm.runInNewContext(nativeTarget,receiptContext);
receiptContext.input.files=[{name:'fixture.mp4',size:35651584}];
receiptContext.document.fileListeners.change({target:receiptContext.input});receiptContext.input.files=[];
const receipt=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/file-receipt.js','utf8'),receiptContext));
assert.equal(receipt.event_observed,true);assert.equal(receipt.count,1);assert.equal(receipt.sizes[0],35651584);
const field={tagName:'INPUT',type:'text',value:'old',isConnected:true,getAttribute:()=>null,dispatchEvent(){}};
const formContext={document:{querySelector:()=>field,querySelectorAll:()=>[]},Event};
assert.equal(JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/form-verify.js','utf8'),formContext)).ok,false);
assert.equal(JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/form-fill.js','utf8'),formContext)).ok,true);
assert.equal(field.value,'verified');
assert.equal(JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/form-verify.js','utf8'),formContext)).verified,true);
field.type='password';
assert.equal(JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/form-fill.js','utf8'),formContext)).ok,false);
console.log('PASS: form readback, sensitive-field rejection and file-change receipt survives input reset');


// Regression: Suno-style voice picker scrolls inside its own modal, never the page.
const rect=()=>({left:25,right:680,top:180,bottom:850,width:655,height:670});
const cards=[{innerText:'Paname offi',getBoundingClientRect:()=>rect()}];
const innerList={scrollHeight:1600,clientHeight:350,scrollTop:0,getBoundingClientRect:()=>rect(),
  querySelectorAll:(selector)=>selector==='*'?[]:selector.includes('article')?cards:[],
  getAttribute:()=>null,hasAttribute:()=>false};
const modal={scrollHeight:700,clientHeight:700,scrollTop:0,getBoundingClientRect:()=>rect(),
  querySelectorAll:(selector)=>selector==='*'?[innerList]:[],
  contains:element=>element===innerList,getAttribute:()=>null,hasAttribute:()=>false};
const backgroundPage={scrollHeight:2300,clientHeight:700,scrollTop:0,getBoundingClientRect:()=>rect()};
const scrollDoc={querySelector:(selector)=>selector.includes('dialog')?modal:null,
  querySelectorAll:selector=>selector.includes('[role=dialog]')?[modal]:[],scrollingElement:backgroundPage,documentElement:backgroundPage};
modal.ownerDocument={defaultView:{innerWidth:900,innerHeight:1400,getComputedStyle:()=>({display:'block',visibility:'visible'})}};modal.closest=()=>null;modal.contains=e=>e===innerList||e===modal;
const scrollContext={document:scrollDoc,innerWidth:900,innerHeight:1400,
  getComputedStyle:e=>({display:'block',visibility:'visible',overflowY:e===innerList?'auto':'visible'})};
const scrollOutcome=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/scroll-list.js','utf8'),scrollContext));
assert.equal(scrollOutcome.ok,true,JSON.stringify(scrollOutcome));
assert.equal(scrollOutcome.moved,true);
assert.equal(innerList.scrollTop,420);
assert.equal(backgroundPage.scrollTop,0);
const matched=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/scroll-until.js','utf8'),scrollContext));
assert.equal(matched.found,true,JSON.stringify(matched));
assert.equal(matched.moved,false);
assert.equal(innerList.scrollTop,420);
console.log('PASS: nested voice picker moved 420 px, page unchanged, target visible without redundant scroll');

const horizontal={nodeType:1,scrollWidth:1200,clientWidth:180,scrollLeft:0,scrollHeight:80,clientHeight:80,
 getBoundingClientRect:()=>({left:0,right:180,top:0,bottom:80,width:180,height:80}),getAttribute:()=>null,hasAttribute:()=>false,
 querySelectorAll:()=>[],contains:e=>e===horizontal};
const horizontalDoc={body:{},querySelector:s=>s==='#horizontal'?horizontal:null,querySelectorAll:s=>s==='*'?[horizontal]:[]};
const horizontalContext={document:horizontalDoc,innerWidth:400,innerHeight:800,getComputedStyle:()=>({display:'block',visibility:'visible',overflowX:'auto',overflowY:'hidden'})};
let h=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/scroll-right.js','utf8'),horizontalContext));
assert.equal(h.axis,'horizontal');assert.equal(h.scroll_after,200);assert.equal(horizontal.scrollLeft,200);
h=JSON.parse(vm.runInNewContext(fs.readFileSync(root+'/scroll-left.js','utf8'),horizontalContext));assert.equal(h.scroll_after,0);
console.log('PASS: measured horizontal scroll and reverse without vertical movement');
