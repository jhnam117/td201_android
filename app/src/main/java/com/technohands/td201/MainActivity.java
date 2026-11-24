package com.technohands.td201;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * TD-201 Manager for Android.
 *
 * The whole user interface is assets/www/index.html (the same page as the
 * browser version). This activity only hosts it in a WebView and gives it:
 *   - window.AndroidSerial : USB-serial port (usb-serial-for-android drivers),
 *                            used by android-serial.js as navigator.serial
 *   - window.AndroidHost   : file saving through the system "save as" screen
 * Results and received data go back to the page through window.__td201.*.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.technohands.td201.USB_PERMISSION";
    private static final int REQ_SAVE = 11;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private WebView web;
    private UsbManager usb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ports found by the last requestPort()
    private final List<UsbSerialPort> found = new ArrayList<UsbSerialPort>();

    // the open port
    private final Object portLock = new Object();
    private UsbSerialPort port;
    private UsbDeviceConnection conn;
    private SerialInputOutputManager io;
    private UsbDevice openDevice;

    // USB permission request in progress
    private int permCallId = -1;
    private int permIndex = -1;

    // received data waiting to be handed to the page: [time ms, base64]
    private final ArrayList<String> rxQueue = new ArrayList<String>();
    private boolean rxPosted = false;

    // file save in progress
    private String saveText;
    private String saveName;

    // ------------------------------------------------------------------ life cycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);                 // ignore the system font size: the layout is fixed
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new SerialBridge(), "AndroidSerial");
        web.addJavascriptInterface(new HostBridge(), "AndroidHost");
        setContentView(web);

        IntentFilter pf = new IntentFilter(ACTION_USB_PERMISSION);
        IntentFilter df = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, pf, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(detachReceiver, df, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(permReceiver, pf);
            registerReceiver(detachReceiver, df);
        }

        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onPause() {
        // the page stops a held jog button when the app goes to the background
        js("window.__td201&&__td201.hidden&&__td201.hidden()");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(permReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(detachReceiver); } catch (Exception ignored) { }
        closePort();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage("Exit TD-201 Manager?")
                .setPositiveButton("Exit", (d, w) -> finish())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ page helpers
    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String q(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void done(int id, boolean ok, String payloadJson) {
        js("window.__td201&&__td201.done(" + id + "," + ok + "," + payloadJson + ")");
    }

    private void fail(int id, String name, String message) {
        done(id, false, "{\"name\":" + q(name) + ",\"message\":" + q(message) + "}");
    }

    private String portInfo(int index) {
        UsbDevice d = found.get(index).getDriver().getDevice();
        return "{\"id\":" + index + ",\"vid\":" + d.getVendorId() + ",\"pid\":" + d.getProductId()
                + ",\"name\":" + q(portLabel(found.get(index))) + "}";
    }

    private static String portLabel(UsbSerialPort p) {
        UsbSerialDriver drv = p.getDriver();
        UsbDevice d = drv.getDevice();
        String kind = drv.getClass().getSimpleName().replace("SerialDriver", "");
        String name = d.getProductName();
        String s = String.format("%s  %04X:%04X", kind, d.getVendorId(), d.getProductId());
        if (name != null && name.length() > 0) s += "  " + name;
        if (drv.getPorts().size() > 1) s += "  (port " + (p.getPortNumber() + 1) + ")";
        return s;
    }

    // ------------------------------------------------------------------ port selection
    private void requestPort(final int id) {
        found.clear();
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        for (UsbSerialDriver d : drivers) found.addAll(d.getPorts());
        if (found.isEmpty()) {
            fail(id, "NotFoundError", "No USB serial adapter found.\n"
                    + "Check the OTG cable and the USB-UART adapter.");
            return;
        }
        if (found.size() == 1) {
            choose(id, 0);
            return;
        }
        String[] items = new String[found.size()];
        for (int i = 0; i < items.length; i++) items[i] = portLabel(found.get(i));
        final boolean[] answered = {false};
        new AlertDialog.Builder(this)
                .setTitle("Select the USB serial port")
                .setItems(items, (dlg, which) -> { answered[0] = true; choose(id, which); })
                .setOnDismissListener(dlg -> {
                    if (!answered[0]) fail(id, "NotFoundError", "No port selected.");
                })
                .show();
    }

    private void choose(int id, int index) {
        UsbDevice dev = found.get(index).getDriver().getDevice();
        if (usb.hasPermission(dev)) {
            done(id, true, portInfo(index));
            return;
        }
        permCallId = id;
        permIndex = index;
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        usb.requestPermission(dev, pi);
    }

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction()) || permCallId < 0) return;
            int id = permCallId, index = permIndex;
            permCallId = -1;
            if (index < 0 || index >= found.size()) {
                fail(id, "NotFoundError", "The adapter was removed.");
                return;
            }
            UsbDevice dev = found.get(index).getDriver().getDevice();
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    || usb.hasPermission(dev);
            if (granted) done(id, true, portInfo(index));
            else fail(id, "NotAllowedError", "USB permission was not granted.");
        }
    };

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean ours;
            synchronized (portLock) {
                ours = openDevice != null && dev != null
                        && openDevice.getDeviceName().equals(dev.getDeviceName());
            }
            if (ours) {
                closePort();
                js("window.__td201&&__td201.lost()");
            }
        }
    };

    // ------------------------------------------------------------------ open / close
    private void openPort(final int index, final int baud, final int id) {
        new Thread(() -> {
            closePort();
            if (index < 0 || index >= found.size()) {
                fail(id, "NotFoundError", "Unknown port.");
                return;
            }
            UsbSerialPort p = found.get(index);
            UsbDevice dev = p.getDriver().getDevice();
            UsbDeviceConnection c = usb.openDevice(dev);
            if (c == null) {
                fail(id, "NetworkError", usb.hasPermission(dev)
                        ? "Cannot open the USB device." : "USB permission was not granted.");
                return;
            }
            try {
                p.open(c);
                p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                try { p.setDTR(true); p.setRTS(true); } catch (Exception ignored) { }
                if (p instanceof FtdiSerialDriver.FtdiSerialPort) {
                    // default 16 ms: data would arrive in bursts and the graph gets kinked
                    try { ((FtdiSerialDriver.FtdiSerialPort) p).setLatencyTimer(1); }
                    catch (Exception ignored) { }
                }
            } catch (Exception e) {
                try { p.close(); } catch (Exception ignored) { }
                try { c.close(); } catch (Exception ignored) { }
                fail(id, "NetworkError", "Cannot open the port: " + e.getMessage());
                return;
            }
            SerialInputOutputManager m = new SerialInputOutputManager(p, new Listener());
            synchronized (portLock) {
                port = p;
                conn = c;
                io = m;
                openDevice = dev;
            }
            m.start();
            done(id, true, "null");
        }).start();
    }

    private void closePort() {
        SerialInputOutputManager m;
        UsbSerialPort p;
        UsbDeviceConnection c;
        synchronized (portLock) {
            m = io; p = port; c = conn;
            io = null; port = null; conn = null; openDevice = null;
        }
        if (m != null) { try { m.setListener(null); m.stop(); } catch (Exception ignored) { } }
        if (p != null) { try { p.close(); } catch (Exception ignored) { } }
        if (c != null) { try { c.close(); } catch (Exception ignored) { } }
        synchronized (rxQueue) { rxQueue.clear(); }
    }

    private class Listener implements SerialInputOutputManager.Listener {
        @Override
        public void onNewData(byte[] data) {
            double t = SystemClock.elapsedRealtimeNanos() / 1e6;
            String item = "[" + String.format(java.util.Locale.US, "%.3f", t) + ",\""
                    + Base64.encodeToString(data, Base64.NO_WRAP) + "\"]";
            boolean post;
            synchronized (rxQueue) {
                rxQueue.add(item);
                post = !rxPosted;
                rxPosted = true;
            }
            if (post) ui.post(flushRx);
        }

        @Override
        public void onRunError(Exception e) {
            boolean current;
            synchronized (portLock) { current = io != null; }
            if (!current) return;           // closed on purpose
            closePort();
            js("window.__td201&&__td201.lost()");
        }
    }

    private final Runnable flushRx = new Runnable() {
        @Override
        public void run() {
            StringBuilder b = new StringBuilder("window.__td201&&__td201.rx([");
            synchronized (rxQueue) {
                for (int i = 0; i < rxQueue.size(); i++) {
                    if (i > 0) b.append(',');
                    b.append(rxQueue.get(i));
                }
                rxQueue.clear();
                rxPosted = false;
            }
            b.append("])");
            if (web != null) web.evaluateJavascript(b.toString(), null);
        }
    };

    // ------------------------------------------------------------------ bridges
    /** window.AndroidSerial (methods run on a background thread of the WebView). */
    private class SerialBridge {
        @JavascriptInterface
        public void requestPort(final int id) {
            ui.post(() -> MainActivity.this.requestPort(id));   // the activity method, not this one
        }

        @JavascriptInterface
        public void open(final int index, final int baud, final int id) {
            ui.post(() -> openPort(index, baud, id));
        }

        /** Returns "" when sent, otherwise the error text. */
        @JavascriptInterface
        public String write(String text) {
            UsbSerialPort p;
            synchronized (portLock) { p = port; }
            if (p == null) return "The port is not open.";
            try {
                p.write(text.getBytes(LATIN1), 500);
                return "";
            } catch (Exception e) {
                return "Write failed: " + e.getMessage();
            }
        }

        /** Change the baud rate of the open port. Returns "" or the error text. */
        @JavascriptInterface
        public String setBaud(int baud) {
            UsbSerialPort p;
            synchronized (portLock) { p = port; }
            if (p == null) return "The port is not open.";
            try {
                p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                return "";
            } catch (Exception e) {
                return "Baud rate change failed: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void close() {
            closePort();
        }
    }

    /** window.AndroidHost */
    private class HostBridge {
        @JavascriptInterface
        public void saveFile(final String name, final String text, final String type) {
            ui.post(() -> {
                saveName = name;
                saveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(type == null || type.length() == 0 ? "text/plain" : type);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No file manager to save with.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE) return;
        String text = saveText, name = saveName;
        saveText = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || text == null) {
            js("window.__td201&&__td201.saved&&__td201.saved(false," + q(name) + ")");
            return;
        }
        Uri uri = data.getData();
        try {
            OutputStream os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) throw new Exception("cannot open");
            os.write(text.getBytes(UTF8));
            os.close();
            Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
            js("window.__td201&&__td201.saved&&__td201.saved(true," + q(name) + ")");
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            js("window.__td201&&__td201.saved&&__td201.saved(false," + q(name) + ")");
        }
    }
}
