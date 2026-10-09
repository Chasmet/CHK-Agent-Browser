package com.chk.agentbrowser;
import android.app.Activity;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native start and local history suggestions; result pages remain real Google pages. */
final class BrowserHome {
    interface Navigate{void open(String value);}
    private final Activity a;private final EditText address;private final BrowserStore store;private final Navigate navigate;
    private final FrameLayout home,suggestions;private boolean browser=true;
    BrowserHome(Activity a,EditText input,BrowserStore store,Navigate navigate){this.a=a;address=input;this.store=store;this.navigate=navigate;home=a.findViewById(R.id.start_page);suggestions=a.findViewById(R.id.search_suggestions);
        input.setOnFocusChangeListener((v,focus)->{if(focus&&browser)drawSuggestions();else suggestions.setVisibility(View.GONE);});
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(input.hasFocus()&&browser)drawSuggestions();}public void afterTextChanged(Editable e){}});
    }
    boolean isHome(String url){return url==null||url.equals("https://www.google.com")||url.equals("https://www.google.com/");}
    void space(boolean active,String url){browser=active;page(url);if(!active)suggestions.setVisibility(View.GONE);}
    void page(String url){home.setVisibility(browser&&isHome(url)?View.VISIBLE:View.GONE);if(home.getVisibility()==View.VISIBLE){drawHome();if(!address.hasFocus())address.setText("");}if(!isHome(url))suggestions.setVisibility(View.GONE);}
    private void drawHome(){home.removeAllViews();ScrollView scroll=new ScrollView(a);home.addView(scroll);LinearLayout content=MobileUi.column(a);content.setPadding(dp(12),dp(35),dp(12),dp(28));scroll.addView(content);
        TextView brand=MobileUi.text(a,"CHK",52,MobileUi.TEXT);brand.setGravity(Gravity.CENTER);brand.setTypeface(null,android.graphics.Typeface.BOLD);content.addView(brand);TextView subtitle=MobileUi.text(a,"Ton navigateur. Ton espace.",14,0xffbdc1c6);subtitle.setGravity(Gravity.CENTER);content.addView(subtitle);
        TextView search=MobileUi.action(a,"⌕   Rechercher sur Google");search.setTextColor(0xffbdc1c6);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(58));sp.setMargins(dp(6),dp(20),dp(6),dp(22));content.addView(search,sp);search.setOnClickListener(v->{address.requestFocus();((InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(address,InputMethodManager.SHOW_IMPLICIT);});
        String[] names={"Google","YouTube","GitHub","ChatGPT"},urls={"https://www.google.com","https://www.youtube.com","https://github.com","https://chatgpt.com"};LinearLayout quick=new LinearLayout(a);content.addView(quick);for(int i=0;i<names.length;i++){String target=urls[i];TextView v=MobileUi.text(a,names[i].substring(0,1)+"\n"+names[i],13,MobileUi.TEXT);v.setGravity(Gravity.CENTER);v.setPadding(dp(2),dp(8),dp(2),dp(8));v.setMaxLines(2);v.setTextSize(12);quick.addView(v,new LinearLayout.LayoutParams(0,dp(76),1));v.setOnClickListener(w->navigate.open(target));}
        JSONArray favorites=store.get("favorites");if(favorites.length()>0){content.addView(MobileUi.text(a,"Favoris",17,MobileUi.TEXT));for(int i=0;i<Math.min(6,favorites.length());i++){JSONObject item=favorites.optJSONObject(i);if(item!=null)link(content,"★  "+item.optString("title"),item.optString("url"));}}
        JSONArray history=store.get("history");LinkedHashSet<String> seen=new LinkedHashSet<>();int count=0;for(int i=0;i<history.length()&&count<6;i++){JSONObject item=history.optJSONObject(i);if(item==null||isHome(item.optString("url"))||!seen.add(item.optString("url")))continue;if(count++==0)content.addView(MobileUi.text(a,"Reprendre la navigation",17,MobileUi.TEXT));link(content,"◷  "+item.optString("title"),item.optString("url"));}
    }
    private String displayTitle(JSONObject item){String url=item.optString("url");try{android.net.Uri parsed=android.net.Uri.parse(url);String host=parsed.getHost(),query=parsed.getQueryParameter("q");if(host!=null&&(host.equals("google.com")||host.endsWith(".google.com"))&&query!=null&&!query.isEmpty())return query;}catch(Exception ignored){}return item.optString("title",url);}
    private int dp(int value){return MobileUi.dp(a,value);}
    private void link(LinearLayout parent,String label,String url){TextView v=MobileUi.text(a,label,14,MobileUi.TEXT);v.setMinHeight(dp(52));v.setMaxLines(2);parent.addView(v);v.setOnClickListener(w->navigate.open(url));}
    private void drawSuggestions(){suggestions.removeAllViews();suggestions.setVisibility(View.VISIBLE);ScrollView sc=new ScrollView(a);suggestions.addView(sc);LinearLayout rows=MobileUi.column(a);sc.addView(rows);String query=address.getText().toString().trim();if(!query.isEmpty())link(rows,"⌕   Rechercher « "+query+" »",query);
        JSONArray history=store.get("history");int added=0;for(int i=0;i<history.length()&&added<10;i++){JSONObject item=history.optJSONObject(i);if(item==null)continue;String title=displayTitle(item),url=item.optString("url");if(!isHome(url)&&(query.isEmpty()||(title+url).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))){link(rows,"◷   "+title,url);added++;}}
        if(added==0)rows.addView(MobileUi.text(a,"Les pages visitées apparaîtront ici.",14,MobileUi.MUTED));TextView close=MobileUi.action(a,"Fermer la recherche");rows.addView(close);close.setOnClickListener(v->{address.clearFocus();closeSuggestions();((InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);});
    }
    boolean closeSuggestions(){boolean open=suggestions.getVisibility()==View.VISIBLE;suggestions.setVisibility(View.GONE);return open;}
    void destroy(){suggestions.removeAllViews();home.removeAllViews();}
}
