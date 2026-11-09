package com.levi.pureswitch;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.levi.pureswitch.tuya.TuyaLanPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TuyaLanPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
