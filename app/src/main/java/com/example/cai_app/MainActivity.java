package com.example.cai_app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
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
                    else toast("Permission Bluetooth refusée");
                });

        connectButton.setOnClickListener(v -> ensurePermissionThenConnect());
        sendSwitch.setOnCheckedChangeListener((b, checked) -> sending = checked);

        if (lightSensor == null) {
            luxText.setText("Cet appareil n'a pas de capteur de lumière");
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
        luxText.setText(String.format("Lux : %.0f", lux));
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
        if (btAdapter == null) { toast("Bluetooth non disponible"); return; }
        if (!btAdapter.isEnabled()) { toast("Activez le Bluetooth"); return; }
        if (hasConnectPermission()) pickDeviceAndConnect();
        else requestConnect.launch(Manifest.permission.BLUETOOTH_CONNECT);
    }

    @SuppressLint("MissingPermission")
    private void pickDeviceAndConnect() {
        List<BluetoothDevice> devices = connectableDevices(btAdapter.getBondedDevices());
        if (devices.isEmpty()) {
            toast("Associez d'abord le PC dans les paramètres Bluetooth");
            return;
        }
        DeviceAdapter adapter = new DeviceAdapter(this, devices);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Choisissez l'ordinateur")
                .setAdapter(adapter, (d, which) -> connect(devices.get(which)))
                .setNegativeButton("Annuler", null)
                .show();
    }

    /**
     * Filtra los dispositivos vinculados para mostrar solo los relevantes:
     * descarta los que no tienen nombre legible (solo MAC), elimina duplicados
     * por dirección y prioriza las computadoras (el destino de la app). Si no hay
     * ninguna computadora vinculada, devuelve igual el resto para no quedar vacío.
     */
    @SuppressLint("MissingPermission")
    private List<BluetoothDevice> connectableDevices(Set<BluetoothDevice> bonded) {
        List<BluetoothDevice> computers = new ArrayList<>();
        List<BluetoothDevice> others = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BluetoothDevice d : bonded) {
            String name = d.getName();
            if (name == null || name.trim().isEmpty()) continue;   // solo MAC, no sirve
            if (!seen.add(d.getAddress())) continue;               // duplicado
            if (isComputer(d)) computers.add(d);
            else others.add(d);
        }
        return computers.isEmpty() ? others : computers;
    }

    private static boolean isComputer(BluetoothDevice d) {
        BluetoothClass cls = d.getBluetoothClass();
        return cls != null
                && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER;
    }

    /** Lista cada dispositivo con icono, nombre y dirección (item_device.xml). */
    private static class DeviceAdapter extends ArrayAdapter<BluetoothDevice> {
        DeviceAdapter(Context context, List<BluetoothDevice> items) {
            super(context, 0, items);
        }

        @SuppressLint("MissingPermission")
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_device, parent, false);
            }
            BluetoothDevice d = getItem(position);
            ImageView icon = row.findViewById(R.id.deviceIcon);
            TextView name = row.findViewById(R.id.deviceName);
            TextView address = row.findViewById(R.id.deviceAddress);

            name.setText(d.getName());
            address.setText(d.getAddress());
            icon.setImageResource(isComputer(d)
                    ? R.drawable.ic_computer : R.drawable.ic_bluetooth);
            return row;
        }
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        statusText.setText("Connexion...");
        io.execute(() -> {
            try {
                closeSocket();
                BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
                socket = s;
                out = s.getOutputStream();
                lastSentPct = -100;
                main.post(() -> {
                    statusText.setText("Connecté à " + device.getName());
                    sendSwitch.setEnabled(true);
                });
            } catch (Exception e) {
                closeSocket();
                main.post(() -> {
                    statusText.setText("Erreur : " + e.getMessage());
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
                    statusText.setText("Déconnecté");
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
