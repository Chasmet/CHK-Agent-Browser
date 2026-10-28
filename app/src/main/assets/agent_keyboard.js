(function () {
  'use strict';
  if (window.__chkAgentKeyboard) return;
  var state = {context: 'web:' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10), next: 0, ids: new WeakMap(), nodes: new Map(), history: new WeakMap(), receipts: new Map()};
  function roots() {
    var result = [], seen = new Set();
    function scan(root) {
      if (!root || seen.has(root) || result.length >= 64) return;
      seen.add(root); result.push(root);
      Array.from(root.querySelectorAll('*')).slice(0, 5000).forEach(function (el) {
        if (el.shadowRoot) scan(el.shadowRoot);
        if (el.tagName === 'IFRAME') { try { scan(el.contentDocument); } catch (ignore) {} }
      });
    }
    scan(document); return result;
  }
  function active() {
    var el = document.activeElement;
    for (var i = 0; el && i < 15; i++) {
      if (el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
      else if (el.tagName === 'IFRAME') { try { var inside = el.contentDocument && el.contentDocument.activeElement; if (inside) el = inside; else break; } catch (ignore) { break; } }
      else break;
    }
    return el;
  }
  function usable(el) {
    if (!el || !el.isConnected || el.disabled || el.readOnly) return false;
    var type = (el.type || '').toLowerCase();
    if (['password', 'hidden', 'file', 'submit', 'button', 'reset', 'image', 'range', 'color'].indexOf(type) >= 0) return false;
    return (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable) && !!el.getClientRects().length;
  }
  function id(el) {
    var key = state.ids.get(el);
    if (!key) { key = 'web:' + state.context.slice(4) + ':' + (++state.next); state.ids.set(el, key); }
    state.nodes.set(key, el);
    if (state.nodes.size > 400) state.nodes.delete(state.nodes.keys().next().value);
    return key;
  }
  function find(selector) {
    if (!selector) { var focused = active(); if (!usable(focused)) throw Error('Aucun champ web ciblé.'); return focused; }
    if (selector.indexOf('web:') === 0) {
      var tracked = state.nodes.get(selector);
      if (!usable(tracked)) throw Error('Champ web remplacé : inspecte le formulaire à nouveau.');
      return tracked;
    }
    var matches = [];
    roots().forEach(function (root) { Array.from(root.querySelectorAll(selector)).forEach(function (el) { if (matches.indexOf(el) < 0) matches.push(el); }); });
    if (matches.length !== 1) throw Error(matches.length ? 'Sélecteur ambigu : utilise le sélecteur unique de l’inspection.' : 'Champ web introuvable.');
    if (!usable(matches[0])) throw Error('Champ protégé, masqué, désactivé ou en lecture seule.');
    return matches[0];
  }
  function value(el) { return el.isContentEditable ? el.textContent : String(el.value || ''); }
  // Editors such as ProseMirror represent each visible line as a separate node.
  // textContent can concatenate those nodes without the line breaks the user sees.
  function sameValue(el, expected) {
    if (value(el) === expected) return true;
    if (!el.isContentEditable) return false;
    var normalize = function (text) { return String(text || '').replace(/\r\n/g, '\n').replace(/\n+$/, ''); };
    return normalize(el.innerText) === normalize(expected);
  }
  function cpLength(text) { return Array.from(text).length; }
  function offset(text, at) { var points = Array.from(text); if (!Number.isInteger(at) || at < 0 || at > points.length) throw Error('Position de curseur hors du champ.'); return points.slice(0, at).join('').length; }
  function clip(text, limit) { return Array.from(String(text)).slice(0, limit).join(''); }
  function selection(el) {
    var text = value(el);
    if (!el.isContentEditable) return {start: el.selectionStart == null ? text.length : el.selectionStart, end: el.selectionEnd == null ? text.length : el.selectionEnd};
    var sel = el.ownerDocument.getSelection();
    if (!sel || !sel.rangeCount || !el.contains(sel.anchorNode) || !el.contains(sel.focusNode)) return {start: text.length, end: text.length};
    var range = sel.getRangeAt(0), prefix = range.cloneRange(); prefix.selectNodeContents(el); prefix.setEnd(range.startContainer, range.startOffset); var start = prefix.toString().length;
    prefix.setEnd(range.endContainer, range.endOffset); return {start: start, end: prefix.toString().length};
  }
  function setSelection(el, start, end) {
    el.focus();
    if (!el.isContentEditable) { if (typeof el.setSelectionRange !== 'function') throw Error('Ce type de champ ne permet pas le curseur.'); el.setSelectionRange(start, end); return; }
    var doc = el.ownerDocument, walker = doc.createTreeWalker(el, 4), nodes = [], node;
    while ((node = walker.nextNode())) nodes.push(node);
    if (!nodes.length) { var empty = doc.createTextNode(''); el.appendChild(empty); nodes.push(empty); }
    function point(at) { for (var i = 0; i < nodes.length; i++) { if (at <= nodes[i].length) return [nodes[i], at]; at -= nodes[i].length; } return [nodes[nodes.length-1], nodes[nodes.length-1].length]; }
    var a = point(start), b = point(end), range = doc.createRange(); range.setStart(a[0], a[1]); range.setEnd(b[0], b[1]); var sel = doc.getSelection(); sel.removeAllRanges(); sel.addRange(range);
  }
  function describe(el) {
    var text = value(el), sel = selection(el), h = state.history.get(el);
    var label = el.getAttribute('aria-label') || (el.labels && el.labels[0] && el.labels[0].textContent) || el.placeholder || el.name || el.id || el.tagName;
    var out = {selector: id(el), label: clip(label,100), kind: (el.type || el.tagName).toLowerCase(), value: clip(text,256), value_truncated: cpLength(text)>256, length: cpLength(text), focused: active()===el, selection_start: cpLength(text.slice(0,sel.start)), selection_end: cpLength(text.slice(0,sel.end)), can_undo: !!(h && h.undo.length), can_redo: !!(h && h.redo.length)};
    if (el.type==='checkbox' || el.type==='radio') out.checked=el.checked;
    if (el.validity) { out.valid=el.validity.valid; if (!out.valid) out.validation_message=clip(el.validationMessage,200); }
    if (el.tagName==='SELECT') out.options=Array.from(el.options).slice(0,30).map(function (o) { return {value:clip(o.value,100),label:clip(o.textContent,100),disabled:o.disabled}; });
    return out;
  }
  function fields() {
    var list=[];
    roots().forEach(function (root) { Array.from(root.querySelectorAll('input,textarea,select,[contenteditable=true]')).forEach(function (el) { if (usable(el) && list.indexOf(el)<0) list.push(el); }); });
    return list;
  }
  function inspect(offset) {
    offset=offset||0;if(!Number.isInteger(offset)||offset<0||offset>5000)throw Error('Pagination de champs invalide.');
    state.nodes.forEach(function (node,key) { if (!node.isConnected) state.nodes.delete(key); });
    var list=fields(), out=[], truncated=false;
    list.slice(offset).some(function (el) { out.push(describe(el)); if (out.length>60 || (typeof TextEncoder==='function'?new TextEncoder().encode(JSON.stringify(out)).length:JSON.stringify(out).length*3)>22000) {out.pop();truncated=true;return true;} return false; });
    return {ok:true,target:'web',context:state.context,document_url:location.href,controls:out,truncated:truncated,next_offset:truncated?offset+out.length:-1,total:list.length,position_unit:'unicode_code_points'};
  }
  function history(el) {
    var h=state.history.get(el);
    if (!h) {h={undo:[],redo:[],expected:value(el)};state.history.set(el,h);}
    if (h.expected!==value(el)) {h.undo=[];h.redo=[];h.expected=value(el);}
    return h;
  }
  function before(el,text) {
    var view=el.ownerDocument.defaultView, event;
    try {event=new view.InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text});}
    catch(ignore) {event=new view.Event('beforeinput',{bubbles:true,cancelable:true});}
    if (!el.dispatchEvent(event)) throw Error('La page a refusé cette saisie.');
  }
  function normalize(el,text) {
    if (text.length>12000) throw Error('Champ trop long (12 000 caractères maximum).');
    if (el.type==='number' && text) {text=text.replace(',','.');if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(text)) throw Error('Valeur numérique invalide.');}
    if (typeof el.maxLength==='number' && el.maxLength>=0 && text.length>el.maxLength) throw Error('Le texte dépasse la longueur autorisée du champ.');
    return text;
  }
  function write(el,text,caret,save,edit) {
    if (el.tagName==='SELECT' || el.type==='checkbox' || el.type==='radio') throw Error('Utilise select_option ou check pour ce contrôle.');
    text=normalize(el,text);before(el,text);el.focus();el.scrollIntoView({block:'center',inline:'nearest'});
    var h=history(el), old={text:value(el),selection:selection(el)};
    if (save) {h.undo.push(old);if(h.undo.length>8)h.undo.shift();h.redo=[];}
    var emitted=false, observeInput=function(){emitted=true;};el.addEventListener('input',observeInput,true);
    if (el.isContentEditable) {
      setSelection(el,edit?edit.from:0,edit?edit.to:value(el).length);
      var inserted=edit?edit.text:text;
      if (!el.ownerDocument.execCommand('insertText',false,inserted)) {
        var range=el.ownerDocument.getSelection().getRangeAt(0);range.deleteContents();range.insertNode(el.ownerDocument.createTextNode(inserted));
      }
    } else {
      var proto=Object.getPrototypeOf(el), setter;
      while(proto&&!setter){var desc=Object.getOwnPropertyDescriptor(proto,'value');setter=desc&&desc.set;proto=Object.getPrototypeOf(proto);}
      if (setter) setter.call(el,text);else el.value=text;
    }
    el.removeEventListener('input',observeInput,true);
    try {setSelection(el,Math.min(caret,text.length),Math.min(caret,text.length));}catch(ignore){}
    var view=el.ownerDocument.defaultView;
    if(!emitted)try {el.dispatchEvent(new view.InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));} catch(ignore) {el.dispatchEvent(new view.Event('input',{bubbles:true}));}
    el.dispatchEvent(new view.Event('change',{bubbles:true}));h.expected=value(el);
    if (!sameValue(el,text)) throw Error('La page a filtré la saisie : inspecte sa valeur réelle.');
    return text;
  }
  function mutation(el,op,p) {
    var current=value(el), sel=selection(el), from=Math.min(sel.start,sel.end), to=Math.max(sel.start,sel.end), expected, caret;
    if (op==='check') {
      if (el.type!=='checkbox' && el.type!=='radio') throw Error('Champ non cochable.');
      var checked=p.checked!==false;if(el.checked!==checked)el.click();if(el.checked!==checked)throw Error('État de la case non confirmé.');return {checked:checked};
    }
    if (op==='select_option') {
      if(el.tagName!=='SELECT')throw Error('Champ non sélectionnable.');
      var option=Array.from(el.options).find(function (o) {return o.value===String(p.text || '');});
      if(!option||option.disabled)throw Error('Option introuvable ou désactivée.');el.value=option.value;
      var view=el.ownerDocument.defaultView;el.dispatchEvent(new view.Event('input',{bubbles:true}));el.dispatchEvent(new view.Event('change',{bubbles:true}));
      if(el.value!==option.value)throw Error('Option refusée par la page.');return {expected:option.value};
    }
    if (op==='select') {setSelection(el,offset(current,p.start==null?0:p.start),offset(current,p.end==null?(p.start||0):p.end));return {};}
    if (op==='key') {
      var key=String(p.key||'').toUpperCase();
      if (key==='SELECT_ALL') {setSelection(el,0,current.length);return {};}
      if (key==='TAB'||key==='SHIFT_TAB') {var list=fields(), index=list.indexOf(el)+(key==='TAB'?1:-1);if(index<0||index>=list.length)throw Error('Aucun autre champ dans ce sens.');list[index].focus();list[index].scrollIntoView({block:'center'});return {};}
      if (key==='HOME'||key==='END'||key==='LEFT'||key==='RIGHT') {
        var at=key==='HOME'?0:key==='END'?current.length:key==='LEFT'?(from!==to?from:offset(current,Math.max(0,cpLength(current.slice(0,from))-1))):(from!==to?to:offset(current,Math.min(cpLength(current),cpLength(current.slice(0,to))+1)));setSelection(el,at,at);return {};
      }
      if (key==='UNDO'||key==='REDO') {
        var h=history(el), source=key==='UNDO'?h.undo:h.redo,dest=key==='UNDO'?h.redo:h.undo;
        if(!source.length)throw Error('Aucune saisie agent à annuler ou rétablir.');var old=source.pop();dest.push({text:current,selection:sel});var restored=write(el,old.text,old.selection.end,false);try{setSelection(el,old.selection.start,old.selection.end);}catch(ignore){}return {expected:restored};
      }
      if (key==='ENTER') {
        if(el.tagName==='TEXTAREA'||el.isContentEditable){op='insert';p={text:'\n'};}
        else {el.focus();return {native_enter:true};}
      } else if(key==='BACKSPACE'||key==='DELETE') {
        if(from===to){if(key==='BACKSPACE'&&from>0)from=offset(current,cpLength(current.slice(0,from))-1);if(key==='DELETE'&&to<current.length)to=offset(current,cpLength(current.slice(0,to))+1);}
        return {expected:write(el,current.slice(0,from)+current.slice(to),from,true,{from:from,to:to,text:''})};
      } else throw Error('Touche clavier non prise en charge.');
    }
    var text=String(p.text==null?'':p.text);if(text.length>8000)throw Error('Saisie trop longue (8 000 caractères maximum).');
    if(op==='replace'||op==='clear'){expected=op==='clear'?'':text;caret=expected.length;}
    else if(op==='insert'){expected=current.slice(0,from)+text+current.slice(to);caret=from+text.length;}
    else throw Error('Opération clavier inconnue.');
    return {expected:write(el,expected,caret,true,op==='insert'?{from:from,to:to,text:text}:null)};
  }
  function contextCheck(p) {
    var expected=p.expected_context||'', selector=p.selector||'';
    if(!expected && selector.indexOf(state.context+':')===0)expected=state.context;
    if(expected!==state.context)throw Error('Contexte web absent ou périmé : inspecte le formulaire avant la saisie.');
    if(p.expected_host && p.expected_host.toLowerCase()!==location.hostname.toLowerCase())throw Error('La destination web a changé.');
  }
  function verify(p) {
    contextCheck(p);var saved=state.receipts.get(p.receipt);if(!saved)throw Error('Vérification interrompue : relis le formulaire avant de recommencer.');state.receipts.delete(p.receipt);
    var succeeded=0;
    saved.results.forEach(function (row) {
      if(!row.ok)return;
      try {var el=state.nodes.get(row.selector);if(!usable(el))throw Error('Champ remplacé pendant la saisie.');
        if(row.expected!==undefined && !sameValue(el,row.expected))throw Error('La page a changé la valeur après la saisie.');
        if(row.checked!==undefined && el.checked!==row.checked)throw Error('La page a changé l’état de la case.');
        row.field=describe(el);row.verified=true;succeeded++;
      }catch(e){row.ok=false;row.error=e.message;row.verified=false;}
      delete row.expected;delete row.checked;
    });
    var out={ok:succeeded===saved.results.length,target:'web',context:state.context,succeeded:succeeded,failed:saved.results.length-succeeded,results:saved.results};
    return out;
  }
  function run(p) {
    try {
      var op=p.operation||'inspect';if(op==='inspect')return JSON.stringify(inspect(p.offset));if(op==='verify')return JSON.stringify(verify(p));
      contextCheck(p);
      var entries=op==='fill'?p.fields:[p];if(!Array.isArray(entries)||!entries.length||entries.length>25)throw Error('Un lot doit contenir entre 1 et 25 champs.');
      var results=[], hasWrite=false;
      entries.forEach(function (entry) {
        var row={selector:String(entry.selector||''),ok:false};
        try {var operation=op==='fill'?(entry.operation||'replace'):op;
          if(op==='fill'&&['replace','insert','clear','check','select_option'].indexOf(operation)<0)throw Error('Un lot remplit des champs sans cliquer ni envoyer.');
          var el=find(entry.selector||'');row.selector=id(el);var done=mutation(el,operation,entry);row.ok=true;row.field=describe(el);
          if(done.native_enter)row.native_enter=true;
          if(done.expected!==undefined){row.expected=done.expected;hasWrite=true;}if(done.checked!==undefined){row.checked=done.checked;hasWrite=true;}
        }catch(e){row.error=e.message;}results.push(row);
      });
      if(results.length===1&&results[0].native_enter)return JSON.stringify({ok:true,native_enter:true,context:state.context,field:results[0].field});
      var succeeded=results.filter(function (r) {return r.ok;}).length,out={ok:succeeded===results.length,target:'web',context:state.context,succeeded:succeeded,failed:results.length-succeeded,results:results};
      if(hasWrite){var receipt=state.context+':r'+(++state.next);state.receipts.set(receipt,{results:results});while(state.receipts.size>8)state.receipts.delete(state.receipts.keys().next().value);out.receipt=receipt;}
      // Only the internal receipt contains the full expected text, never the public response.
      out.results=results.map(function (r) {var copy=Object.assign({},r);delete copy.expected;delete copy.checked;return copy;});
      return JSON.stringify(out);
    }catch(e){return JSON.stringify({ok:false,error:(p.operation==='verify'?'Saisie potentiellement appliquée : ':'')+e.message,may_have_applied:p.operation==='verify',context:state.context});}
  }
  window.__chkAgentKeyboard={run:run};
})();
