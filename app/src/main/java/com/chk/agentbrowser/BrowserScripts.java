package com.chk.agentbrowser;

/** Browser scripts shared by visible and background WebViews. Arguments are JSON-quoted literals. */
public final class BrowserScripts {
    private BrowserScripts() {}

    private static String finder() {
        return "function roots(){if(roots.cached)return roots.cached;var out=[document],seen=[];function scan(root){if(!root||seen.indexOf(root)>=0)return;seen.push(root);"
            +"var all=[];try{all=Array.from(root.querySelectorAll('*'));}catch(e){}"
            +"all.forEach(function(el){try{if(el.shadowRoot){out.push(el.shadowRoot);scan(el.shadowRoot);}}catch(e){}"
            +"if((el.tagName||'').toLowerCase()==='iframe'){try{var d=el.contentDocument;if(d){out.push(d);scan(d);}}catch(e){}}});}"
            +"scan(document);roots.cached=out;return out;}"
            +"function deepFind(sel){var rs=roots();if(sel.indexOf('label:')===0){var label=sel.slice(6).trim().replace(/\\s+/g,' '),hits=[];"
            +"rs.forEach(function(root){Array.from(root.querySelectorAll('button,a,input,textarea,select,[role=button],[role=checkbox],[contenteditable=true]')).forEach(function(e){"
            +"var name=(e.getAttribute('aria-label')||e.getAttribute('title')||e.getAttribute('placeholder')||e.innerText||'').trim().replace(/\\s+/g,' ');"
            +"if(name===label&&e.getClientRects().length&&hits.indexOf(e)<0)hits.push(e);});});"
            +"if(hits.length>1)throw Error('Libellé ambigu : '+label);return hits.length===1?hits[0]:null;}"
            +"for(var i=0;i<rs.length;i++){try{var e=rs[i].querySelector(sel);if(e)return e;}catch(x){}}return null;}"
            +"function selectorFor(el){var parts=[],n=el;function unique(s){var count=0;roots().forEach(function(r){try{count+=r.querySelectorAll(s).length;}catch(e){}});return count===1&&deepFind(s)===n;}"
            +"var t=(el.type||'').toLowerCase();if((t==='radio'||t==='checkbox')&&el.name&&el.value){var stable='input[type='+JSON.stringify(t)+'][name='+JSON.stringify(el.name)+'][value='+JSON.stringify(el.value)+']';if(unique(stable))return stable;}"
            +"while(n&&n.nodeType===1&&parts.length<9){if(n.id&&typeof CSS!=='undefined'&&CSS.escape){var anchor='#'+CSS.escape(n.id);if(unique(anchor)){parts.unshift(anchor);var found=parts.join('>');return deepFind(found)===el?found:'';}}"
            +"var idx=1,sib=n.previousElementSibling;while(sib){if(sib.tagName===n.tagName)idx++;sib=sib.previousElementSibling;}parts.unshift(n.tagName.toLowerCase()+':nth-of-type('+idx+')');n=n.parentElement;}"
            +"var sel=parts.join('>');return deepFind(sel)===el?sel:'';}";
    }

    static String finderForUpload(){return finder();}

    private static String highlight(){return "function mark(el){try{var r=el.getBoundingClientRect(),dot=document.createElement('div');dot.setAttribute('data-chk-pointer','true');dot.style.cssText='position:fixed;pointer-events:none!important;z-index:2147483647;border:3px solid #21d4c6;border-radius:50%;width:34px;height:34px;box-shadow:0 0 0 7px #21d4c633;';dot.style.left=(Math.max(20,Math.min(innerWidth-20,r.left+r.width/2))-17)+'px';dot.style.top=(Math.max(20,Math.min(innerHeight-20,r.top+r.height/2))-17)+'px';document.documentElement.appendChild(dot);setTimeout(function(){dot.remove();},900);}catch(ignore){}}";}

    public static String readPage() {
        return "(function(){"+finder()
            + "var out={text:(document.body&&document.body.innerText||'').slice(0,5000),elements:[],media:[],forms:0,ready:document.readyState,document_url:(typeof location!=='undefined'?location.href:''),visibility:(document.visibilityState||'unknown')};"
            + "var rs=roots(),nodes=[];rs.forEach(function(r){try{nodes=nodes.concat(Array.from(r.querySelectorAll('input,textarea,select,button,a,[role=button],[role=checkbox],[role=radio],[contenteditable=true],[tabindex=\"0\"]')));}catch(e){}});"
            + "function label(el){return (el.getAttribute('aria-label')||el.getAttribute('title')||el.placeholder||el.innerText||el.name||'').trim().replace(/\\s+/g,' ').slice(0,120);}"
            + "function score(el){var tag=(el.tagName||'').toLowerCase(),v=label(el).toLowerCase();"
            + "if(v.indexOf('add assets')>=0||v.indexOf('add from drive')>=0||v.indexOf('upload')>=0||v.indexOf('save as draft')>=0||v.indexOf('enregistrer')>=0)return 180;"
            + "if((el.type||'').toLowerCase()==='file')return 170;if(tag==='input'||tag==='textarea'||tag==='select')return 130;"
            + "if(v.indexOf('icon')>=0||v.indexOf('screenshot')>=0||v.indexOf('description')>=0)return 110;"
            + "if(el.closest&&el.closest('header,nav,footer'))return 5;if(tag==='a')return 20;return 40;}"
            + "nodes=nodes.filter(function(e,i,a){return a.indexOf(e)===i;});nodes.sort(function(a,b){return score(b)-score(a);});"
            + "function selector(el){return selectorFor(el);}"
            + "for(var i=0;i<nodes.length&&out.elements.length<40;i++){var el=nodes[i];"
            + "if(['password','hidden'].indexOf((el.type||'').toLowerCase())>=0)continue;"
            + "var visible=true;try{visible=!!el.getClientRects().length||((el.type||'').toLowerCase()==='file');}catch(e){}if(!visible)continue;"
            + "var path=selector(el);if(!path||path.length>340)continue;var item={selector:path,tag:(el.tagName||'').toLowerCase(),type:el.type||'',label:label(el),disabled:!!el.disabled};"
            + "if((el.type||'').toLowerCase()==='checkbox'||(el.type||'').toLowerCase()==='radio')item.checked=!!el.checked;"
            + "if((el.type||'').toLowerCase()==='file')item.multiple=!!el.multiple;"
            + "out.elements.push(item);if(JSON.stringify(out).length>9400){out.elements.pop();break;}}"
            + "out.cards=[];out.scrollables=[];try{var seenCards=new Set(),seenScroll=new Set();rs.forEach(function(root){var collection=[];try{collection="
            + "Array.from(root.querySelectorAll('button[aria-label=\"Play\"],[role=listitem],article,[class*=voice],[class*=Voice]')).slice(0,350);}catch(e){"
            + "}for(var c of collection){if(out.cards.length>=28)break;var item=c,name='',rect;for(var hop=0;item&&hop<5;hop++,item=item.parentElement){nam"
            + "e=String(item.innerText||'').trim().replace(/\\s+/g,' ');if(name.length>=3&&name.length<=170)break;}if(!item||!name||seenCards.has(name))cont"
            + "inue;try{rect=item.getBoundingClientRect();if(rect.width<30||rect.height<22||rect.right<=0||rect.bottom<=0||rect.left>=innerWidth||rect.top>"
            + "=innerHeight)continue;seenCards.add(name);out.cards.push({label:name.slice(0,145),bounds:{x:rect.left,y:rect.top,width:rect.width,height:rec"
            + "t.height}});}catch(ignore){}}var all=[];try{all=Array.from(root.querySelectorAll('[role=dialog],div,section,ul,main')).slice(0,1600);}catch("
            + "e){}for(var e of all){if(out.scrollables.length>=12)break;if(seenScroll.has(e)||e.scrollHeight<e.clientHeight+20||e.clientHeight<48)continue"
            + ";var st=getComputedStyle(e);if(st.overflowY!=='auto'&&st.overflowY!=='scroll'&&st.overflowY!=='overlay')continue;var rc=e.getBoundingClientR"
            + "ect();if(rc.width<60||rc.height<50||rc.right<=0||rc.bottom<=0||rc.top>=innerHeight)continue;seenScroll.add(e);out.scrollables.push({label:St"
            + "ring(e.getAttribute('aria-label')||e.getAttribute('role')||e.className||'').slice(0,80),bounds:{x:rc.left,y:rc.top,width:rc.width,height:rc."
            + "height},top:e.scrollTop,max_top:e.scrollHeight-e.clientHeight});}});}catch(error){}"
            + "try{out.forms=roots().reduce(function(n,r){return n+r.querySelectorAll('form').length;},0);}catch(e){}"
            + "try{var seenMedia=[];roots().forEach(function(root){Array.from(root.querySelectorAll('video')).forEach(function(v){"
            + "if(seenMedia.length>=8)return;var src=v.currentSrc||v.src||((v.querySelector('source')||{}).src)||'';"
            + "if(src&&seenMedia.indexOf(src)<0){seenMedia.push(src);out.media.push({tag:'video',src:src.slice(0,2400),duration:isFinite(v.duration)?v.duration:null});}"
            + "});});}catch(e){}"
            + "return JSON.stringify(out);})()";
    }

    /** A read-only postcondition for verified clicks. DONE means every requested condition holds. */
    public static String condition(String selectorLiteral,String textLiteral,String urlLiteral) {
        return "(function(){try{"+finder()
            +"var sel="+selectorLiteral+",text="+textLiteral+",url="+urlLiteral+";"
            +"if(!sel&&!text&&!url)return 'ERROR: condition absente';"
            +"if(sel&&!deepFind(sel))return 'WAIT';"
            +"if(text&&(document.body&&document.body.innerText||'').indexOf(text)<0)return 'WAIT';"
            +"if(url&&location.href.indexOf(url)<0)return 'WAIT';"
            +"return 'DONE';"
            +"}catch(e){return 'ERROR: '+e.message;}})()";
    }


    /** Safe JavaScript string literal without requiring Android's JSON classes. */
    private static String quoteJs(String raw){
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(c=='\\')out.append("\\\\");
            else if(c=='"')out.append("\\\"");
            else if(c<' ') {
                String h=Integer.toHexString(c);
                out.append("\\u");
                for(int pad=h.length();pad<4;pad++)out.append('0');
                out.append(h);
            }else out.append(c);
        }
        return out.append('"').toString();
    }

    /** Scroll the actual visible inner list (dialog/card grid) instead of only the WebView root.
     * Returns measured movement, never claims success solely because a gesture was sent. */
    public static String scrollContainer(String direction,int pixels,String selector,String untilText){
        return "(function(){try{" + finder()
            + "var dir="
            + quoteJs(direction)
            + ",pixels="
            + Integer.toString(pixels)
            + ",sel="
            + quoteJs(selector)
            + ",wanted="
            + quoteJs(untilText)
            + ";var modal=document.querySelector('[role=dialog][aria-modal=true]')||document.querySelector('[role=dialog]');var scope=sel?deepFind(sel):modal;if"
            + "(sel&&!scope)return JSON.stringify({ok:false,error:'Zone de défilement introuvable',selector:sel});var listRoots=scope?[scope]:[document];var candida"
            + "tes=[],seen=new Set();function visible(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.height>32&&r.width>35&&r.bottom>0&&r.rig"
            + "ht>0&&r.top<innerHeight&&r.left<innerWidth&&s.display!=='none'&&s.visibility!=='hidden';}function inspect(root){var es=root.nodeType===1?[root]:[];try{es=es.concat(Ar"
            + "ray.from(root.querySelectorAll('*')).slice(0,4000));}catch(ignore){}for(var el of es){if(seen.has(el))continue;seen.add(el);if(!visible(el))conti"
            + "nue;var sty=getComputedStyle(el);var scrollable=el.scrollHeight>el.clientHeight+10&&(sty.overflowY==='auto'||sty.overflowY==='scroll'||sty.overfl"
            + "owY==='overlay'||el.hasAttribute('data-scroll-container'));if(scrollable){var r=el.getBoundingClientRect();candidates.push({el:el,score:(scope&&s"
            + "cope.contains(el)?5000:0)+(modal&&modal.contains(el)?2000:0)+Math.min(1000,el.scrollHeight-el.clientHeight)+(Math.min(r.height,900)/5),rect:r});}"
            + "}}for(var root of listRoots)inspect(root);if(!scope||scope===document){if(modal)inspect(modal);}var fallback=document.scrollingElement||document.docu"
            + "mentElement; if(!scope||scope===document)candidates.push({el:fallback,score:1,rect:fallback.getBoundingClientRect()});if(!candidates.length)retur"
            + "n JSON.stringify({ok:true,moved:false,reason:'Aucune zone interne défilable',selector:sel});candidates.sort((a,b)=>b.score-a.score);var delta=(di"
            + "r==='up'?-1:1)*Math.max(60,Math.min(2400,pixels||Math.round(innerHeight*.6)));var found=false,selected=candidates[0],before=0,after=0;function ha"
            + "sTarget(el){if(!wanted)return false;var nodes=el.querySelectorAll('article,li,[role=listitem],button,[class*=card],[class*=Card]');for(var i=0;i<"
            + "Math.min(nodes.length,250);i++){var e=nodes[i],r=e.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight&&(e.innerText||'').toLowerCase().incl"
            + "udes(wanted.toLowerCase()))return true;}return false;}if(wanted&&candidates.some(c=>hasTarget(c.el))){found=true;return JSON.stringify({ok:true,m"
            + "oved:false,found:true,matched:wanted,reason:'Element visible',container_count:candidates.length});}for(var c of candidates){var e=c.el,b=e.scroll"
            + "Top;e.scrollTop=Math.max(0,Math.min(e.scrollHeight-e.clientHeight,b+delta));if(Math.abs(e.scrollTop-b)>1){selected=c;before=b;after=e.scrollTop;b"
            + "reak;}}found=hasTarget(selected.el);var r=selected.rect;return JSON.stringify({ok:true,moved:Math.abs(after-before)>1,found:found,scroll_top_befo"
            + "re:before,scroll_top_after:after,container_count:candidates.length,container_bounds:{x:r.left,y:r.top,width:r.width,height:r.height},scrollable_h"
            + "eight:selected.el.scrollHeight,visible_height:selected.el.clientHeight});"
            + "}catch(e){return JSON.stringify({ok:false,error:String(e.message||e)});}})()";
    }

    public static String type(String selectorLiteral,String textLiteral) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");"
            + "if(!el)return 'Champ introuvable';if(['password','file','hidden'].indexOf((el.type||'').toLowerCase())>=0)return 'Champ sensible bloqué';"
            + "if(el.disabled||el.readOnly)return 'Champ non modifiable';if(el.scrollIntoView)el.scrollIntoView({block:'center'});mark(el);el.focus();"
            + "if(el.isContentEditable){el.textContent="+textLiteral+";}else{if(!('value' in el))return 'Champ non saisissable';"
            + "var proto=Object.getPrototypeOf(el),setter=null;while(proto&&!setter){var desc=Object.getOwnPropertyDescriptor(proto,'value');setter=desc&&desc.set;proto=Object.getPrototypeOf(proto);}"
            + "if(setter)setter.call(el,"+textLiteral+");else el.value="+textLiteral+";}"
            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'Saisie effectuée';"
            + "}catch(e){return 'Erreur : '+e.message;}})()";
    }

    /** Validate every target before editing, then return values read from the actual controls. */
    public static String formFields(String fieldsLiteral,boolean write){
        return "(function(){try{"+finder()+"var fields="+fieldsLiteral+",targets=[],out=[];"
            +"for(var i=0;i<fields.length;i++){var f=fields[i],el=deepFind(f.selector),k=f.kind||'text';"
            +"if(!el||el.disabled||el.readOnly)throw Error('Champ absent ou non modifiable : '+f.selector);"
            +"var t=(el.type||'').toLowerCase(),role=el.getAttribute('role')||'';"
            +"if(['password','file','hidden'].indexOf(t)>=0)throw Error('Champ sensible ou fichier : utiliser la commande adaptée');"
            +"if(k==='text'&&!el.isContentEditable&&['input','textarea'].indexOf((el.tagName||'').toLowerCase())<0)throw Error('Champ texte attendu');"
            +"if(k==='select'&&((el.tagName||'').toLowerCase()!=='select'||!Array.from(el.options).some(function(o){return o.value===f.value&&!o.disabled;})))throw Error('Option sélectionnable absente');"
            +"if(k==='check'&&['checkbox','radio'].indexOf(t)<0&&['checkbox','radio','switch'].indexOf(role)<0)throw Error('Case ou bouton radio attendu');"
            +"if(k==='check'&&t==='radio'&&f.value===false)throw Error('Choisir une autre option radio explicitement');"
            +"targets.push(el);}"
            +"function val(el,k){return k==='check'?('checked' in el?!!el.checked:el.getAttribute('aria-checked')==='true'):String(el.isContentEditable?el.textContent:el.value);}"
            +"for(var i=0;i<fields.length;i++){var f=fields[i],el=targets[i],k=f.kind||'text';"
            + (write?"if(val(el,k)!==f.value){if(k==='check')el.click();else{if(el.isContentEditable)el.textContent=f.value;else{var proto=Object.getPrototypeOf(el),setter=null;while(proto&&!setter){var d=Object.getOwnPropertyDescriptor(proto,'value');setter=d&&d.set;proto=Object.getPrototypeOf(proto);}if(setter)setter.call(el,f.value);else el.value=f.value;}el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));el.dispatchEvent(new Event('change',{bubbles:true,composed:true}));}}":"")
            +"var actual=val(el,k),ok=el.isConnected&&actual===f.value;out.push({selector:f.selector,kind:k,matches:ok});"
            +"if(!ok)return JSON.stringify({ok:false,verified:false,fields:out,error:'Valeur refusée par la page'});}"
            +"return JSON.stringify({ok:true,verified:true,fields:out,count:out.length});"
            +"}catch(e){return JSON.stringify({ok:false,verified:false,error:e.message});}})()";
    }

    public static String click(String selectorLiteral) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Élément introuvable';"
            +"if(el.disabled||el.getAttribute('aria-disabled')==='true'||(el.closest&&el.closest('[inert]')))return 'Élément désactivé';el.scrollIntoView({block:'center',inline:'center'});mark(el);el.click();return 'Clic effectué';"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
    }
    /** Inspects a live button without clicking it; disabled is not a failure. */
    public static String clickState(String selectorLiteral) {
        return "(function(){try{"+finder()
            +"var el=deepFind("+selectorLiteral+");if(!el)return 'MISSING';"
            +"var aria=(el.getAttribute('aria-disabled')||'').toLowerCase();"
            +"if(el.disabled||aria==='true'||el.closest('[inert]'))return 'WAIT';"
            +"if(el.getAttribute('aria-busy')==='true')return 'WAIT';"
            +"return 'READY';}catch(e){return 'ERROR:'+e.message;}})()";
    }

    public static String valueMatches(String selectorLiteral,String expectedLiteral){
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");"
            +"if(!el||el.disabled)return false;return String(el.isContentEditable?el.textContent:el.value)==="+expectedLiteral+";"
            +"}catch(e){return false;}})()";
    }

    public static String select(String selectorLiteral,String valueLiteral,String labelLiteral,int index) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Menu introuvable';"
            +"if((el.tagName||'').toLowerCase()!=='select')return 'Élément non sélectionnable';var opt=null;"
            +"var value="+valueLiteral+",label="+labelLiteral+",idx="+index+";"
            +"if(value)opt=Array.from(el.options).find(function(o){return o.value===value;});"
            +"if(!opt&&label)opt=Array.from(el.options).find(function(o){return (o.textContent||'').trim()===label;});"
            +"if(!opt&&idx>=0&&idx<el.options.length)opt=el.options[idx];if(!opt)return 'Option introuvable';"
            +"el.value=opt.value;opt.selected=true;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
            +"return 'Option sélectionnée : '+(opt.textContent||opt.value);"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String check(String selectorLiteral,boolean checked) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Option introuvable';"
            +"var t=(el.type||'').toLowerCase();if(t!=='checkbox'&&t!=='radio'&&el.getAttribute('role')!=='checkbox'&&el.getAttribute('role')!=='radio')return 'Élément non cochable';"
            +"var wanted="+checked+";if('checked' in el){if(!!el.checked!==wanted)el.click();return (!!el.checked===wanted)?'État confirmé':'État non confirmé';}"
            +"var now=el.getAttribute('aria-checked')==='true';if(now!==wanted)el.click();return 'État demandé';"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
    }

    /** Read-only account identity guard before a publication. */
    public static String accountMatches(String selectorLiteral,String accountLiteral) {
        return "(function(){try{"+finder()
            +"var el=deepFind("+selectorLiteral+");if(!el)return 'ACCOUNT_MISSING';"
            +"var expected=("+accountLiteral+").trim().toLowerCase();"
            +"if(!expected)return 'ACCOUNT_UNSET';"
            +"var text=[el.innerText||'',el.textContent||'',el.getAttribute('aria-label')||'',"
            +"el.getAttribute('title')||'',el.getAttribute('alt')||''].join(' ').toLowerCase();"
            +"return text.indexOf(expected)>=0?'MATCH':'ACCOUNT_MISMATCH';"
            +"}catch(e){return 'ACCOUNT_CHECK_FAILED';}})()";
    }

    public static String exists(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");return !!el;}catch(e){return false;}})()";
    }

    /**
     * Locate the real visible upload control associated with an inspected
     * input[type=file]. Returns only geometry; native Android delivers the tap.
     * Hidden inputs are supported only when a clearly labelled file picker
     * button is found in the same upload dialog or form.
     */
    public static String nativeFileTarget(String selectorLiteral) {
        return "(function(){try{"+finder()
            +"var input=deepFind("+selectorLiteral+");"
            +"if(!input||(input.type||'').toLowerCase()!=='file'||input.disabled)"
            +"return JSON.stringify({ok:false,error:'Champ fichier invalide'});"
            +"var previous=window.__chkFileReceipt;if(previous&&previous.cleanup)previous.cleanup();"
            +"var receipt={selector:"+selectorLiteral+",input:input,event_observed:false,count:0,names:[],sizes:[]};window.__chkFileReceipt=receipt;"
            +"var receiptRoot=input.getRootNode?input.getRootNode():input.ownerDocument;"
            +"function observeFile(event){if(window.__chkFileReceipt!==receipt||event.target!==input)return;var fs=Array.from(input.files||[]);receipt.event_observed=true;"
            +"if(!fs.length)return;receipt.count=fs.length;receipt.names=fs.map(function(f){return f.name;});receipt.sizes=fs.map(function(f){return f.size;});receipt.cleanup();}"
            +"receipt.cleanup=function(){receiptRoot.removeEventListener('input',observeFile,true);receiptRoot.removeEventListener('change',observeFile,true);};"
            +"receiptRoot.addEventListener('input',observeFile,true);receiptRoot.addEventListener('change',observeFile,true);"
            +"function visible(e){if(!e||!e.getBoundingClientRect)return false;"
            +"var r=e.getBoundingClientRect(),w=e.ownerDocument.defaultView||window,cs=w.getComputedStyle(e);"
            +"return r.width>4&&r.height>4&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0';}"
            +"function label(e){return [e.innerText||'',e.getAttribute('aria-label')||'',e.getAttribute('title')||'',"
            +"e.getAttribute('data-tooltip')||'',e.getAttribute('value')||''].join(' ').trim().replace(/\\s+/g,' ').slice(0,180);}"
            +"var target=visible(input)?input:null,method=target?'visible-input':'button';"
            +"if(!target){"
            +"var linked=[];try{if(input.labels)linked=Array.from(input.labels);}catch(ignore){}"
            +"if(input.closest&&input.closest('label'))linked.unshift(input.closest('label'));"
            +"target=linked.find(function(e){return visible(e);})||null;if(target)method='associated-label';"
            +"}"
            +"if(!target){"
            +"var scopes=[],p=input.parentElement;for(var depth=0;p&&depth<5;depth++,p=p.parentElement)scopes.push(p);"
            +"var dialog=input.closest&&input.closest('[role=dialog],dialog,form,ytcp-uploads-dialog');"
            +"if(dialog&&scopes.indexOf(dialog)<0)scopes.push(dialog);"
            +"var root=input.getRootNode&&input.getRootNode();"
            +"if(root&&root.host&&root.host.parentElement)scopes.push(root.host.parentElement);"
            +"var pattern=/(select files?|choose files?|browse files?|add files?|upload|upload files?|import|importer|t[eé]l[eé]verser|s[eé]lectionner|choisir|parcourir|ajouter|ajout(er)? des fichiers|pick files?|add assets?)/i;"
            +"var choices=[],seen=[];"
            +"scopes.forEach(function(scope,level){"
            +"var buttons=[];try{buttons=Array.from(scope.querySelectorAll('button,[role=button],label,[type=button],[type=submit]'));}catch(ignore){}"
            +"buttons.forEach(function(b){if(seen.indexOf(b)>=0)return;seen.push(b);if(!visible(b))return;"
            +"var name=label(b);if(!pattern.test(name))return;"
            +"var text=name.toLowerCase(),distance=level*12,score=100-distance;"
            +"if(/select files?|choose files?|browse files?|choisir|s[eé]lectionner|parcourir/.test(text))score+=40;"
            +"if(/upload|import|t[eé]l[eé]verser|add assets?/.test(text))score+=12;"
            +"if(b.getAttribute('for')===input.id&&input.id)score+=70;"
            +"if(b.contains(input))score+=65;"
            +"var controls=b.getAttribute('aria-controls')||'';if(input.id&&controls.split(/\\s+/).indexOf(input.id)>=0)score+=50;"
            +"choices.push({element:b,score:score,label:name});"
            +"});});"
            +"choices.sort(function(a,b){return b.score-a.score;});"
            +"if(choices.length>1&&choices[0].score===choices[1].score)"
            +"return JSON.stringify({ok:false,error:'Plusieurs boutons d’importation possibles : précise le dialogue ou le champ.'});"
            +"target=choices.length?choices[0].element:null;"
            +"if(!target)return JSON.stringify({ok:false,error:'Bouton de sélection des fichiers introuvable près du champ'});"
            +"}"
            +"target.scrollIntoView({block:'center',inline:'center'});"
            +"var r=target.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;"
            +"var w=target.ownerDocument.defaultView||window;"
            +"while(w!==window){try{var frame=w.frameElement;if(!frame)break;"
            +"var box=frame.getBoundingClientRect();x+=box.left;y+=box.top;w=w.parent;}catch(ignore){break;}}"
            +"var width=innerWidth,height=innerHeight;"
            +"if(!(x>0&&x<width&&y>0&&y<height))return JSON.stringify({ok:false,error:'Bouton hors écran'});"
            +"return JSON.stringify({ok:true,x:x,y:y,viewportWidth:width,viewportHeight:height,multiple:!!input.multiple,method:method});"
            +"}catch(e){return JSON.stringify({ok:false,error:String(e.message)});}})()";
    }

    public static String fileClick(String selectorLiteral) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Champ fichier introuvable';"
            +"if((el.type||'').toLowerCase()!=='file')return 'Le sélecteur ne cible pas input[type=file]';if(el.disabled)return 'Champ fichier désactivé';"
            +"el.click();return 'Sélecteur fichier ouvert';}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String fileInfo(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el||!el.files)return JSON.stringify({ok:false,count:0});"
            +"return JSON.stringify({ok:el.files.length>0,count:el.files.length,names:Array.from(el.files).map(function(f){return f.name;}),sizes:Array.from(el.files).map(function(f){return f.size;})});"
            +"}catch(e){return JSON.stringify({ok:false,error:e.message,count:0});}})()";
    }

    public static String fileReceipt(String selectorLiteral){
        return "(function(){try{"+finder()+"var input=deepFind("+selectorLiteral+"),r=window.__chkFileReceipt;"
            +"if(r&&r.selector==="+selectorLiteral+"&&r.event_observed)return JSON.stringify({ok:r.count>0,event_observed:true,count:r.count,names:r.names,sizes:r.sizes});"
            +"var f=Array.from(input&&input.files||[]);return JSON.stringify({ok:f.length>0,event_observed:false,count:f.length,names:f.map(function(x){return x.name;}),sizes:f.map(function(x){return x.size;})});"
            +"}catch(e){return JSON.stringify({ok:false,count:0});}})()";
    }

    /** Inspect forms plus modern dynamic controls outside <form> (Google Play-like wizards). */
    public static String getForm() {
        return "(function(){try{"+finder()+"var rs=roots(),forms=[],seen=[];"
            +"function selector(el){return selectorFor(el);}"
            +"rs.forEach(function(r){var list=[];try{list=Array.from(r.querySelectorAll('form'));}catch(e){}list.forEach(function(form){if(seen.indexOf(form)>=0)return;seen.push(form);"
            +"var item={action:(form.action||'').slice(0,500),method:(form.method||'get').toLowerCase(),controls:[]};"
            +"Array.from(form.querySelectorAll('input,textarea,select,button,[role=radio],[role=checkbox],[role=switch],[role=combobox]')).slice(0,25).forEach(function(el){var t=(el.type||'').toLowerCase();if(t==='password'||t==='hidden')return;"
            +"item.controls.push({selector:selector(el),tag:(el.tagName||'').toLowerCase(),type:t,value:String(el.isContentEditable?el.textContent:el.value||'').slice(0,8000),name:(el.name||'').slice(0,120),label:(el.getAttribute('aria-label')||el.placeholder||el.innerText||'').trim().slice(0,120),required:!!el.required||el.getAttribute('aria-required')==='true',invalid:el.getAttribute('aria-invalid')==='true',disabled:!!el.disabled,checked:('checked' in el)?!!el.checked:el.getAttribute('aria-checked')==='true'});});"
            +"forms.push(item);});});"
            +"var dynamic=[],errors=[],taken=[];"
            +"function describe(el){var t=(el.type||'').toLowerCase(),role=el.getAttribute('role')||'';"
            +"if(t==='password'||t==='hidden'||el.getAttribute('aria-hidden')==='true')return;"
            +"if(el.closest&&el.closest('form'))return;"
            +"if(el.getClientRects&&el.getClientRects().length===0&&t!=='file')return;"
            +"if(taken.indexOf(el)>=0)return;taken.push(el);"
            +"var name=(el.getAttribute('aria-label')||el.getAttribute('title')||el.placeholder||el.innerText||el.name||'').trim().replace(/\\s+/g,' ').slice(0,120);"
            +"var ctrl={selector:selector(el),tag:(el.tagName||'').toLowerCase(),type:t,role:role,value:String(el.isContentEditable?el.textContent:el.value||'').slice(0,8000),label:name,required:!!el.required||el.getAttribute('aria-required')==='true',invalid:el.getAttribute('aria-invalid')==='true',disabled:!!el.disabled};"
            +"if('checked' in el&&(t==='radio'||t==='checkbox'))ctrl.checked=!!el.checked;"
            +"else if(el.hasAttribute('aria-checked'))ctrl.checked=el.getAttribute('aria-checked')==='true';"
            +"if(ctrl.invalid&&errors.length<12)errors.push({label:name,role:role});"
            +"if(dynamic.length<35)dynamic.push(ctrl);}"
            +"rs.forEach(function(root){var controls=[];try{controls=Array.from(root.querySelectorAll('input,textarea,select,button,[role=radio],[role=checkbox],[role=switch],[role=combobox],[contenteditable=true],[aria-invalid=true]'));}catch(e){}"
            +"controls.forEach(describe);});"
            +"return JSON.stringify({forms:forms.slice(0,8),count:forms.length,dynamic_controls:dynamic,validation_errors:errors});"
            +"}catch(e){return JSON.stringify({forms:[],count:0,dynamic_controls:[],validation_errors:[],error:e.message});}})()";
    }

    public static String pageStatus() {
        return "(function(){try{var text=(document.body&&document.body.innerText||'').slice(0,12000);"
            +"var messages=text.split(/\\n+/).filter(function(x){return /error|erreur|failed|échec|invalid|invalide|required|obligatoire|saved|enregistr|success|réussi/i.test(x);}).slice(0,20);"
            +"return JSON.stringify({readyState:document.readyState,url:location.href,title:document.title,forms:document.forms.length,dialogs:document.querySelectorAll('[role=dialog],dialog').length,messages:messages});"
            +"}catch(e){return JSON.stringify({readyState:'unknown',error:e.message});}})()";
    }

    public static String linkUrl(String selectorLiteral) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return '';var u=el.href||el.getAttribute('data-href')||'';"
            +"if(!u&&el.closest){var a=el.closest('a');if(a)u=a.href||'';}"
            +"if(!u){var tag=(el.tagName||'').toLowerCase(),v=tag==='video'?el:((el.querySelector&&el.querySelector('video'))||null);"
            +"if(v)u=v.currentSrc||v.src||((v.querySelector('source')||{}).src)||'';"
            +"if(!u&&tag==='source')u=el.src||'';}"
            +"return u;}catch(e){return '';}})()";
    }
}
