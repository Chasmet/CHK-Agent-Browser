const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/agent_vision.js','utf8');
const rect={left:0,top:0,right:300,bottom:300,width:300,height:300};
const control={tagName:'INPUT',disabled:false,value:'Brume',scrollHeight:30,clientHeight:30,
 getAttribute:k=>k==='aria-label'?'Titre actif':null,getBoundingClientRect:()=>rect,
 closest:()=>null,matches:q=>q.includes('button,a,input'),getRootNode:()=>document};
const modal={scrollHeight:300,clientHeight:300,innerText:'Fenêtre de création',
 getAttribute:()=>null,getBoundingClientRect:()=>rect,closest:()=>null,matches:()=>false,
 querySelectorAll:()=>[control],contains:n=>n===control||n===modal};
const background=Array.from({length:6000},()=>({matches:()=>false,scrollHeight:0,clientHeight:0}));
const document={contains:n=>n===modal,body:{innerText:'Bibliothèque derrière'},querySelectorAll:q=>q==='*'?[...background,modal,control]:[modal]};
const result=JSON.parse(vm.runInNewContext(source,{document,performance:{now:()=>0},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),innerWidth:400,innerHeight:800,scrollX:0,scrollY:0,location:{href:'https://fixture.example/'}}));
assert.equal(result.observation_scope,'dialog');assert.equal(result.text,'Fenêtre de création');
assert.equal(result.controls.length,1);assert.equal(result.controls[0].label,'Titre actif');assert.equal(result.controls_truncated,false);
console.log('PASS: dialog before bounded scan of 6000 background controls; active text/fields only');
