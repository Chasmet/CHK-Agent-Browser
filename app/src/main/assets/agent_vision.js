(function(){
  'use strict';
  const started=performance.now(),controls=[],cards=[],scrollables=[],roots=[document],seenRoots=new Set(),seenCards=new Set();
  let visits=0,truncated=false;
  function bounds(r){return {x:r.left,y:r.top,width:r.width,height:r.height};}
  function displayed(el){
    const r=el.getBoundingClientRect();
    if(r.width<=0||r.height<=0||r.right<=0||r.bottom<=0||r.left>=innerWidth||r.top>=innerHeight)return null;
    const s=getComputedStyle(el);
    return s.display==='none'||s.visibility==='hidden'?null:r;
  }
  function text(el,n){
    const v=el.getAttribute('aria-label')||el.getAttribute('title')||el.getAttribute('placeholder')||el.innerText||'';
    return String(v).replace(/\s+/g,' ').trim().slice(0,n);
  }
  function card(el){
    let chosen=el;
    for(let n=0;n<4&&chosen;n++,chosen=chosen.parentElement){
      if(seenCards.has(chosen))continue;
      const label=text(chosen,170);
      if(label.length<3||label.length>160||!displayed(chosen))continue;
      if(chosen.querySelector('button[aria-label="Play"],img,[role=img]')||chosen.matches('article,[role=listitem],li')){
        seenCards.add(chosen);
        cards.push({label,bounds:bounds(chosen.getBoundingClientRect()),role:'card'});
        break;
      }
    }
  }
  while(roots.length&&visits<5000){
    const root=roots.shift();
    if(seenRoots.has(root))continue;seenRoots.add(root);
    let nodes;
    try{nodes=root.querySelectorAll('*');}catch(e){continue;}
    for(const el of nodes){
      if(++visits>5000){truncated=true;break;}
      if(el.shadowRoot)roots.push(el.shadowRoot);
      if(el.matches('input[type="password"],input[type="hidden"],input[type="file"]'))continue;
      let r=null;
      const actionable=el.matches('button,a,input,textarea,select,[contenteditable="true"],[role="textbox"],[role="button"],[role="checkbox"],[role="menuitem"],canvas');
      const scannable=el.scrollHeight>el.clientHeight+16&&el.clientHeight>50;
      const candidate=el.matches('article,[role="listitem"],li')||el.matches('button[aria-label="Play"]');
      if(!actionable&&!scannable&&!candidate)continue;
      r=displayed(el);if(!r)continue;
      if(actionable&&controls.length<120){
        controls.push({role:(el.getAttribute('role')||el.tagName.toLowerCase()),label:text(el,160),
          value:el.matches('input,textarea,select')?String(el.value||'').slice(0,120):'',enabled:!el.disabled,
          bounds:bounds(r)});
      }
      if(scannable&&scrollables.length<16){
        const style=getComputedStyle(el);
        if(['auto','scroll','overlay'].includes(style.overflowY)||el.hasAttribute('data-scroll-container')){
          scrollables.push({role:el.getAttribute('role')||el.tagName.toLowerCase(),
            label:text(el,80),bounds:bounds(r),top:el.scrollTop,
            max_top:Math.max(0,el.scrollHeight-el.clientHeight),visible_height:el.clientHeight});
        }
      }
      if(candidate&&cards.length<32)card(el);
    }
  }
  return JSON.stringify({
    text:(document.body?document.body.innerText:'').slice(0,4000),
    controls,cards,scrollables,controls_truncated:truncated||controls.length===120,
    cards_truncated:cards.length===32,css_width:innerWidth,css_height:innerHeight,
    scroll_x:scrollX,scroll_y:scrollY,url:location.href,
    observed_duration_ms:Math.round(performance.now()-started)
  });
})()