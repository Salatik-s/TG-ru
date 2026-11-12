package org.telegram.messenger.family;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.telegram.messenger.R;

/** Built-in tools and speech controls share the same ordinary settings rows as privacy. */
public final class FamilyPluginsActivity extends FamilySettingsPage {
    private final String[] tags={"ru-RU","en-US","zh-CN"};
    private final String[] names={"Русский","English","中文（普通话）"};
    private final TextView[] languages=new TextView[3];
    private FamilySystemSpeech.Session inspection, downloadSession;
    private boolean downloading;
    private TextView providerRow;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setTitle(R.string.fg_plugins);createSettingsLayout(R.string.fg_plugins);
        item("python",R.string.fg_python_runner,R.string.fg_python_summary);divider();
        item("documents",R.string.fg_documents_title,R.string.fg_documents_summary);
        note("Встроенные инструменты работают прямо в чате. Код запускается только после нажатия «Запустить».");
        providerRow=actionRow("Расшифровка голоса и видеосообщений",providerName(),v->chooseProvider());
        note("Автоматически: Telegram Premium, если он доступен для аккаунта; иначе — локальное распознавание Android. Локальная запись не отправляется на наш сервер.");
        if (!FamilySystemSpeech.isFileProviderVerified(this)) note("Расшифровка файлов на этой версии системной службы пока не поддерживается приложением. Установка языковой модели сама по себе это не исправит. Telegram Premium работает независимо от локальной службы.");
        for(int i=0;i<tags.length;i++){final int index=i;if(i>0)divider();languages[i]=actionRow(names[i],"Проверить язык и выбрать",v->inspect(index));}
        note("Выберите язык, чтобы проверить модель и запросить её загрузку у Android. Доступность зависит от телефона и системной службы распознавания. Установка языка не добавляет поддержку расшифровки файлов, если её нет у службы.");
        actionRow("Другие плагины","Управление установленными плагинами",v->{Intent intent=new Intent();intent.setClassName(getPackageName(),"org.telegram.ui.LaunchActivity");intent.putExtra("family_screen","external_plugins");intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);finish();});
        refreshLanguageLabels();
    }
    private void item(String id,int title,int description){
        Switch toggle=toggle(title,description);toggle.setChecked(FamilyChatTools.enabled(this,id));
        toggle.setOnCheckedChangeListener((view,checked)->{FamilyChatTools.setEnabled(this,id,checked);refreshRow(toggle);});
    }
    private Object provider() throws Exception {
        Class<?> type=Class.forName("xyz.nextalone.nagram.NaConfig");return type.getMethod("getTranscribeProvider").invoke(type.getField("INSTANCE").get(null));
    }
    private int providerValue(){try{Object item=provider();return ((Number)item.getClass().getMethod("Int").invoke(item)).intValue();}catch(Exception e){return -1;}}
    private String providerName(){int v=providerValue();return v==0?"Автоматически":v==1?"Telegram Premium":v<0?"Настройка недоступна":"На устройстве";}
    private void chooseProvider(){
        String[] values={"Автоматически","Telegram Premium","На устройстве"};int current=providerValue();
        new AlertDialog.Builder(this,FamilyNotificationSetup.dialogTheme(this)).setTitle("Способ расшифровки")
          .setSingleChoiceItems(values,current<0?-1:Math.min(current,2),(dialog,index)->{
            try{Object item=provider();item.getClass().getMethod("setConfigInt",int.class).invoke(item,index);providerRow.setText(providerName());}
            catch(Exception e){toast("Не удалось сохранить настройку");}dialog.dismiss();
          }).setNegativeButton("Отмена",null).show();
    }
    private void refreshLanguageLabels(){String selected=getSharedPreferences("saa_system_speech_v1",0).getString("language","ru-RU");for(int i=0;i<tags.length;i++)languages[i].setText(tags[i].equals(selected)?"Выбран • проверить модель":"Выбрать и проверить модель");}
    private void inspect(int index){
        if(downloading){toast("Загрузка модели уже запрошена. Дождитесь ответа Android.");return;}
        if(inspection!=null){toast("Проверка языка уже выполняется");return;}
        getSharedPreferences("saa_system_speech_v1",0).edit().putString("language",tags[index]).apply();refreshLanguageLabels();
        if(!FamilySystemSpeech.isAvailable(this)){languages[index].setText("Локальная служба недоступна");toast("На этом телефоне Android не предоставляет подходящую локальную службу распознавания. Установка языка из приложения недоступна.");return;}
        languages[index].setText("Проверяем модель…");
        inspection=FamilySystemSpeech.inspect(this,tags[index],new FamilySystemSpeech.SupportCallback(){
            public void onSupport(FamilySystemSpeech.SupportInfo info){inspection=null;if(isDestroyed())return;
                if(info.requestedLanguageInstalled){languages[index].setText("Выбран • модель установлена");return;}
                if(info.requestedLanguagePending){languages[index].setText("Выбран • Android ожидает загрузку");return;}
                languages[index].setText("Выбран • модель не установлена");
                new AlertDialog.Builder(FamilyPluginsActivity.this,FamilyNotificationSetup.dialogTheme(FamilyPluginsActivity.this))
                    .setTitle("Скачать язык: "+names[index]).setMessage("Запросить локальную модель у Android? Система определяет доступность языка и условия загрузки.")
                    .setPositiveButton("Скачать",(dialog,which)->download(index)).setNegativeButton("Отмена",null).show();
            }
            public void onError(String code){inspection=null;if(isDestroyed())return;languages[index].setText("Не удалось проверить модель");toast("Android не сообщил о поддержке языка ("+code+"). Попробуйте позже или проверьте системную службу распознавания.");}
        });
    }
    private void download(int index){downloading=true;languages[index].setText("Запрос загрузки Android…");
        downloadSession=FamilySystemSpeech.downloadLanguage(this,tags[index],new FamilySystemSpeech.Callback(){
            public void onProgress(int sent,int total){if(!isDestroyed())languages[index].setText("Загрузка модели: "+sent+"%");}
            public void onResult(String text){downloading=false;if(!isDestroyed())languages[index].setText("Выбран • модель установлена");}
            public void onError(String code){downloading=false;if(isDestroyed())return;
                if("MODEL_DOWNLOAD_SCHEDULED".equals(code)){languages[index].setText("Загрузка запрошена • проверьте позже");return;}
                languages[index].setText("Android не загрузил модель");toast("Не удалось загрузить язык ("+code+"). Доступность загрузки зависит от системной службы.");}
        });
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(inspection!=null)inspection.destroy();if(downloadSession!=null)downloadSession.destroy();super.onDestroy();}
}
