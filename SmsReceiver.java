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
            Pattern.compile("(?i)(credited|received|deposited|\\bcr\\b|credit|paid you|sent you)");
    private static final Pattern REJECT =
            Pattern.compile("(?i)(\\botp\\b|one time password|debited|withdrawn|\\bdr\\b|request|cashback|reward|scratch)");
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
            String sender = parts[0].getOriginatingAddress();
            mark(context, "last_any");
            boolean credit = isPaymentCredit(body);
            recordRecent(context, "SMS", sender, body, credit);
            if (!credit) return;
            mark(context, "last_credit");

            long ts = System.currentTimeMillis();
            String id = ts + "-" + Math.abs(body.hashCode());

            store(context, id, sender, body, ts);
            SmsPaymentPlugin.notifyForeground();
            showNotification(context, body, findPayerName(context, body));
        } catch (Exception e) {
            // never crash the receiver
        }
    }

    /** Hides long digit runs (OTP, account and phone numbers) but keeps rupee amounts. */
    static String mask(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m = Pattern
                .compile("(?i)((?:rs\\.?|inr|\u20B9)\\s*)?(\\d[\\d,]*(?:\\.\\d+)?)")
                .matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String rep;
            if (m.group(1) != null) rep = m.group(0);
            else if (m.group(2).replaceAll("\\D", "").length() >= 4) rep = "****";
            else rep = m.group(0);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        String r = out.toString();
        return r.length() > 70 ? r.substring(0, 70) + "..." : r;
    }

    /** Keeps the last few messages the app saw (masked) so the SMS check screen can show them. */
    static synchronized void recordRecent(Context ctx, String src, String from, String body, boolean ok) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString("recent", "[]"));
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("src", src);
            o.put("from", from == null ? "" : from);
            o.put("txt", mask(body));
            o.put("ok", ok);
            JSONArray arr = new JSONArray();
            arr.put(o);
            for (int i = 0; i < old.length() && arr.length() < 6; i++) arr.put(old.get(i));
            sp.edit().putString("recent", arr.toString()).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    static void mark(Context ctx, String key) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(key, System.currentTimeMillis()).apply();
        } catch (Exception e) {
            // ignore
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

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:91)?([6-9]\\d{9})(?!\\d)");

    /** Returns the customer's name when exactly one saved customer's phone is inside the SMS, else "". */
    static String findPayerName(Context ctx, String body) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray custs = new JSONArray(sp.getString("customers", "[]"));
            java.util.regex.Matcher m = PHONE.matcher(body);
            String found = "";
            int hits = 0;
            while (m.find()) {
                String num = m.group(1);
                for (int i = 0; i < custs.length(); i++) {
                    JSONObject c = custs.getJSONObject(i);
                    if (num.equals(c.optString("p"))) {
                        if (!c.optString("n").equals(found)) hits++;
                        found = c.optString("n");
                    }
                }
            }
            return hits == 1 ? found : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void showNotification(Context ctx, String body, String payer) {
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
                .setContentTitle(payer == null || payer.length() == 0
                        ? "Payment received - tap to add in Khata"
                        : "Payment from " + payer + " - opens Khata")
                .setContentText(shortBody)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }

    /**
     * Reads payment notifications of UPI / bank apps (PhonePe, GPay, Paytm, bank apps...)
     * once the user allows "Notification access". Works like a soundbox: no SMS needed.
     */
    public static class Listener extends android.service.notification.NotificationListenerService {
        private static final Pattern PKG = Pattern.compile(
                "(?i)(phonepe|paisa|paytm|npci|bhim|upi|bank|sbi|yono|hdfc|icici|axis|kotak|pnb|canara|baroda|idfc|mobikwik|freecharge|\\.pay|pay\\.)");

        @Override
        public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
            try {
                if (sbn == null) return;
                String pkg = sbn.getPackageName();
                if (pkg == null || pkg.equals(getPackageName()) || !PKG.matcher(pkg).find()) return;
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) return;
                CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                String body = ((title == null ? "" : title.toString()) + " "
                        + (big != null ? big.toString() : (text == null ? "" : text.toString()))).trim();
                if (body.length() == 0) return;

                Context ctx = getApplicationContext();
                boolean credit = isPaymentCredit(body);
                recordRecent(ctx, "NOTIF", pkg, body, credit);
                mark(ctx, "last_notif");
                if (!credit) return;

                // the same notification is often posted twice - keep only the first within 2 minutes
                String sig = Integer.toHexString((pkg + body).hashCode());
                long now = System.currentTimeMillis();
                SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String last = sp.getString("nsig", "");
                if (last.startsWith(sig + "|")) {
                    try {
                        long t = Long.parseLong(last.substring(sig.length() + 1));
                        if (now - t < 120000L) return;
                    } catch (Exception ignore) {
                        // fall through and store it
                    }
                }
                sp.edit().putString("nsig", sig + "|" + now).apply();

                mark(ctx, "last_credit");
                store(ctx, "n-" + sig + "-" + now, "N:" + pkg, body, now);
                SmsPaymentPlugin.notifyForeground();
            } catch (Exception e) {
                // never crash the listener
            }
        }
    }
}
