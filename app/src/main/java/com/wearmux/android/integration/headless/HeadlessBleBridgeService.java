package com.wearmux.android.integration.headless;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Base64;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONObject;
import com.wearmux.android.integration.HeadlessBridgeOwnership;
import com.wearmux.android.integration.WearableService;
import com.wearmux.android.integration.WearableServiceActions;

/** Foreground owner of one WebSocket and one BrilliantSole GATT connection. */
public final class HeadlessBleBridgeService extends Service {
    public static final String URL="url", TOKEN="token", FILTER="filter", STOP="stop";
    private static final UUID MAIN=UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f");
    private static final UUID RX=UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f");
    private static final UUID TX=UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY=UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID DEVICE_INFO=UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private static final Map<UUID,String> INFO = new HashMap<>();
    static { INFO.put(UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"),"manufacturerName"); INFO.put(UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"),"modelNumber"); INFO.put(UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"),"hardwareRevision"); INFO.put(UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"),"firmwareRevision"); INFO.put(UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"),"softwareRevision"); INFO.put(UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"),"serialNumber"); INFO.put(UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb"),"pnpId"); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final OperationQueue<Op> queue = new OperationQueue<>(32);
    private WebSocket socket; private String url, token, filter; private BluetoothGatt gatt; private BluetoothDevice device; private BluetoothGattCharacteristic tx, rx; private int generation; private boolean socketReady, discovery, announced, destroyed, helloSeen;
    private Op active;
    private Runnable operationTimeout;
    private Runnable setupTimeout;
    private Runnable scanRetry;
    private boolean ownsBluetooth, explicitStop;
    private boolean disconnectionSent;
    private final Runnable reconnect = () -> { if (!destroyed && socket == null && token != null) connectSocket(); };

    @Override public void onCreate() {
        super.onCreate();
        ownsBluetooth = HeadlessBridgeOwnership.claim();
        if (!ownsBluetooth) { stopSelf(); return; }
        if (!hasBlePermissions()) { ownsBluetooth = false; HeadlessBridgeOwnership.release(); stopSelf(); return; }
        startService(new Intent(this, WearableService.class).setAction(WearableServiceActions.ACTION_DISCONNECT_GLASSES));
        createChannel();
        startForeground(7, notification("Starting"));
    }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (!ownsBluetooth || destroyed) return START_NOT_STICKY;
        if (i != null && STOP.equals(i.getAction())) { explicitStop = true; stopSelf(); return START_NOT_STICKY; }
        if (i != null && socket == null) {
            String nextUrl = i.getStringExtra(URL);
            String nextToken = i.getStringExtra(TOKEN);
            if (!validConfiguration(nextUrl, nextToken)) { sendError("invalid bridge configuration"); stopSelf(); return START_NOT_STICKY; }
            url = nextUrl; token = nextToken == null ? "" : nextToken; filter = i.getStringExtra(FILTER); connectSocket();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(reconnect);
        closeGatt("service stopped");
        if (socket != null) socket.close(1000, "service stopped");
        socket = null; token = null;
        if (ownsBluetooth) {
            ownsBluetooth = false;
            HeadlessBridgeOwnership.release();
            if (explicitStop) startService(new Intent(this, WearableService.class).setAction(WearableServiceActions.ACTION_CONNECT_GLASSES));
        }
        http.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private boolean validConfiguration(String endpoint, String sharedToken) {
        return BridgeConfiguration.valid(endpoint, sharedToken);
    }

    private void connectSocket() {
        if (url == null || token == null || socket != null) return;
        Request.Builder request = new Request.Builder().url(url);
        String authorization = BridgeConfiguration.authorizationHeader(token);
        if (authorization != null) request.header("Authorization", authorization);
        Request req = request.build();
        socket = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) { main.post(() -> { if (s != socket || destroyed) return; socketReady=false; helloSeen=false; send(BridgeProtocol.hello()); }); }
            @Override public void onMessage(WebSocket s, String text) { main.post(() -> { if (s == socket && !destroyed) handle(text); }); }
            @Override public void onClosing(WebSocket s, int c, String reason) { s.close(c, reason); main.post(() -> { if (s == socket) { socketReady=false; closeGatt(reason); } }); }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) { main.post(() -> socketEnded(s, "websocket lost")); }
            @Override public void onClosed(WebSocket s, int c, String reason) { main.post(() -> socketEnded(s, reason)); }
        });
    }
    private void socketEnded(WebSocket s, String reason) { if (s != socket || destroyed) return; socket=null; socketReady=false; helloSeen=false; closeGatt(reason); main.removeCallbacks(reconnect); main.postDelayed(reconnect, 3000); }
    private void handle(String text) {
        if (text.length() > BridgeProtocol.MAX_FRAME) { sendError("frame too large"); return; }
        try {
            JSONObject o=new JSONObject(text); String type=o.optString("type");
            if ("hello".equals(type)) { if (helloSeen || o.optInt("version", -1)!=1) { sendError("invalid or duplicate hello"); return; } helloSeen=true; socketReady=true; startScan(); return; }
            if (!socketReady) { sendError("hello required"); return; }
            if ("write".equals(type)) handleWrite(o); else if ("disconnect".equals(type)) { if (device != null && sameDevice(o.optString("deviceId"))) { closeGatt("requested"); scheduleScanRetry(); } }
        } catch (Exception e) { sendError("invalid frame"); }
    }
    private void handleWrite(JSONObject o) {
        String requestId=o.optString("requestId", "");
        if (requestId.isEmpty() || !"tx".equals(o.optString("characteristic"))) { sendWriteResult(o.optString("deviceId"),requestId,false,"requestId and characteristic=tx are required"); return; }
        if (device == null || !announced || !sameDevice(o.optString("deviceId"))) { sendWriteResult(o.optString("deviceId"),requestId,false,"device is not connected"); return; }
        byte[] b; try { b=BridgeProtocol.data(o); } catch (Exception e) { sendWriteResult(device.getAddress(),o.optString("requestId"),false,e.getMessage()); return; }
        int mtu = gatt == null ? 23 : gatt.getDevice().equals(device) ? negotiatedMtu : 23;
        if (b.length > Math.max(0, mtu-3)) { sendWriteResult(device.getAddress(),requestId,false,"write exceeds negotiated MTU"); return; }
        if (queue.size() >= 32) { sendWriteResult(device.getAddress(),requestId,false,"write queue full"); return; }
        enqueue(Op.write(b,requestId)); pump();
    }
    private int negotiatedMtu=23;
    private void startScan() {
        if (!hasBlePermissions() || discovery) return; BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); BluetoothAdapter a=bm==null?null:bm.getAdapter(); if(a==null||!a.isEnabled()){sendError("Bluetooth unavailable");return;}
        discovery=true; ScanFilter sf=new ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(MAIN)).build(); ScanSettings ss=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(); a.getBluetoothLeScanner().startScan(Collections.singletonList(sf),ss,scanCallback); update("Scanning");
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (destroyed || !socketReady || gatt != null || !discovery) return;
                BluetoothDevice d = result.getDevice();
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                if (name == null) name = d.getName();
                if (filter == null || filter.trim().isEmpty() || d.getAddress().equalsIgnoreCase(filter.trim()) || (name != null && name.equalsIgnoreCase(filter.trim()))) {
                    stopScan(); connect(d);
                }
            });
        }
        @Override public void onScanFailed(int error) { main.post(() -> { if (!destroyed) { discovery=false; sendError("BLE scan failed: "+error); } }); }
    };
    private void stopScan(){ if(!discovery)return; discovery=false; BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); if(bm!=null&&bm.getAdapter()!=null&&bm.getAdapter().getBluetoothLeScanner()!=null&&hasBlePermissions())bm.getAdapter().getBluetoothLeScanner().stopScan(scanCallback); }
    private void connect(BluetoothDevice d){ closeGatt("reconnecting"); device=d; generation++; int gen=generation; if(!hasBlePermissions())return; gatt=d.connectGatt(this,false,new Callback(gen),BluetoothDevice.TRANSPORT_LE); setupTimeout=()->{if(gatt!=null&&generation==gen)failGatt("GATT setup timed out");}; main.postDelayed(setupTimeout,15000); update("Connecting"); }

    private final class Callback extends BluetoothGattCallback { final int gen; Callback(int g){gen=g;} private boolean live(BluetoothGatt g){return gen==generation&&gatt==g&&!destroyed;}
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){main.post(()->{if(!live(g))return;if(state==BluetoothProfile.STATE_CONNECTED){if(status!=BluetoothGatt.GATT_SUCCESS||!g.discoverServices())failGatt("service discovery start failed");}else if(state==BluetoothProfile.STATE_DISCONNECTED){closeGatt(status==0?"disconnected":"GATT status "+status);scheduleScanRetry();}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){main.post(()->{if(!live(g))return;if(setupTimeout!=null){main.removeCallbacks(setupTimeout);setupTimeout=null;}if(status!=BluetoothGatt.GATT_SUCCESS){failGatt("service discovery failed");return;}BluetoothGattService s=g.getService(MAIN);if(s==null){failGatt("main service not found");return;}rx=s.getCharacteristic(RX);tx=s.getCharacteristic(TX);if(rx==null||tx==null){failGatt("RX/TX characteristic missing");return;}enqueue(Op.mtu());enqueue(Op.enableNotifications());pump();});}
        @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){main.post(()->{if(!live(g)||active==null||active.kind!=Op.Kind.MTU)return;negotiatedMtu=status==BluetoothGatt.GATT_SUCCESS?Math.max(23,mtu):23;done();});}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){main.post(()->{if(!live(g)||active==null||active.kind!=Op.Kind.NOTIFY||!CCCD.equals(d.getUuid()))return;if(status!=BluetoothGatt.GATT_SUCCESS){failGatt("RX notification enable failed");return;}done();});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){main.post(()->{if(!live(g)||active==null||active.kind!=Op.Kind.WRITE||!TX.equals(c.getUuid()))return;Op op=active;sendWriteResult(device.getAddress(),op.requestId,status==BluetoothGatt.GATT_SUCCESS,status==0?null:"GATT write failed "+status);done();});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){main.post(()->{if(live(g)&&announced&&RX.equals(c.getUuid()))send(BridgeProtocol.value(device.getAddress(),"rx",v));});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v,int status){main.post(()->{if(!live(g)||active==null||active.kind!=Op.Kind.READ||!active.uuid.equals(c.getUuid()))return;Op op=active;if(status==BluetoothGatt.GATT_SUCCESS)send(BridgeProtocol.value(device.getAddress(),op.name,v));else sendError("read failed");done();});}
    }
    private void enqueue(Op op) { queue.offer(op); }
    private void pump() {
        if (active != null || queue.size() == 0 || gatt == null || !hasBlePermissions()) return;
        Op op = queue.begin();
        if (op == null) return;
        active = op;
        op.deadline = SystemClock.uptimeMillis() + 8000;
        boolean started = false;
        try {
            if (op.kind == Op.Kind.MTU) started = gatt.requestMtu(517);
            else if (op.kind == Op.Kind.NOTIFY) {
                BluetoothGattDescriptor descriptor = rx.getDescriptor(CCCD);
                started = descriptor != null && gatt.setCharacteristicNotification(rx, true);
                if (descriptor != null && started) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    started = started && gatt.writeDescriptor(descriptor);
                }
            } else if (op.kind == Op.Kind.WRITE) {
                tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                tx.setValue(op.data); started = gatt.writeCharacteristic(tx);
            } else if (op.kind == Op.Kind.READ) {
                BluetoothGattCharacteristic characteristic = findCharacteristic(op.uuid);
                started = characteristic != null && gatt.readCharacteristic(characteristic);
            }
        } catch (Exception ignored) { }
        if (!started) { failActive("GATT operation could not start"); return; }
        operationTimeout = () -> { if (active == op) failActive("GATT operation timed out"); };
        main.postDelayed(operationTimeout, 8100);
    }
    private BluetoothGattCharacteristic findCharacteristic(UUID uuid){if(gatt==null||gatt.getServices()==null)return null;for(BluetoothGattService service:gatt.getServices()){BluetoothGattCharacteristic c=service.getCharacteristic(uuid);if(c!=null)return c;}return null;}
    private void done() {
        if (active == null || queue.peek() != active) return;
        if (operationTimeout != null) main.removeCallbacks(operationTimeout);
        Op finished = queue.complete(active); active = null;
        if (finished != null && finished.kind == Op.Kind.NOTIFY) {
            announced = true; disconnectionSent = false;
            send(BridgeProtocol.connected(device.getAddress(), device.getName(), negotiatedMtu));
            enqueueOptionalReads();
        }
        pump();
    }
    private void failActive(String reason) {
        failGatt(reason);
    }
    private void enqueueOptionalReads(){if(gatt!=null&&gatt.getService(DEVICE_INFO)!=null){for(Map.Entry<UUID,String> e:INFO.entrySet()){BluetoothGattCharacteristic c=findCharacteristic(e.getKey());if(c!=null&&(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0)enqueue(Op.read(e.getKey(),e.getValue()));}}BluetoothGattService bs=gatt==null?null:gatt.getService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"));if(bs!=null){BluetoothGattCharacteristic c=bs.getCharacteristic(BATTERY);if(c!=null&&(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0)enqueue(Op.read(BATTERY,"batteryLevel"));}}
    private static final class Op {enum Kind{MTU,NOTIFY,WRITE,READ} Kind kind;byte[] data;String requestId,name;UUID uuid;long deadline;static Op mtu(){Op o=new Op();o.kind=Kind.MTU;return o;}static Op enableNotifications(){Op o=new Op();o.kind=Kind.NOTIFY;return o;}static Op write(byte[] b,String id){Op o=new Op();o.kind=Kind.WRITE;o.data=b;o.requestId=id;return o;}static Op read(UUID u,String n){Op o=new Op();o.kind=Kind.READ;o.uuid=u;o.name=n;return o;}}
    private boolean sameDevice(String id){return device!=null&&device.getAddress().equalsIgnoreCase(id);}
    private void failGatt(String reason) { sendError(reason); closeGatt(reason); scheduleScanRetry(); }
    private void scheduleScanRetry() {
        if (destroyed || !socketReady || scanRetry != null) return;
        scanRetry = () -> { scanRetry = null; if (!destroyed && socketReady) startScan(); };
        main.postDelayed(scanRetry, 1500);
    }
    private void sendDisconnected(String reason) {
        if (device != null && socketReady && announced && !disconnectionSent) {
            disconnectionSent = true; send(BridgeProtocol.disconnected(device.getAddress(), reason));
        }
    }
    private void failQueuedWrites(String reason) {
        for (Op op : queue.clearAndReturn()) if (op.kind == Op.Kind.WRITE)
            sendWriteResult(device == null ? "" : device.getAddress(), op.requestId, false, reason);
        active = null;
    }
    private void closeGatt(String reason) {
        stopScan(); generation++;
        if (operationTimeout != null) { main.removeCallbacks(operationTimeout); operationTimeout = null; }
        if (scanRetry != null) { main.removeCallbacks(scanRetry); scanRetry = null; }
        if (setupTimeout != null) { main.removeCallbacks(setupTimeout); setupTimeout = null; }
        if (device != null && reason != null) sendDisconnected(reason);
        failQueuedWrites(reason == null ? "GATT disconnected" : reason);
        announced = false;
        if (gatt != null && hasBlePermissions()) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; tx = null; rx = null; device = null; negotiatedMtu = 23;
    }
    private void send(JSONObject o){if(socket!=null&&socketReady||o.optString("type").equals("hello")){String s=o.toString();if(s.length()<=BridgeProtocol.MAX_FRAME&&socket!=null)socket.send(s);}}
    private void sendError(String s){try{send(BridgeProtocol.error(s));}catch(Exception ignored){}}
    private void sendWriteResult(String d,String id,boolean ok,String err){try{send(BridgeProtocol.writeResult(d,id,ok,err));}catch(Exception ignored){}}
    private boolean hasBlePermissions(){
        if (Build.VERSION.SDK_INT < 31) return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void update(String s){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7,notification(s));}
    private Notification notification(String text){return new Notification.Builder(this,"wearmux_bridge").setContentTitle("WearMux Bluetooth Bridge").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("wearmux_bridge","WearMux bridge",NotificationManager.IMPORTANCE_LOW));}
}
