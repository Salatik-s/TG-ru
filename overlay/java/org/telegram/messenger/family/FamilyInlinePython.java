package org.telegram.messenger.family;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;

/** Native child below a message. The pinned smali wrappers reserve its height. */
public final class FamilyInlinePython {
    private FamilyInlinePython(){}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    // Values must not retain the key, its Context, or its Panel. Cells without a
    // panel must also be observed so enabling the plugin updates the current row.
    private static final WeakHashMap<ViewGroup,Boolean> CELLS=new WeakHashMap<>();
    private static SharedPreferences settings;
    private static final Runnable REFRESH_CELLS=()->{
        boolean enabled=settings!=null&&settings.getBoolean("python",false);
        List<ViewGroup> visible;
        synchronized(CELLS){visible=new ArrayList<>(CELLS.keySet());}
        for(ViewGroup cell:visible){if(cell==null)continue;if(!enabled){Panel panel=existing(cell);if(panel!=null){panel.release(true);panel.setVisibility(View.GONE);}}cell.requestLayout();cell.invalidate();}
        if(!enabled)FamilyPythonSnippets.clear();
    };
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTINGS_LISTENER=(preferences,key)->{
        if(!"python".equals(key))return;
        MAIN.removeCallbacks(REFRESH_CELLS);MAIN.post(REFRESH_CELLS);
    };
    private static void observe(ViewGroup cell){
        synchronized(CELLS){
            if(!CELLS.containsKey(cell)&&CELLS.size()>=256){
                Iterator<ViewGroup> iterator=CELLS.keySet().iterator();
                while(iterator.hasNext()){ViewGroup old=iterator.next();if(old==null||!old.isAttachedToWindow()){iterator.remove();if(CELLS.size()<256)break;}}
                if(CELLS.size()>=256){iterator=CELLS.keySet().iterator();if(iterator.hasNext()){iterator.next();iterator.remove();}}
            }
            CELLS.put(cell,Boolean.TRUE);
            if(settings==null){Context application=cell.getContext().getApplicationContext();if(application!=null){settings=application.getSharedPreferences("saa_builtin_plugins_v1",0);settings.registerOnSharedPreferenceChangeListener(SETTINGS_LISTENER);}}
        }
    }
    private static Panel existing(ViewGroup cell){for(int i=0;i<cell.getChildCount();i++)if(cell.getChildAt(i) instanceof Panel)return (Panel)cell.getChildAt(i);return null;}
    private static List<FamilyPythonSnippets.Snippet> snippets(ViewGroup cell,Object message){try{if(!FamilyChatTools.enabled(cell.getContext(),"python"))return Collections.emptyList();int account=((Number)FamilyPythonSnippets.field(cell,"currentAccount")).intValue();Class<?> config=Class.forName("org.telegram.messenger.UserConfig");Object instance=config.getMethod("getInstance",int.class).invoke(null,account);long owner=((Number)config.getMethod("getClientUserId").invoke(instance)).longValue();if(owner==0)return Collections.emptyList();return FamilyPythonSnippets.extract(message,account,account+":"+owner);}catch(Exception ignored){return Collections.emptyList();}}
    private static Object message(ViewGroup cell){try{return cell.getClass().getMethod("getMessageObject").invoke(cell);}catch(Exception ignored){return null;}}
    /** Context-menu execution uses the exact visible native panel, never an editor Activity. */
    public static boolean runFromChat(Object chat,Object requestedMessage){
        if(Looper.myLooper()!=Looper.getMainLooper()||!FamilyAccess.isAllowed())return false;
        try{
            int account=((Number)chat.getClass().getMethod("getCurrentAccount").invoke(chat)).intValue();
            long dialog=((Number)chat.getClass().getMethod("getDialogId").invoke(chat)).longValue();
            int id=((Number)requestedMessage.getClass().getMethod("getId").invoke(requestedMessage)).intValue();
            if(!messageMatches(requestedMessage,account,dialog,id))return false;
            Object fragment=chat.getClass().getMethod("getFragmentView").invoke(chat);if(!(fragment instanceof View))return false;
            View root=(View)fragment;if(!root.isAttachedToWindow()||!root.isShown())return false;
            Class<?> cellType=Class.forName("org.telegram.ui.Cells.ChatMessageCell");
            ArrayList<View> pending=new ArrayList<>();pending.add(root);Panel found=null;int visited=0;
            while(!pending.isEmpty()){
                if(++visited>4096)return false;
                View view=pending.remove(pending.size()-1);
                if(!view.isShown()||!view.isAttachedToWindow())continue;
                if(cellType.isInstance(view)&&view instanceof ViewGroup){
                    ViewGroup cell=(ViewGroup)view;
                    if(integer(cell,"currentAccount",-1)==account&&messageMatches(message(cell),account,dialog,id)){
                        Panel panel=existing(cell);
                        if(panel!=null&&panel.isShown()){if(found!=null)return false;found=panel;}
                    }
                    continue;
                }
                if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)pending.add(group.getChildAt(i));}
            }
            if(found==null)return false;
            List<FamilyPythonSnippets.Snippet> selected=snippets(found.cell,requestedMessage);
            if(selected.isEmpty()||!sameKeys(selected,snippets(found.cell,message(found.cell))))return false;
            found.bind(selected);
            return found.runSelection(root,account,dialog,id,new ArrayList<>(selected));
        }catch(Exception ignored){return false;}
    }
    static boolean messageMatches(Object message,int account,long dialog,int id){
        try{return account>=0&&dialog!=0&&id!=0&&((Number)FamilyPythonSnippets.field(message,"currentAccount")).intValue()==account&&((Number)message.getClass().getMethod("getDialogId").invoke(message)).longValue()==dialog&&((Number)message.getClass().getMethod("getId").invoke(message)).intValue()==id;}catch(Exception ignored){return false;}
    }
    static boolean sameKeys(List<FamilyPythonSnippets.Snippet> first,List<FamilyPythonSnippets.Snippet> second){if(first.size()!=second.size())return false;for(int i=0;i<first.size();i++)if(!first.get(i).key.equals(second.get(i).key))return false;return true;}
    private static boolean inside(View child,View root){for(int i=0;child!=null&&i<128;i++){if(child==root)return true;android.view.ViewParent parent=child.getParent();child=parent instanceof View?(View)parent:null;}return false;}
    public static void beforeBind(ViewGroup cell,Object message){Panel panel=existing(cell);if(panel!=null)panel.bind(snippets(cell,message));}
    /** Called after the ORIGINAL onMeasure, whose base padding has been reset. */
    public static int measure(ViewGroup cell){
        observe(cell);
        List<FamilyPythonSnippets.Snippet> blocks=snippets(cell,message(cell));Panel panel=existing(cell);
        if(blocks.isEmpty()){if(panel!=null){panel.bind(blocks);panel.setVisibility(View.GONE);}return 0;}
        if(panel==null){panel=new Panel(cell);cell.addView(panel,new ViewGroup.LayoutParams(-1,-2));}
        panel.bind(blocks);panel.setVisibility(View.VISIBLE);
        // Short source code must not squeeze a much wider result into its bubble width.
        int width=Math.max(1,cell.getMeasuredWidth()-dp(cell,32));
        panel.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        panel.extra=panel.getMeasuredHeight()+dp(cell,8);return panel.extra;
    }
    public static void layout(ViewGroup cell){Panel panel=existing(cell);if(panel==null||panel.getVisibility()!=View.VISIBLE)return;int left=dp(cell,8);try{left=((Number)cell.getClass().getMethod("getBackgroundDrawableLeft").invoke(cell)).intValue();}catch(Exception ignored){}left=Math.max(dp(cell,4),Math.min(left,cell.getMeasuredWidth()-panel.getMeasuredWidth()-dp(cell,4)));int top=cell.getMeasuredHeight()-panel.extra;panel.layout(left,top,left+panel.getMeasuredWidth(),top+panel.getMeasuredHeight());}
    public static void detach(ViewGroup cell){Panel panel=existing(cell);if(panel!=null)panel.release(true);}
    private static int integer(Object value,String name,int fallback){try{return ((Number)FamilyPythonSnippets.field(value,name)).intValue();}catch(Exception ignored){return fallback;}}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
    private static int color(String name,int fallback){try{Class<?> theme=Class.forName("org.telegram.ui.ActionBar.Theme");return (Integer)theme.getMethod("getColor",int.class).invoke(null,theme.getField(name).getInt(null));}catch(Exception ignored){return fallback;}}
    private static String label(Context c,String name,String fallback){int id=c.getResources().getIdentifier(name,"string",c.getPackageName());return id==0?fallback:c.getString(id);}
    private static final class Panel extends LinearLayout implements SharedPreferences.OnSharedPreferenceChangeListener {
        final ViewGroup cell;List<FamilyPythonSnippets.Snippet> blocks=Collections.emptyList();final LinkedHashMap<String,Button> runButtons=new LinkedHashMap<>();FamilyPythonRunner runner;String runningKey;int extra;boolean listening,needsRender;AlertDialog expanded,chooser;
        Panel(ViewGroup cell){super(cell.getContext());this.cell=cell;setOrientation(VERTICAL);setPadding(dp(this,10),dp(this,6),dp(this,10),dp(this,6));setClipChildren(true);}
        void bind(List<FamilyPythonSnippets.Snippet> next){boolean same=next.size()==blocks.size();if(same)for(int i=0;i<next.size();i++)if(!next.get(i).key.equals(blocks.get(i).key)){same=false;break;}if(same)return;release(true);blocks=next;removeAllViews();render();}
        void render(){
            needsRender=false;
            runButtons.clear();
            if(blocks.isEmpty())return;
            int ink=color("key_chat_messageTextIn",0xff202020),surface=color("key_chat_inBubble",0xfff0f2f6);
            GradientDrawable background=new GradientDrawable();background.setColor(surface);background.setCornerRadius(dp(this,10));setBackground(background);
            for(int index=0;index<blocks.size();index++){
                FamilyPythonSnippets.Snippet snippet=blocks.get(index);
                LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setOrientation(HORIZONTAL);
                TextView title=new TextView(getContext());title.setText(blocks.size()==1?"Python":"Python · "+(index+1));title.setTextColor(ink);title.setTextSize(14);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                Button button=new Button(getContext());button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(dp(this,48));button.setMinimumHeight(dp(this,48));button.setMinWidth(0);button.setMinimumWidth(0);button.setText(label(getContext(),"fg_python_run","Запустить"));row.addView(button,new LinearLayout.LayoutParams(-2,dp(this,48)));addView(row,new LinearLayout.LayoutParams(-1,-2));
                runButtons.put(snippet.key,button);
                TextView output=new TextView(getContext());output.setTypeface(Typeface.MONOSPACE);output.setTextColor(ink);output.setTextSize(15);output.setSingleLine(false);output.setHorizontallyScrolling(false);output.setTextIsSelectable(true);output.setPadding(0,dp(this,4),0,dp(this,4));output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                ScrollView scroll=new OutputScroll(getContext());scroll.setFillViewport(false);scroll.addView(output,new ScrollView.LayoutParams(-1,-2));addView(scroll,new LinearLayout.LayoutParams(-1,-2));
                Button expand=new Button(getContext());expand.setAllCaps(false);expand.setTextSize(14);expand.setText("Развернуть вывод");expand.setMinHeight(dp(this,48));expand.setMinimumHeight(dp(this,48));expand.setVisibility(GONE);addView(expand,new LinearLayout.LayoutParams(-1,dp(this,48)));
                expand.setOnClickListener(v->{if(contains(snippet.key)&&FamilyChatTools.enabled(getContext(),"python"))showOutput(output.getText().toString(),ink);});
                output.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateExpand(expand,output));
                String cached=FamilyPythonSnippets.result(snippet.key);if(cached!=null)output.setText(cached);else if(FamilyPythonSnippets.changed(snippet))output.setText("Код изменён. Запустите снова.");else scroll.setVisibility(GONE);
                updateExpand(expand,output);
                button.setOnClickListener(v->{
                    if(!fresh(snippet.key))return;
                    if(runner!=null&&runner.running()){boolean same=snippet.key.equals(runningKey);runner.cancel();if(same)return;}
                    if(runner!=null)runner.destroy();
                    runningKey=snippet.key;scroll.setVisibility(VISIBLE);
                    runner=new FamilyPythonRunner(this,(busy,text)->{
                        if(!snippet.key.equals(runningKey)||!contains(snippet.key))return;
                        output.setText(text);button.setText(label(getContext(),busy?"fg_python_stop":"fg_python_run",busy?"Остановить":"Запустить"));
                        updateExpand(expand,output);
                        if(!busy)FamilyPythonSnippets.save(snippet.key,text);
                        requestLayout();cell.requestLayout();
                    });runner.run(snippet.code);
                });
            }
        }
        boolean fresh(String key){if(!isAttachedToWindow()||!isShown()||existing(cell)!=this||!FamilyChatTools.enabled(getContext(),"python")||!FamilyAccess.isAllowed())return false;for(FamilyPythonSnippets.Snippet snippet:snippets(cell,message(cell)))if(snippet.key.equals(key))return contains(key);return false;}
        boolean clickSelection(View root,int account,long dialog,int id,List<FamilyPythonSnippets.Snippet> snapshot,int index){
            if(index<0||index>=snapshot.size()||!inside(cell,root)||!root.isShown()||!root.isAttachedToWindow()||integer(cell,"currentAccount",-1)!=account||!messageMatches(message(cell),account,dialog,id))return false;
            List<FamilyPythonSnippets.Snippet> current=snippets(cell,message(cell));if(!sameKeys(snapshot,current))return false;
            bind(current);String key=snapshot.get(index).key;Button button=runButtons.get(key);
            if(button==null||!fresh(key)||!button.isShown()||!button.isEnabled())return false;
            return button.performClick();
        }
        boolean runSelection(View root,int account,long dialog,int id,List<FamilyPythonSnippets.Snippet> snapshot){
            if(snapshot.size()==1)return clickSelection(root,account,dialog,id,snapshot,0);
            if(chooser!=null)chooser.dismiss();
            String[] labels=new String[snapshot.size()];for(int i=0;i<labels.length;i++)labels[i]="Блок Python · "+(i+1);
            chooser=new AlertDialog.Builder(getContext()).setTitle("Какой блок запустить?").setItems(labels,(dialogInterface,index)->{if(!clickSelection(root,account,dialog,id,snapshot,index))Toast.makeText(getContext(),"Сообщение изменилось или больше не видно. Откройте его снова.",Toast.LENGTH_SHORT).show();}).setNegativeButton(android.R.string.cancel,null).create();
            chooser.show();return true;
        }
        void updateExpand(Button expand,TextView output){String text=output.getText().toString();int lines=1;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')lines++;int visibility=text.length()>1000||lines>12||output.getLineCount()>12?VISIBLE:GONE;if(expand.getVisibility()!=visibility){expand.setVisibility(visibility);cell.requestLayout();}}
        void showOutput(String text,int ink){
            if(expanded!=null)expanded.dismiss();
            int brightness=(((ink>>16)&255)+((ink>>8)&255)+(ink&255))/3;
            int theme=brightness>128?android.R.style.Theme_DeviceDefault_Dialog_Alert:android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            AlertDialog.Builder builder=new AlertDialog.Builder(getContext(),theme);
            Context themed=builder.getContext();
            TextView full=new TextView(themed);full.setText(text);full.setTextSize(15);full.setTypeface(Typeface.MONOSPACE);full.setTextIsSelectable(true);full.setSingleLine(false);full.setHorizontallyScrolling(false);full.setPadding(dp(this,16),dp(this,8),dp(this,16),dp(this,12));
            ScrollView scroll=new OutputScroll(themed){@Override protected void onMeasure(int width,int height){super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(Math.max(dp(this,120),screenHeight()*2/3),View.MeasureSpec.AT_MOST));}};
            scroll.addView(full,new ScrollView.LayoutParams(-1,-2));
            expanded=builder.setTitle("Вывод Python").setView(scroll).setPositiveButton("Закрыть",null).setNeutralButton("Копировать",null).create();
            expanded.setOnShowListener(dialog->{AlertDialog current=expanded;if(current==null)return;current.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{ClipboardManager clipboard=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("Python",text));});if(current.getWindow()!=null)current.getWindow().setLayout(Math.max(dp(this,160),cell.getRootView().getWidth()-dp(this,32)),ViewGroup.LayoutParams.WRAP_CONTENT);});
            expanded.show();
        }
        int screenHeight(){int height=cell.getRootView().getHeight();return height>0?height:getResources().getDisplayMetrics().heightPixels;}
        boolean contains(String key){for(FamilyPythonSnippets.Snippet snippet:blocks)if(snippet.key.equals(key))return true;return false;}
        void release(boolean cancelled){if(chooser!=null){chooser.dismiss();chooser=null;}if(expanded!=null){expanded.dismiss();expanded=null;}if(runner!=null){if(cancelled&&runner.running()&&runningKey!=null){FamilyPythonSnippets.save(runningKey,label(getContext(),"fg_python_cancelled","Выполнение остановлено."));needsRender=true;}runner.destroy();runner=null;}runningKey=null;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            // Bound each output viewport; the full 64KiB stays selectable/scrollable.
            int limit=Math.max(dp(this,96),Math.min(dp(this,360),screenHeight()/2));
            for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child instanceof ScrollView){child.getLayoutParams().height=-2;child.measure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));child.getLayoutParams().height=Math.min(limit,child.getMeasuredHeight());}}
            super.onMeasure(widthSpec,heightSpec);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(needsRender){removeAllViews();render();}if(!listening){getContext().getSharedPreferences("saa_builtin_plugins_v1",0).registerOnSharedPreferenceChangeListener(this);listening=true;}}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)release(true);else if(needsRender){removeAllViews();render();cell.requestLayout();}}
        @Override protected void onDetachedFromWindow(){release(true);if(listening){getContext().getSharedPreferences("saa_builtin_plugins_v1",0).unregisterOnSharedPreferenceChangeListener(this);listening=false;}super.onDetachedFromWindow();}
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){if(!"python".equals(key))return;if(!prefs.getBoolean("python",false)){release(true);FamilyPythonSnippets.clear();setVisibility(GONE);}cell.requestLayout();}
    }
    /** Short output does not become an independent drag surface over the chat. */
    private static class OutputScroll extends ScrollView {
        OutputScroll(Context context){super(context);setFillViewport(false);}
        private boolean scrollable(){return canScrollVertically(-1)||canScrollVertically(1);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){return scrollable()&&super.onInterceptTouchEvent(event);}
        @Override public boolean onTouchEvent(MotionEvent event){return scrollable()&&super.onTouchEvent(event);}
    }
}
