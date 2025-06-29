package com.example.barometerservice;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No UI needed for this activity, so no setContentView()

        // Start the BarometerService
        Intent serviceIntent = new Intent(this, BarometerService.class);
        startService(serviceIntent);

        // Finish this activity immediately so the user doesn't see an empty screen
        finish();
    }
}