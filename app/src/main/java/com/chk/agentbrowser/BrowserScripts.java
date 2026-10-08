package com.chk.agentbrowser;

/** Browser scripts shared by both WebViews. Arguments are JSON-quoted literals. */
public final class BrowserScripts {
    private BrowserScripts() {}
    public static String readPage() {
        return "(function(){var out={text:(document.body&&document.body.innerText||'').slice(0,5000),elements:[]};"
            + "var nodes=document.querySelectorAll('input,textarea,select,button,a,[role=button],[contenteditable=true]');"
            + "for(var i=0;i<nodes.length&&out.elements.length<24;i++){var el=nodes[i];"
            + "if(!el.getClientRects().length||['password','hidden'].indexOf((el.type||'').toLowerCase())>=0)continue;"
            + "var path='',node=el;while(node&&node.nodeType===1){var idx=1,sib=node.previousElementSibling;"
            + "while(sib){if(sib.tagName===node.tagName)idx++;sib=sib.previousElementSibling;}"
            + "path=node.tagName.toLowerCase()+':nth-of-type('+idx+')'+(path?'>'+path:'');node=node.parentElement;}"
            + "if(path.length>340)continue;out.elements.push({selector:path,tag:el.tagName.toLowerCase(),type:el.type||'',"
            + "label:(el.getAttribute('aria-label')||el.placeholder||el.innerText||el.name||'').slice(0,90)});if(JSON.stringify(out).length>9400){out.elements.pop();break;} }"
            + "return JSON.stringify(out);})()";
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
