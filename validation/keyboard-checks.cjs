const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync(process.env.CHK_KEYBOARD_SOURCE||'app/src/main/assets/agent_keyboard.js','utf8');
function editorFixture({unsupported=false,reject=false}={}){
  const events=[],calls=[],editor={nodeType:1,tagName:'DIV',isConnected:true,isContentEditable:true,
    textContent:'ancien #tag',innerText:'ancien #tag',disabled:false,readOnly:false,
    getClientRects:()=>[{}],getAttribute:k=>k==='aria-label'?'Légende':null,
    focus(){doc.activeElement=this;},scrollIntoView(){},
    addEventListener(type,fn){this.listener=fn;},removeEventListener(){this.listener=null;},
    dispatchEvent(e){events.push(e.type+(e.isTrusted?':native':':synthetic'));
      // Reactive hashtag editors may consume a synthetic beforeinput themselves.
      if(e.type==='beforeinput'&&!e.isTrusted){this.textContent+=e.data||'';this.innerText=this.textContent;}
      if(e.type==='input'&&this.listener)this.listener(e);return true;},
    contains:()=>true};
  let range={cloneRange(){return this;},selectNodeContents(){},setStart(){},setEnd(){},toString(){return '';}};
  const selection={rangeCount:0,removeAllRanges(){this.rangeCount=0;},addRange(r){range=r;this.rangeCount=1;},getRangeAt:()=>range};
  const doc={activeElement:null,defaultView:null,querySelectorAll:()=>[editor],querySelector:()=>editor,
    createRange:()=>({...range}),getSelection:()=>selection,
    createTreeWalker(){let used=false;return {nextNode(){if(used)return null;used=true;return {length:editor.textContent.length};}};},
    execCommand(command,unused,text){calls.push(command);if(unsupported)return false;
      if(reject)return true;
      editor.textContent=command==='delete'?'':text;editor.innerText=editor.textContent;
      editor.dispatchEvent({type:'input',isTrusted:true});return true;}};
  class Event {constructor(type,options={}){this.type=type;Object.assign(this,options);this.isTrusted=false;}}
  doc.defaultView={Event,InputEvent:Event};editor.ownerDocument=doc;
  const context={window:{},document:doc,location:{href:'https://fixture.example/',hostname:'fixture.example'},Event,TextEncoder};
  vm.runInNewContext(source,context);
  const run=p=>JSON.parse(context.window.__chkAgentKeyboard.run(p));
  const inspected=run({operation:'inspect'}),control=inspected.controls[0];
  const input=(operation,text)=>run({operation,selector:control.selector,expected_context:inspected.context,text});
  return {input,run,editor,events,calls,context:inspected.context};
}
let f=editorFixture(),result=f.input('replace','CHK #Brume');
assert.equal(result.ok,true,JSON.stringify(result));assert.equal(f.editor.textContent,'CHK #Brume');
assert.deepEqual(f.events,['input:native']);assert.deepEqual(f.calls,['insertText']);
assert.equal(f.run({operation:'verify',expected_context:f.context,receipt:result.receipt}).ok,true);
result=f.input('clear');assert.equal(result.ok,true,JSON.stringify(result));assert.equal(f.editor.textContent,'');
assert.deepEqual(f.calls,['insertText','delete']);assert.deepEqual(f.events,['input:native','input:native']);
f=editorFixture({unsupported:true});result=f.input('replace','nouveau');assert.equal(result.ok,false);assert.equal(f.editor.textContent,'ancien #tag');assert.deepEqual(f.events,[]);
f=editorFixture({reject:true});result=f.input('replace','nouveau');assert.equal(result.ok,false);assert.equal(f.editor.textContent,'ancien #tag');assert.deepEqual(f.events,[]);
console.log('PASS: one editor transaction, entity replacement/clear, native input only, no forced DOM fallback, rejection and delayed verification');
