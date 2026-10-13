package com.ifallhub.app;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(FFmpegPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
