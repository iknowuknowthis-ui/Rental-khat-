package com.ankit.khata;

import android.Manifest;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.core.content.FileProvider;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

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

        if ("hasPermission".equals(action)) {
            boolean ok = cordova.hasPermission(Manifest.permission.RECEIVE_SMS);
            callbackContext.success(ok ? "granted" : "denied");
            return true;
        }

        if ("listen".equals(action)) {
            listener = callbackContext;
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
