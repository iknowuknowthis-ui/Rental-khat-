package com.ankit.khata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Receives every incoming SMS (even when the app is closed), keeps only bank/UPI
 * credit messages, stores them until the app is opened, and shows a notification.
 * Nothing is sent anywhere - messages stay on this phone.
 */
public class SmsReceiver extends BroadcastReceiver {

    static final String PREFS = "smspayment_prefs";
    static final String KEY_PENDING = "pending";
    static final String CHANNEL_ID = "payment_sms";

    // \u20B9 = rupee sign
    private static final Pattern CREDIT =
            Pattern.compile("(?i)(credited|received|deposited|\\bcr\\b|credit)");
    private static final Pattern REJECT =
            Pattern.compile("(?i)(\\botp\\b|one time password|debited|withdrawn|\\bdr\\b|request)");
    private static final Pattern MONEY =
            Pattern.compile("(?i)(rs\\.?|inr|\u20B9)\\s*[0-9]");

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null
                    || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
                return;
            }
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) return;

            StringBuilder sb = new StringBuilder();
            for (SmsMessage p : parts) {
                if (p != null && p.getMessageBody() != null) sb.append(p.getMessageBody());
            }
            String body = sb.toString();
            if (!isPaymentCredit(body)) return;

            String sender = parts[0].getOriginatingAddress();
            long ts = System.currentTimeMillis();
            String id = ts + "-" + Math.abs(body.hashCode());

            store(context, id, sender, body, ts);
            SmsPaymentPlugin.notifyForeground();
            showNotification(context, body);
        } catch (Exception e) {
            // never crash the receiver
        }
    }

    static boolean isPaymentCredit(String body) {
        if (body == null) return false;
        return CREDIT.matcher(body).find()
                && MONEY.matcher(body).find()
                && !REJECT.matcher(body).find();
    }

    private static synchronized void store(Context ctx, String id, String sender,
                                           String body, long ts) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray(sp.getString(KEY_PENDING, "[]"));
        for (int i = 0; i < arr.length(); i++) {
            if (id.equals(arr.getJSONObject(i).optString("id"))) return;
        }
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("sender", sender == null ? "" : sender);
        o.put("body", body);
        o.put("ts", ts);
        arr.put(o);
        if (arr.length() > 30) {
            JSONArray trimmed = new JSONArray();
            for (int i = arr.length() - 30; i < arr.length(); i++) trimmed.put(arr.get(i));
            arr = trimmed;
        }
        sp.edit().putString(KEY_PENDING, arr.toString()).apply();
    }

    static synchronized String takePending(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_PENDING, "[]");
        sp.edit().remove(KEY_PENDING).apply();
        return s;
    }

    private void showNotification(Context ctx, String body) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Payment SMS", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (launch == null) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch, flags);

        String shortBody = body.length() > 90 ? body.substring(0, 90) + "..." : body;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Payment received - tap to add in Khata")
                .setContentText(shortBody)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }
}
