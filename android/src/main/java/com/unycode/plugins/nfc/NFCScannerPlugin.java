package com.unycode.plugins.nfc;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Parcelable;
import android.os.Build;
import android.util.Log;

import androidx.core.content.IntentCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;

@CapacitorPlugin(name = "NFCScanner")
public class NFCScannerPlugin extends Plugin {

    private static final String TAG = "NFCScannerPlugin";

    private PendingWrite pendingWrite;
    private volatile boolean writeCancelled;
    private JSObject cachedLaunchTagData;

    private static class PendingWrite {
        final PluginCall call;
        final String uri;
        final String payload;
        final boolean lock;

        PendingWrite(PluginCall call, String uri, String payload, boolean lock) {
            this.call = call;
            this.uri = uri;
            this.payload = payload;
            this.lock = lock;
        }
    }

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value", "");
        JSObject ret = new JSObject();
        ret.put("value", value);
        call.resolve(ret);
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(getContext());
        boolean enabled = adapter != null && adapter.isEnabled();
        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void startScan(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void getLaunchIntent(PluginCall call) {
        if (cachedLaunchTagData != null) {
            call.resolve(cachedLaunchTagData);
            return;
        }
        Intent intent = getActivity() != null ? getActivity().getIntent() : null;
        JSObject tagData = intent != null ? buildTagDataFromIntent(intent) : null;
        if (tagData != null) {
            cachedLaunchTagData = tagData;
        }
        call.resolve(tagData);
    }

    @PluginMethod
    public void clearLaunchIntent(PluginCall call) {
        cachedLaunchTagData = null;
        call.resolve();
    }

    private JSObject buildTagDataFromIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null) return null;
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return null;
        }
        Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
        if (tag == null) return null;

        try {
            org.json.JSONArray messagesArray = new org.json.JSONArray();
            Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (rawMsgs != null && rawMsgs.length > 0) {
                for (Parcelable p : rawMsgs) {
                    if (p instanceof NdefMessage) {
                        org.json.JSONArray recordsArray = new org.json.JSONArray();
                        for (NdefRecord rec : ((NdefMessage) p).getRecords()) {
                            org.json.JSONObject recordObj = new org.json.JSONObject();
                            recordObj.put("tnf", rec.getTnf());
                            recordObj.put("type", new String(rec.getType(), StandardCharsets.UTF_8));
                            recordObj.put("id", rec.getId() != null ? new String(rec.getId(), StandardCharsets.UTF_8) : "");
                            recordObj.put("payload", rec.getPayload() != null ? bytesToHex(rec.getPayload()) : "");
                            try {
                                android.net.Uri uri = rec.toUri();
                                if (uri != null) {
                                    recordObj.put("uri", uri.toString());
                                }
                            } catch (Exception ignored) {}
                            String mimeType = new String(rec.getType(), StandardCharsets.UTF_8);
                            if ("application/json".equals(mimeType) && rec.getPayload() != null) {
                                String json = new String(rec.getPayload(), StandardCharsets.UTF_8);
                                recordObj.put("mimeContent", json);
                                recordObj.put("jsonContent", json);
                            }
                            recordsArray.put(recordObj);
                        }
                        org.json.JSONObject msgObj = new org.json.JSONObject();
                        msgObj.put("records", recordsArray);
                        messagesArray.put(msgObj);
                    }
                }
            }

            org.json.JSONObject dataWrapper = new org.json.JSONObject();
            dataWrapper.put("tagId", bytesToHex(tag.getId()));
            dataWrapper.put("discoveryAction", action);
            dataWrapper.put("ndefMessages", messagesArray);

            JSObject result = new JSObject();
            result.put("id", bytesToHex(tag.getId()));
            result.put("serialNumber", bytesToHex(tag.getId()));
            result.put("data", dataWrapper);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error building tag data from intent", e);
            return null;
        }
    }

    @PluginMethod
    public void writeTag(PluginCall call) {
        String uri = call.getString("uri");
        String payload = call.getString("payload");
        boolean lock = call.getBoolean("lock", false);

        if (uri == null || uri.isEmpty()) {
            call.reject("uri is required");
            return;
        }
        if (payload == null) {
            call.reject("payload is required");
            return;
        }

        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(getContext());
        if (adapter == null) {
            call.reject("NFC is not available on this device");
            return;
        }
        if (!adapter.isEnabled()) {
            call.reject("NFC is disabled. Please enable NFC in settings.");
            return;
        }

        if (pendingWrite != null) {
            call.reject("A write is already in progress. Cancel it first.");
            return;
        }

        call.save();
        writeCancelled = false;
        pendingWrite = new PendingWrite(call, uri, payload, lock);

        // Must enable in onResume; also try now in case we're already resumed
        tryEnableForegroundDispatch();
        // Call stays saved; will resolve in handlePendingWrite when tag is tapped
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        tryEnableForegroundDispatch();
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        disableForegroundDispatch();
    }

    @PluginMethod
    public void cancelWrite(PluginCall call) {
        writeCancelled = true;
        if (pendingWrite != null) {
            pendingWrite.call.reject("Write cancelled");
            pendingWrite = null;
        }
        disableForegroundDispatch();
        call.resolve();
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        try {
            if (intent == null) return;

            String action = intent.getAction();
            if (action == null) return;
            if (!NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                    && !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
                return;
            }

            Tag tag = getTagFromIntent(intent);
            if (tag == null) {
                Log.w(TAG, "No tag in intent");
                return;
            }

            if (pendingWrite != null && !writeCancelled) {
                PendingWrite pw = pendingWrite;
                pendingWrite = null;
                disableForegroundDispatch();
                JSObject tagDataFromIntent = buildTagDataFromIntent(intent);
                handlePendingWrite(tag, pw, tagDataFromIntent);
            } else {
                JSObject tagData = buildTagDataFromIntent(intent);
                if (tagData != null) {
                    notifyListeners("tagDetected", tagData);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleOnNewIntent failed", e);
            if (pendingWrite != null) {
                PendingWrite pw = pendingWrite;
                pendingWrite = null;
                disableForegroundDispatch();
                safeReject(pw, "NFC error: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Tag getTagFromIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class);
        }
        return IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
    }

    private void safeReject(PendingWrite pw, String msg) {
        if (pw == null) return;
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                try {
                    pw.call.reject(msg);
                } catch (Exception e) {
                    Log.e(TAG, "safeReject failed", e);
                }
            });
        } else {
            try {
                pw.call.reject(msg);
            } catch (Exception e) {
                Log.e(TAG, "safeReject failed (no activity)", e);
            }
        }
    }

    private void handlePendingWrite(Tag tag, PendingWrite pw, JSObject tagDataFromIntent) {
        new Thread(() -> {
            try {
                NdefRecord uriRecord = NdefRecord.createUri(pw.uri);
                byte[] payloadBytes = pw.payload.getBytes(StandardCharsets.UTF_8);
                NdefRecord mimeRecord = NdefRecord.createMime("application/json", payloadBytes);
                NdefMessage message = new NdefMessage(new NdefRecord[]{uriRecord, mimeRecord});

                Ndef ndef = Ndef.get(tag);
                NdefFormatable formatable = NdefFormatable.get(tag);

                if (ndef != null) {
                    ndef.connect();
                    ndef.writeNdefMessage(message);
                    boolean locked = false;
                    if (pw.lock) {
                        try {
                            ndef.makeReadOnly();
                            locked = true;
                        } catch (Exception e) {
                            Log.w(TAG, "Could not lock tag: " + e.getMessage());
                        }
                    }
                    ndef.close();
                    resolveOnMainThread(pw, tag, true, locked, null);
                } else if (formatable != null) {
                    formatable.connect();
                    boolean locked = false;
                    if (pw.lock) {
                        try {
                            formatable.formatReadOnly(message);
                            locked = true;
                        } catch (Exception e) {
                            Log.w(TAG, "Could not format read-only, trying format: " + e.getMessage());
                            formatable.format(message);
                        }
                    } else {
                        formatable.format(message);
                    }
                    formatable.close();
                    resolveOnMainThread(pw, tag, true, locked, null);
                } else {
                    rejectOnMainThread(pw, "Tag does not support NDEF or NDEF formatable");
                }
            } catch (Exception e) {
                Log.e(TAG, "Write failed", e);
                resolveOnMainThread(pw, tag, false, false, tagDataFromIntent);
            }
        }).start();
    }

    private void resolveOnMainThread(PendingWrite pw, Tag tag, boolean written, boolean locked, JSObject tagData) {
        if (getActivity() == null) {
            pw.call.reject("Activity no longer available");
            return;
        }
        getActivity().runOnUiThread(() -> {
            try {
                String tagId = bytesToHex(tag.getId());
                JSObject ret = new JSObject();
                ret.put("tagId", tagId);
                ret.put("written", written);
                ret.put("locked", locked);
                if (tagData != null) {
                    ret.put("tagData", tagData);
                }
                pw.call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Resolve failed", e);
                pw.call.reject("Failed to complete: " + e.getMessage());
            }
        });
    }

    private void rejectOnMainThread(PendingWrite pw, String message) {
        if (getActivity() == null) {
            return;
        }
        getActivity().runOnUiThread(() -> pw.call.reject(message));
    }

    private void tryEnableForegroundDispatch() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(getContext());
                if (adapter == null) return;

                android.app.Activity activity = getActivity();
                Intent intent = new Intent(activity, activity.getClass());
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        activity, 0, intent, flags);

                IntentFilter[] filters = new IntentFilter[]{
                        new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                        new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
                };

                adapter.enableForegroundDispatch(getActivity(), pendingIntent, filters, null);
                Log.d(TAG, "Foreground dispatch enabled");
            } catch (IllegalStateException e) {
                Log.w(TAG, "Foreground dispatch not ready (will retry in onResume): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Failed to enable foreground dispatch", e);
            }
        });
    }

    private void disableForegroundDispatch() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(getContext());
                if (adapter != null) {
                    adapter.disableForegroundDispatch(getActivity());
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "Could not disable foreground dispatch (activity not resumed): " + e.getMessage());
            }
        });
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xff));
        }
        return sb.toString();
    }
}
