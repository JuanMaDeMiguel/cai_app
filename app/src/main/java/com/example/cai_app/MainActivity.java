package com.example.cai_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // Curva lux->%: clamp(log10(lux+1)/log10(LUX_MAX)*100, 5, 100)
    private static final double LUX_MAX = 5000.0;
    private static final int THRESHOLD = 3;   // enviar solo si cambia >= 3%
    private static final java.util.UUID SPP_UUID =
            java.util.UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private BluetoothAdapter btAdapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothSocket socket;
    private OutputStream out;

    private boolean sending = false;
    private int lastSentPct = -100;

    private TextView luxText, brightnessText, statusText;
    private Button connectButton;
    private Switch sendSwitch;

    private ActivityResultLauncher<String> requestConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        luxText = findViewById(R.id.luxText);
        brightnessText = findViewById(R.id.brightnessText);
        statusText = findViewById(R.id.statusText);
        connectButton = findViewById(R.id.connectButton);
        sendSwitch = findViewById(R.id.sendSwitch);

        sensorManager = getSystemService(SensorManager.class);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        btAdapter = BluetoothAdapter.getDefaultAdapter();

        requestConnect = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) pickDeviceAndConnect();
                    else toast("Permiso Bluetooth denegado");
                });

        connectButton.setOnClickListener(v -> ensurePermissionThenConnect());
        sendSwitch.setOnCheckedChangeListener((b, checked) -> sending = checked);

        if (lightSensor == null) {
            luxText.setText("Este dispositivo no tiene sensor de luz");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeSocket();
        io.shutdownNow();
    }

    // ---- Sensor ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        int pct = luxToPercent(lux);
        luxText.setText(String.format("Lux: %.0f", lux));
        brightnessText.setText(pct + " %");

        if (sending && out != null && Math.abs(pct - lastSentPct) >= THRESHOLD) {
            lastSentPct = pct;
            send(pct);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static int luxToPercent(float lux) {
        double pct = Math.log10(lux + 1) / Math.log10(LUX_MAX) * 100.0;
        return (int) Math.max(5, Math.min(100, Math.round(pct)));
    }

    // ---- Bluetooth ----

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionThenConnect() {
        if (btAdapter == null) { toast("Bluetooth no disponible"); return; }
        if (!btAdapter.isEnabled()) { toast("Activa el Bluetooth"); return; }
        if (hasConnectPermission()) pickDeviceAndConnect();
        else requestConnect.launch(Manifest.permission.BLUETOOTH_CONNECT);
    }

    @SuppressLint("MissingPermission")
    private void pickDeviceAndConnect() {
        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded.isEmpty()) {
            toast("Emparejá la PC primero desde Ajustes de Bluetooth");
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            names[i] = devices.get(i).getName() + "\n" + devices.get(i).getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("Elegí la PC")
                .setItems(names, (d, which) -> connect(devices.get(which)))
                .show();
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        statusText.setText("Conectando...");
        io.execute(() -> {
            try {
                closeSocket();
                BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
                socket = s;
                out = s.getOutputStream();
                lastSentPct = -100;
                main.post(() -> {
                    statusText.setText("Conectado a " + device.getName());
                    sendSwitch.setEnabled(true);
                });
            } catch (Exception e) {
                closeSocket();
                main.post(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    sendSwitch.setEnabled(false);
                    sendSwitch.setChecked(false);
                });
            }
        });
    }

    private void send(int pct) {
        io.execute(() -> {
            try {
                out.write((pct + "\n").getBytes());
                out.flush();
            } catch (Exception e) {
                closeSocket();
                main.post(() -> {
                    statusText.setText("Desconectado");
                    sendSwitch.setEnabled(false);
                    sendSwitch.setChecked(false);
                });
            }
        });
    }

    private void closeSocket() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        out = null;
        socket = null;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
