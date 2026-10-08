package com.chk.agentbrowser;

/** Browser scripts shared by both WebViews. Arguments are JSON-quoted literals. */
public final class BrowserScripts {
    private BrowserScripts() {}
    public static String readPage() {
        return "(function(){"
            + "var out={text:(document.body&&document.body.innerText||'').slice(0,5000),elements:[]};"
            + "var nodes=Array.from(document.querySelectorAll('input,textarea,select,button,a,[role=button],[contenteditable=true],[tabindex=\"0\"]'));"
            + "function label(el){return (el.getAttribute('aria-label')||el.placeholder||el.innerText||el.name||'').trim().slice(0,100);}"
            + "function score(el){var tag=(el.tagName||'').toLowerCase(),v=label(el).toLowerCase();"
            + "if(v.indexOf('add assets')>=0||v.indexOf('add from drive')>=0||v.indexOf('upload')>=0||v.indexOf('save as draft')>=0)return 140;"
            + "if(tag==='input'||tag==='textarea'||tag==='select')return 120;"
            + "if(v.indexOf('icon')>=0||v.indexOf('screenshot')>=0||v.indexOf('description')>=0)return 100;"
            + "if(el.closest&&el.closest('header,nav,footer'))return 0;"
            + "if(tag==='a')return 10;return 30;}"
            + "nodes.sort(function(a,b){return score(b)-score(a);});"
            + "function selector(el){var parts=[],node=el;"
            + "while(node&&node.nodeType===1){if(node.id){var byId='[id='+JSON.stringify(node.id)+']'+(parts.length?'>'+parts.join('>'):'');"
            + "try{if(document.querySelectorAll(byId).length===1)return byId;}catch(e){}}"
            + "var idx=1,sib=node.previousElementSibling;"
            + "while(sib){if(sib.tagName===node.tagName)idx++;sib=sib.previousElementSibling;}"
            + "parts.unshift(node.tagName.toLowerCase()+':nth-of-type('+idx+')');"
            + "var candidate=parts.join('>');if(candidate.length>338)break;"
            + "try{if(document.querySelectorAll(candidate).length===1)return candidate;}catch(e){}"
            + "node=node.parentElement;}"
            + "return parts.join('>');}"
            + "for(var i=0;i<nodes.length&&out.elements.length<28;i++){var el=nodes[i];"
            + "if(!el.getClientRects().length||['password','hidden'].indexOf((el.type||'').toLowerCase())>=0)continue;"
            + "var path=selector(el);if(path.length>340)continue;"
            + "var item={selector:path,tag:el.tagName.toLowerCase(),type:el.type||'',label:label(el)};"
            + "out.elements.push(item);if(JSON.stringify(out).length>9400){out.elements.pop();break;}}"
            + "return JSON.stringify(out);"
            + "})()";
    }
    public static String type(String selectorLiteral,String textLiteral) {
        return "(function(){try{var el=document.querySelector("+selectorLiteral+");"
            + "if(!el)return 'Champ introuvable';"
            + "if(['password','file','hidden'].indexOf((el.type||'').toLowerCase())>=0)return 'Champ sensible bloqué';"
            + "if(el.disabled||el.readOnly)return 'Champ non modifiable';el.focus();"
            + "if(el.isContentEditable){el.textContent="+textLiteral+";}else{"
            + "if(!('value' in el))return 'Champ non saisissable';"
            + "var proto=Object.getPrototypeOf(el),setter=null;while(proto&&!setter){"
            + "var desc=Object.getOwnPropertyDescriptor(proto,'value');setter=desc&&desc.set;proto=Object.getPrototypeOf(proto);}"
            + "if(setter)setter.call(el,"+textLiteral+");else el.value="+textLiteral+";}"
            + "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
            + "return 'Saisie effectuée';}catch(e){return 'Erreur : '+e.message;}})()";
    }
}
