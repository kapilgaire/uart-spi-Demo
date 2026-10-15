package com.quectel.demo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EnvSet.initEnv(this.getApplicationInfo().nativeLibraryDir);

        Button btnGpio     = findViewById(R.id.btnGpio);
        Button btnUart     = findViewById(R.id.btnUart);
        Button btnI2c      = findViewById(R.id.btnI2c);
        Button btnSpi      = findViewById(R.id.btnSpi);
        Button btnGesture  = findViewById(R.id.btnGesture);
        Button btnOled     = findViewById(R.id.btnOled);
        Button btnOledI2c  = findViewById(R.id.btnOledI2c);
        Button btnTestRight = findViewById(R.id.btnTestRight);
        Button btnTest     = findViewById(R.id.btnTest);

        btnGpio.setOnClickListener(v ->
                startActivity(new Intent(this, GpioActivity.class)));

        btnUart.setOnClickListener(v ->
                startActivity(new Intent(this, UartDemoActivity.class)));

        btnI2c.setOnClickListener(v ->
                startActivity(new Intent(this, I2cActivity.class)));

        btnSpi.setOnClickListener(v ->
                startActivity(new Intent(this, SpiActivity.class)));

        btnGesture.setOnClickListener(v ->
                startActivity(new Intent(this, GestureActivity.class)));



        btnOledI2c.setOnClickListener(v ->
                startActivity(new Intent(this, OledI2cActivity.class)));



        btnTest.setOnClickListener(v ->
                startActivity(new Intent(this, OledI2cActivity.class)));
    }
}
