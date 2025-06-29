package com.example.barometerservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

public class BarometerService extends Service implements SensorEventListener {

    private static final String TAG = "BarometerService";
    private static final String SERVICE_NAME = "BarometerNMEAService";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SPP UUID

    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private float currentPressure = 0.0f; // In hPa (millibars)

    private BluetoothAdapter bluetoothAdapter;
    private AcceptThread acceptThread;
    private ConnectedThread connectedThread;

    private Handler handler;
    private Runnable nmeaGeneratorRunnable;

    private static final long NMEA_UPDATE_INTERVAL = 1000; // 1 second

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        // Initialize Sensor Manager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            if (pressureSensor == null) {
                Log.e(TAG, "Barometer sensor not found!");
                // Handle gracefully, perhaps stop service or notify user
            }
        } else {
            Log.e(TAG, "SensorManager not available!");
        }

        // Initialize Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device!");
            // Handle gracefully, perhaps stop service
        }

        // Start the Bluetooth server thread
        if (bluetoothAdapter != null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }

        // Setup Handler for periodic NMEA string generation
        handler = new Handler();
        nmeaGeneratorRunnable = new Runnable() {
            @Override
            public void run() {
                if (connectedThread != null && connectedThread.isConnected()) {
                    String nmeaString = generateNMEAString(currentPressure);
                    connectedThread.write(nmeaString.getBytes());
                    Log.d(TAG, "Sent NMEA: " + nmeaString);
                } else {
                    Log.d(TAG, "Not connected to Bluetooth device. Skipping NMEA send.");
                }
                handler.postDelayed(this, NMEA_UPDATE_INTERVAL); // Schedule next run
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        // Make the service a foreground service
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "BarometerServiceChannel")
                .setContentTitle("Barometer Service")
                .setContentText("Reading pressure and sending via Bluetooth")
                .setSmallIcon(android.R.drawable.ic_dialog_info) // A generic icon
                .setTicker("Barometer Service Running")
                .build();
        startForeground(1, notification); // ID 1 for the notification

        // Register sensor listener
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Sensor listener registered.");
        }

        // Start the periodic NMEA generation
        handler.post(nmeaGeneratorRunnable);

        // We want this service to continue running until it is explicitly stopped
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");

        // Unregister sensor listener
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d(TAG, "Sensor listener unregistered.");
        }

        // Stop the periodic NMEA generation
        if (handler != null && nmeaGeneratorRunnable != null) {
            handler.removeCallbacks(nmeaGeneratorRunnable);
        }

        // Cancel Bluetooth threads
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        if (connectedThread != null) {
            connectedThread.cancel();
        }

        stopForeground(true); // Remove notification
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    // --- SensorEventListener Methods ---
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]; // Pressure in hPa (millibars)
            // Log.d(TAG, "Pressure: " + currentPressure + " hPa"); // Log for debugging
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used for this application
    }

    // --- NMEA String Generation ---
    private String generateNMEAString(float pressure) {
        // Format pressure to two decimal places
        String pressureStr = String.format(Locale.US, "%.2f", pressure);
        String data = String.format(Locale.US, "PBARO,%s,hPa", pressureStr);
        byte checksum = calculateChecksum(data);
        return String.format(Locale.US, "$%s*%02X\r\n", data, checksum); // Add CR/LF for NMEA
    }

    private byte calculateChecksum(String data) {
        byte checksum = 0;
        for (char c : data.toCharArray()) {
            checksum ^= (byte) c;
        }
        return checksum;
    }

    // --- Foreground Service Notification Channel (for Android 8.0+) ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "BarometerServiceChannel",
                    "Barometer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // --- Bluetooth Server Thread ---
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID, also used by the client code
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, MY_UUID);
                Log.d(TAG, "Bluetooth server socket created.");
            } catch (IOException e) {
                Log.e(TAG, "Socket's listen() method failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            // Keep listening until exception occurs or a socket is returned
            while (true) {
                try {
                    Log.d(TAG, "Waiting for Bluetooth connection...");
                    socket = mmServerSocket.accept(); // This is a blocking call
                } catch (IOException e) {
                    Log.e(TAG, "Socket's accept() method failed", e);
                    break;
                }

                if (socket != null) {
                    // A connection was accepted. Manage the connection in a separate thread.
                    Log.d(TAG, "Bluetooth connection accepted!");
                    manageConnectedSocket(socket);
                    try {
                        mmServerSocket.close(); // Close the server socket once a connection is established
                    } catch (IOException e) {
                        Log.e(TAG, "Could not close the connect socket", e);
                    }
                    break; // Exit the loop after accepting one connection
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        public void cancel() {
            try {
                mmServerSocket.close();
                Log.d(TAG, "Bluetooth server socket closed.");
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }
    }

    // --- Bluetooth Connected Thread ---
    private void manageConnectedSocket(BluetoothSocket socket) {
        // Start the thread to handle data transmission
        if (connectedThread != null) {
            connectedThread.cancel(); // Cancel any existing connection
        }
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final OutputStream mmOutStream;
        private boolean isConnected = false;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpOut = socket.getOutputStream();
                isConnected = true;
                Log.d(TAG, "Bluetooth output stream obtained.");
            } catch (IOException e) {
                Log.e(TAG, "Error creating output stream", e);
                isConnected = false; // Mark as disconnected
            }
            mmOutStream = tmpOut;
        }

        public void run() {
            // Keep alive as long as the connection is active
            Log.d(TAG, "ConnectedThread running.");
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes) {
            try {
                if (mmOutStream != null) {
                    mmOutStream.write(bytes);
                }
            }
            catch (IOException e) {
                Log.e(TAG, "Error during write", e);
                isConnected = false; // Mark as disconnected
            }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
                isConnected = false;
                Log.d(TAG, "Bluetooth socket closed.");
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }

        public boolean isConnected() {
            return isConnected;
        }
    }
}
