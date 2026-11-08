package org.telegram.messenger.family;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Switch;
import org.telegram.messenger.R;

public final class FamilyPluginsActivity extends FamilyUi {
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setTitle(R.string.fg_plugins);settingsNavigation(R.string.fg_plugins);
        paragraph(R.string.fg_builtin_intro);
        item("python",R.string.fg_python_runner,R.string.fg_python_summary);
        item("documents",R.string.fg_documents_title,R.string.fg_documents_summary);
        button(R.string.fg_other_plugins,false).setOnClickListener(v->{Intent intent=new Intent();intent.setClassName(getPackageName(),"org.telegram.ui.LaunchActivity");intent.putExtra("family_screen","external_plugins");intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);finish();});
    }
    private void item(String id,int title,int description){
        section(title,description);
        Switch toggle=new Switch(this);toggle.setText(FamilyChatTools.enabled(this,id)?R.string.fg_builtin_enabled:R.string.fg_builtin_disabled);toggle.setTextColor(ink);toggle.setTextSize(16);toggle.setMinHeight(dp(56));toggle.setChecked(FamilyChatTools.enabled(this,id));
        toggle.setOnCheckedChangeListener((view,checked)->{FamilyChatTools.setEnabled(this,id,checked);view.setText(checked?R.string.fg_builtin_enabled:R.string.fg_builtin_disabled);});add(toggle,8);
        if(id.equals("python"))button(R.string.fg_python_open,false).setOnClickListener(v->{if(FamilyChatTools.enabled(this,id))FamilyChatTools.openPython(this,"print(2 + 2)");else android.widget.Toast.makeText(this,R.string.fg_builtin_enable_first,android.widget.Toast.LENGTH_SHORT).show();});
    }
}
