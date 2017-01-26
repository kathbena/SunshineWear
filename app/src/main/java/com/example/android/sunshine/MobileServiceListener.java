package com.example.android.sunshine;

import android.util.Log;

import com.example.android.sunshine.sync.SunshineSyncUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by kathleenbenavides on 1/15/17.
 */

public class MobileServiceListener extends WearableListenerService {

    private static final String REQUEST_SUNSHINE_PATH = "/get-weather";
    private final String TAG = MobileServiceListener.class.getSimpleName();

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent dataEvent : dataEventBuffer) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                String dataPath = dataEvent.getDataItem().getUri().getPath();
                if (dataPath.equals(REQUEST_SUNSHINE_PATH)) {
                    Log.i(TAG, "Wear requesting data from app");
                    SunshineSyncUtils.startImmediateSync(this);
                }
            }

        }
    }
}
