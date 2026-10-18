package com.chk.agentbrowser;

/** Browser scripts shared by visible and background WebViews. Arguments are JSON-quoted literals. */
public final class BrowserScripts {
    private BrowserScripts() {}

    private static String finder() {
        return "function roots(){var out=[document],seen=[];function scan(root){if(!root||seen.indexOf(root)>=0)return;seen.push(root);"
            +"var all=[];try{all=Array.from(root.querySelectorAll('*'));}catch(e){}"
            +"all.forEach(function(el){try{if(el.shadowRoot){out.push(el.shadowRoot);scan(el.shadowRoot);}}catch(e){}"
            +"if((el.tagName||'').toLowerCase()==='iframe'){try{var d=el.contentDocument;if(d){out.push(d);scan(d);}}catch(e){}}});}"
            +"scan(document);return out;}"
            +"function deepFind(sel){var rs=roots();for(var i=0;i<rs.length;i++){try{var e=rs[i].querySelector(sel);if(e)return e;}catch(x){}}return null;}";
    }

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
            + "function selector(el){try{if(el.id&&typeof CSS!=='undefined'&&CSS.escape)return '#'+CSS.escape(el.id);}catch(e){}"
            + "var parts=[],node=el;while(node&&node.nodeType===1&&parts.length<7){var idx=1,sib=node.previousElementSibling;while(sib){if(sib.tagName===node.tagName)idx++;sib=sib.previousElementSibling;}"
            + "parts.unshift(node.tagName.toLowerCase()+':nth-of-type('+idx+')');node=node.parentElement;}return parts.join('>');}"
            + "for(var i=0;i<nodes.length&&out.elements.length<40;i++){var el=nodes[i];"
            + "if(['password','hidden'].indexOf((el.type||'').toLowerCase())>=0)continue;"
            + "var visible=true;try{visible=!!el.getClientRects().length||((el.type||'').toLowerCase()==='file');}catch(e){}if(!visible)continue;"
            + "var path=selector(el);if(!path||path.length>340)continue;var item={selector:path,tag:(el.tagName||'').toLowerCase(),type:el.type||'',label:label(el),disabled:!!el.disabled};"
            + "if((el.type||'').toLowerCase()==='checkbox'||(el.type||'').toLowerCase()==='radio')item.checked=!!el.checked;"
            + "if((el.type||'').toLowerCase()==='file')item.multiple=!!el.multiple;"
            + "out.elements.push(item);if(JSON.stringify(out).length>9400){out.elements.pop();break;}}"
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

    public static String click(String selectorLiteral) {
        return "(function(){try{"+finder()+highlight()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Élément introuvable';"
            +"if(el.disabled)return 'Élément désactivé';el.scrollIntoView({block:'center',inline:'center'});mark(el);el.click();return 'Clic effectué';"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
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

    /** Inspect forms plus modern dynamic controls outside <form> (Google Play-like wizards). */
    public static String getForm() {
        return "(function(){try{"+finder()+"var rs=roots(),forms=[],seen=[];"
            +"rs.forEach(function(r){var list=[];try{list=Array.from(r.querySelectorAll('form'));}catch(e){}list.forEach(function(form){if(seen.indexOf(form)>=0)return;seen.push(form);"
            +"var item={action:(form.action||'').slice(0,500),method:(form.method||'get').toLowerCase(),controls:[]};"
            +"Array.from(form.querySelectorAll('input,textarea,select,button,[role=radio],[role=checkbox],[role=switch],[role=combobox]')).slice(0,80).forEach(function(el){var t=(el.type||'').toLowerCase();if(t==='password'||t==='hidden')return;"
            +"item.controls.push({tag:(el.tagName||'').toLowerCase(),type:t,name:(el.name||'').slice(0,120),label:(el.getAttribute('aria-label')||el.placeholder||el.innerText||'').trim().slice(0,120),required:!!el.required||el.getAttribute('aria-required')==='true',invalid:el.getAttribute('aria-invalid')==='true',disabled:!!el.disabled,checked:('checked' in el)?!!el.checked:el.getAttribute('aria-checked')==='true'});});"
            +"forms.push(item);});});"
            +"var dynamic=[],errors=[],taken=[];"
            +"function describe(el){var t=(el.type||'').toLowerCase(),role=el.getAttribute('role')||'';"
            +"if(t==='password'||t==='hidden'||el.getAttribute('aria-hidden')==='true')return;"
            +"if(el.closest&&el.closest('form'))return;"
            +"if(el.getClientRects&&el.getClientRects().length===0&&t!=='file')return;"
            +"if(taken.indexOf(el)>=0)return;taken.push(el);"
            +"var name=(el.getAttribute('aria-label')||el.getAttribute('title')||el.placeholder||el.innerText||el.name||'').trim().replace(/\\s+/g,' ').slice(0,120);"
            +"var ctrl={tag:(el.tagName||'').toLowerCase(),type:t,role:role,label:name,required:!!el.required||el.getAttribute('aria-required')==='true',invalid:el.getAttribute('aria-invalid')==='true',disabled:!!el.disabled};"
            +"if('checked' in el&&(t==='radio'||t==='checkbox'))ctrl.checked=!!el.checked;"
            +"else if(el.hasAttribute('aria-checked'))ctrl.checked=el.getAttribute('aria-checked')==='true';"
            +"if(el.id&&typeof CSS!=='undefined'&&CSS.escape)ctrl.selector='#'+CSS.escape(el.id);"
            +"if(ctrl.invalid&&errors.length<12)errors.push({label:name,role:role});"
            +"if(dynamic.length<55)dynamic.push(ctrl);}"
            +"rs.forEach(function(root){var controls=[];try{controls=Array.from(root.querySelectorAll('input,textarea,select,button,[role=radio],[role=checkbox],[role=switch],[role=combobox],[contenteditable=true],[aria-invalid=true]'));}catch(e){}"
            +"controls.forEach(describe);});"
            +"return JSON.stringify({forms:forms.slice(0,20),count:forms.length,dynamic_controls:dynamic,validation_errors:errors});"
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
