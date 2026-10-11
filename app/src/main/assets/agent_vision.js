(function(){
  'use strict';
  const controls=[], roots=[document], seen=new Set(); let visits=0;
  while(roots.length&&controls.length<120&&visits<5000){
    const root=roots.shift(); if(seen.has(root))continue; seen.add(root);
    for(const el of root.querySelectorAll('*')){
      if(++visits>5000)break;
      if(el.shadowRoot)roots.push(el.shadowRoot);
      if(el.matches('input[type="password"],input[type="hidden"],input[type="file"]'))continue;
      if(!el.matches('button,a,input,textarea,select,[role="button"],[role="checkbox"],[role="menuitem"],canvas'))continue;
      const r=el.getBoundingClientRect(),s=getComputedStyle(el);
      if(r.width<=0||r.height<=0||r.right<=0||r.bottom<=0||r.left>=innerWidth||r.top>=innerHeight||s.visibility==='hidden'||s.display==='none')continue;
      controls.push({role:el.tagName.toLowerCase(),label:(el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.innerText||el.getAttribute('title')||'').slice(0,160),
        value:el.matches('input,textarea,select')?String(el.value||'').slice(0,160):'',enabled:!el.disabled,
        bounds:{x:r.left,y:r.top,width:r.width,height:r.height}});
      if(controls.length>=120)break;
    }
  }
  return JSON.stringify({text:(document.body?document.body.innerText:'').slice(0,4000),controls,controls_truncated:controls.length===120||visits>=5000,
    css_width:innerWidth,css_height:innerHeight,scroll_x:scrollX,scroll_y:scrollY,url:location.href});
})()
