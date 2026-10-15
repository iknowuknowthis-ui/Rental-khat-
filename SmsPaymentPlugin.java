package com.ankit.khata;

import android.Manifest;
import android.content.ClipData;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Bridge between the Khata web page and Android: SMS inbox + file sharing. */
public class SmsPaymentPlugin extends CordovaPlugin {

    private static final int REQ_PERMS = 7301;
    private static volatile CallbackContext listener;

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext)
            throws JSONException {

        if ("requestPermission".equals(action)) {
            List<String> need = new ArrayList<String>();
            if (!cordova.hasPermission(Manifest.permission.RECEIVE_SMS)) {
                need.add(Manifest.permission.RECEIVE_SMS);
            }
            if (!cordova.hasPermission(Manifest.permission.READ_SMS)) {
                need.add(Manifest.permission.READ_SMS);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && !cordova.hasPermission("android.permission.POST_NOTIFICATIONS")) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (!need.isEmpty()) {
                cordova.requestPermissions(this, REQ_PERMS, need.toArray(new String[0]));
            }
            callbackContext.success("requested");
            return true;
        }

        if ("syncCustomers".equals(action)) {
            Context ctx = cordova.getActivity().getApplicationContext();
            ctx.getSharedPreferences(SmsReceiver.PREFS, Context.MODE_PRIVATE)
                    .edit().putString("customers", args.getString(0)).apply();
            callbackContext.success();
            return true;
        }

        if ("diagnose".equals(action)) {
            Context ctx = cordova.getActivity().getApplicationContext();
            SharedPreferences sp = ctx.getSharedPreferences(SmsReceiver.PREFS, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("brand", Build.MANUFACTURER);
            o.put("receive", cordova.hasPermission(Manifest.permission.RECEIVE_SMS));
            o.put("read", cordova.hasPermission(Manifest.permission.READ_SMS));
            o.put("notif", Build.VERSION.SDK_INT < 33
                    || cordova.hasPermission("android.permission.POST_NOTIFICATIONS"));
            o.put("lastAny", sp.getLong("last_any", 0));
            o.put("lastNotif", sp.getLong("last_notif", 0));
            boolean na = false;
            try {
                na = androidx.core.app.NotificationManagerCompat
                        .getEnabledListenerPackages(ctx).contains(ctx.getPackageName());
            } catch (Exception ignore) {
                // keep false
            }
            o.put("notifAccess", na);
            o.put("recent", new JSONArray(sp.getString("recent", "[]")));
            o.put("lastCredit", sp.getLong("last_credit", 0));
            o.put("pending", new JSONArray(sp.getString(SmsReceiver.KEY_PENDING, "[]")).length());
            callbackContext.success(o);
            return true;
        }

        if ("scanInbox".equals(action)) {
            final long days = args.optLong(0, 2);
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    Cursor c = null;
                    try {
                        if (!cordova.hasPermission(Manifest.permission.READ_SMS)) {
                            callbackContext.error("no_permission");
                            return;
                        }
                        Context ctx = cordova.getActivity().getApplicationContext();
                        long since = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
                        c = ctx.getContentResolver().query(
                                Uri.parse("content://sms/inbox"),
                                new String[]{"_id", "address", "body", "date"},
                                "date>?", new String[]{String.valueOf(since)},
                                "date DESC LIMIT 60");
                        JSONArray out = new JSONArray();
                        if (c != null) {
                            while (c.moveToNext()) {
                                String body = c.getString(2);
                                if (!SmsReceiver.isPaymentCredit(body)) continue;
                                JSONObject o = new JSONObject();
                                o.put("id", "ib-" + c.getLong(0));
                                o.put("sender", c.getString(1) == null ? "" : c.getString(1));
                                o.put("body", body);
                                o.put("ts", c.getLong(3));
                                out.put(o);
                            }
                        }
                        callbackContext.success(out);
                    } catch (Exception e) {
                        callbackContext.error(String.valueOf(e.getMessage()));
                    } finally {
                        if (c != null) c.close();
                    }
                }
            });
            return true;
        }

        if ("openNotifAccess".equals(action)) {
            Context ctx = cordova.getActivity();
            try {
                Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e1) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception e2) {
                    callbackContext.error(String.valueOf(e2.getMessage()));
                    return true;
                }
            }
            callbackContext.success();
            return true;
        }

        if ("openAutostart".equals(action)) {
            Context ctx = cordova.getActivity();
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e1) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                } catch (Exception e2) {
                    callbackContext.error(String.valueOf(e2.getMessage()));
                    return true;
                }
            }
            callbackContext.success();
            return true;
        }

        if ("hasPermission".equals(action)) {
            boolean ok = cordova.hasPermission(Manifest.permission.RECEIVE_SMS);
            callbackContext.success(ok ? "granted" : "denied");
            return true;
        }

        if ("listen".equals(action)) {
            listener = callbackContext;
            try {
                // ask Android to reconnect the notification listener if it was stopped
                android.service.notification.NotificationListenerService.requestRebind(
                        new ComponentName(cordova.getActivity().getApplicationContext(),
                                SmsReceiver.Listener.class));
            } catch (Exception ignore) {
                // not allowed yet - fine
            }
            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);
            return true;
        }

        if ("getPending".equals(action)) {
            Context ctx = cordova.getActivity().getApplicationContext();
            callbackContext.success(new JSONArray(SmsReceiver.takePending(ctx)));
            return true;
        }

        if ("shareFile".equals(action)) {
            final String b64 = args.getString(0);
            final String name = args.getString(1);
            final String mime = args.getString(2);
            final String title = args.getString(3);
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        doShare(b64, name, mime, title);
                        callbackContext.success();
                    } catch (Exception e) {
                        callbackContext.error(String.valueOf(e.getMessage()));
                    }
                }
            });
            return true;
        }

        return false;
    }

    private void doShare(String b64, String name, String mime, String title) throws Exception {
        Context ctx = cordova.getActivity().getApplicationContext();
        File dir = new File(ctx.getCacheDir(), "share");
        if (!dir.exists()) dir.mkdirs();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File f = new File(dir, safe);
        byte[] data = Base64.decode(b64, Base64.DEFAULT);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".smspay.provider", f);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, title);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        cordova.getActivity().startActivity(chooser);
    }

    /** Called by SmsReceiver when the app process is alive, so the page can refresh at once. */
    static void notifyForeground() {
        CallbackContext cb = listener;
        if (cb == null) return;
        PluginResult r = new PluginResult(PluginResult.Status.OK, "new");
        r.setKeepCallback(true);
        cb.sendPluginResult(r);
    }

    @Override
    public void onReset() {
        listener = null;
    }

    @Override
    public void onDestroy() {
        listener = null;
    }
}
