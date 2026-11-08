package org.telegram.messenger.family;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.R;

/** Installation-bound owner surface. Every operation is also authorized on the server. */
public final class FamilyDevicesActivity extends FamilyUi {
    private LinearLayout list;
    private TextView feedback, invitation;
    private Button create, refresh, copy;
    private boolean busy;
    private String invitationCode="";
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.fg_owner_devices);
        settingsNavigation(R.string.fg_owner_devices);
        paragraph(R.string.fg_owner_explanation);
        create=button(R.string.fg_owner_create_invite,true);
        create.setOnClickListener(v->request("invite",null));
        invitation=text(R.string.fg_owner_invite_hint,16,ink);invitation.setTextIsSelectable(true);add(invitation,12);
        copy=button(R.string.fg_owner_copy_invite,false);copy.setVisibility(View.GONE);
        copy.setOnClickListener(v->{if(!invitationCode.isEmpty())((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.fg_owner_invitation),invitationCode));});
        refresh=button(R.string.fg_owner_refresh,false);refresh.setOnClickListener(v->request("list",null));
        feedback=text(R.string.fg_owner_loading,14,muted);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(feedback,12);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);add(list,8);
        request("list",null);
    }
    private void request(String command,String id) {
        if(busy)return;
        if(!FamilyAccess.isPhoneOwner()){showError("owner_required");return;}
        busy=true;enabled(create,false);enabled(refresh,false);list.setEnabled(false);
        feedback.setText(R.string.fg_owner_loading);feedback.setTextColor(muted);
        FamilyAccess.manage(command,id,24,(result,errorCode)->{
            if(isFinishing()||isDestroyed())return;
            busy=false;enabled(create,true);enabled(refresh,true);list.setEnabled(true);
            if(!errorCode.isEmpty()){showError(errorCode);return;}
            try {
                if(command.equals("list")){render(result.getJSONArray("devices"));feedback.setText(result.optBoolean("truncated")?R.string.fg_owner_truncated:R.string.fg_owner_verified);}
                else if(command.equals("invite")){
                    String code=result.getString("code");if(!code.matches("[A-F0-9]{30}"))throw new IllegalStateException();
                    invitationCode=code;invitation.setText(getString(R.string.fg_owner_invite_display,code));copy.setVisibility(View.VISIBLE);feedback.setText(R.string.fg_owner_invite_created);
                } else {feedback.setText(R.string.fg_owner_done);request("list",null);}
            } catch(Exception failure){showError("invalid_response");}
        });
    }
    private void render(JSONArray devices)throws Exception {
        list.removeAllViews();
        if(devices.length()==0){TextView empty=text(R.string.fg_owner_empty,16,muted);list.addView(empty);return;}
        if(devices.length()>50)throw new IllegalStateException();
        for(int n=0;n<devices.length();n++){
            JSONObject device=devices.getJSONObject(n);String id=device.getString("id"),status=device.getString("status");
            if(!id.matches("[a-f0-9]{64}")||!status.matches("pending|approved|revoked|blocked"))throw new IllegalStateException();
            boolean owner=device.optBoolean("phone_owner");
            TextView name=text(R.string.fg_device_name,17,ink);
            String clean=device.optString("name").replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").trim();
            name.setText(clean.isEmpty()?getString(R.string.fg_device_name):clean);name.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams title=new LinearLayout.LayoutParams(-1,-2);title.topMargin=dp(24);list.addView(name,title);
            TextView info=text(R.string.fg_device_fingerprint,14,muted);
            String fingerprint=id.substring(0,16).toUpperCase(java.util.Locale.ROOT).replaceAll("(.{4})(?!$)","$1 ");
            int label=owner?R.string.fg_owner_this_phone:status.equals("pending")?R.string.fg_owner_pending:status.equals("approved")?R.string.fg_owner_approved:status.equals("blocked")?R.string.fg_owner_blocked:R.string.fg_owner_revoked;
            info.setText(getString(label)+"\n"+fingerprint);info.setTypeface(android.graphics.Typeface.MONOSPACE);list.addView(info);
            if(!owner&&(status.equals("pending")||status.equals("approved"))){
                boolean approval=status.equals("pending");
                // Reuse the established button styling without attaching it to the outer form.
                Button action=button(approval?R.string.fg_owner_approve:R.string.fg_owner_revoke,false);content.removeView(action);list.addView(action,new LinearLayout.LayoutParams(-1,-2));
                action.setOnClickListener(v->{if(busy)return;new AlertDialog.Builder(this).setTitle(approval?R.string.fg_owner_approve:R.string.fg_owner_revoke)
                    .setMessage(getString(approval?R.string.fg_owner_approve_confirmation:R.string.fg_owner_revoke_confirmation,fingerprint))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(approval?R.string.fg_owner_approve:R.string.fg_owner_revoke,(dialog,which)->request(approval?"approve":"revoke",id)).show();});
            }
        }
    }
    private void showError(String code){
        feedback.setTextColor(error);
        int message=code.equals("owner_required")?R.string.fg_owner_denied:code.equals("device_not_pending")?R.string.fg_owner_already_changed:code.equals("operation_in_progress")?R.string.fg_owner_busy:R.string.fg_owner_network_error;
        feedback.setText(message);
        if(!FamilyAccess.isPhoneOwner()){enabled(create,false);enabled(refresh,false);copy.setVisibility(View.GONE);invitationCode="";invitation.setText(R.string.fg_owner_denied);list.removeAllViews();}
    }
}
