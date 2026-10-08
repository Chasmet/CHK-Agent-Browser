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

    public static String readPage() {
        return "(function(){"+finder()
            + "var out={text:(document.body&&document.body.innerText||'').slice(0,5000),elements:[],forms:0,ready:document.readyState};"
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
            + "return JSON.stringify(out);})()";
    }

    public static String type(String selectorLiteral,String textLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");"
            + "if(!el)return 'Champ introuvable';if(['password','file','hidden'].indexOf((el.type||'').toLowerCase())>=0)return 'Champ sensible bloqué';"
            + "if(el.disabled||el.readOnly)return 'Champ non modifiable';el.focus();"
            + "if(el.isContentEditable){el.textContent="+textLiteral+";}else{if(!('value' in el))return 'Champ non saisissable';"
            + "var proto=Object.getPrototypeOf(el),setter=null;while(proto&&!setter){var desc=Object.getOwnPropertyDescriptor(proto,'value');setter=desc&&desc.set;proto=Object.getPrototypeOf(proto);}"
            + "if(setter)setter.call(el,"+textLiteral+");else el.value="+textLiteral+";}"
            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'Saisie effectuée';"
            + "}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String click(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Élément introuvable';"
            +"if(el.disabled)return 'Élément désactivé';el.scrollIntoView({block:'center',inline:'center'});el.click();return 'Clic effectué';"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String select(String selectorLiteral,String valueLiteral,String labelLiteral,int index) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Menu introuvable';"
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
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Option introuvable';"
            +"var t=(el.type||'').toLowerCase();if(t!=='checkbox'&&t!=='radio'&&el.getAttribute('role')!=='checkbox'&&el.getAttribute('role')!=='radio')return 'Élément non cochable';"
            +"var wanted="+checked+";if('checked' in el){if(!!el.checked!==wanted)el.click();return (!!el.checked===wanted)?'État confirmé':'État non confirmé';}"
            +"var now=el.getAttribute('aria-checked')==='true';if(now!==wanted)el.click();return 'État demandé';"
            +"}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String exists(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");return !!el;}catch(e){return false;}})()";
    }

    public static String fileClick(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el)return 'Champ fichier introuvable';"
            +"if((el.type||'').toLowerCase()!=='file')return 'Le sélecteur ne cible pas input[type=file]';if(el.disabled)return 'Champ fichier désactivé';"
            +"el.click();return 'Sélecteur fichier ouvert';}catch(e){return 'Erreur : '+e.message;}})()";
    }

    public static String fileInfo(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el||!el.files)return JSON.stringify({ok:false,count:0});"
            +"return JSON.stringify({ok:el.files.length>0,count:el.files.length,names:Array.from(el.files).map(function(f){return f.name;}),sizes:Array.from(el.files).map(function(f){return f.size;})});"
            +"}catch(e){return JSON.stringify({ok:false,error:e.message,count:0});}})()";
    }

    public static String getForm() {
        return "(function(){try{"+finder()+"var rs=roots(),forms=[],seen=[];"
            +"rs.forEach(function(r){var list=[];try{list=Array.from(r.querySelectorAll('form'));}catch(e){}list.forEach(function(form){if(seen.indexOf(form)>=0)return;seen.push(form);"
            +"var item={action:(form.action||'').slice(0,500),method:(form.method||'get').toLowerCase(),controls:[]};"
            +"Array.from(form.querySelectorAll('input,textarea,select,button')).slice(0,80).forEach(function(el){var t=(el.type||'').toLowerCase();if(t==='password'||t==='hidden')return;"
            +"item.controls.push({tag:(el.tagName||'').toLowerCase(),type:t,name:(el.name||'').slice(0,120),label:(el.getAttribute('aria-label')||el.placeholder||el.innerText||'').trim().slice(0,120),required:!!el.required,disabled:!!el.disabled,checked:('checked' in el)?!!el.checked:undefined});});"
            +"forms.push(item);});});return JSON.stringify({forms:forms.slice(0,20),count:forms.length});"
            +"}catch(e){return JSON.stringify({forms:[],count:0,error:e.message});}})()";
    }

    public static String pageStatus() {
        return "(function(){try{var text=(document.body&&document.body.innerText||'').slice(0,12000);"
            +"var messages=text.split(/\\n+/).filter(function(x){return /error|erreur|failed|échec|invalid|invalide|required|obligatoire|saved|enregistr|success|réussi/i.test(x);}).slice(0,20);"
            +"return JSON.stringify({readyState:document.readyState,url:location.href,title:document.title,forms:document.forms.length,dialogs:document.querySelectorAll('[role=dialog],dialog').length,messages:messages});"
            +"}catch(e){return JSON.stringify({readyState:'unknown',error:e.message});}})()";
    }

    public static String linkUrl(String selectorLiteral) {
        return "(function(){try{"+finder()+"var el=deepFind("+selectorLiteral+");if(!el)return '';var u=el.href||el.getAttribute('data-href')||'';"
            +"if(!u&&el.closest){var a=el.closest('a');if(a)u=a.href||'';}return u;}catch(e){return '';}})()";
    }
}
