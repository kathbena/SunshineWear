/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gradle.kathleenbenavides.com.sunshinewear.R;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WearWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WearWatchFace.Engine> mWeakReference;

        public EngineHandler(WearWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WearWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Calendar mCalendar;

        //values for weather app
        private static final String SUNSHINE_PATH = "/weather";
        private static final String REQUEST_SUNSHINE_PATH = "/get-weather";
        private static final String HIGH_TEMP = "high";
        private static final String LOW_TEMP = "low";
        private static final String WEATHER_ID = "weatherId";
        private static final String RANDOM_UUID = "uuid";
        private int weatherId = 0;
        private final String TAG = WearWatchFace.class.getSimpleName();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WearWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API).build();

        //Declare all X Y offsets
        float mXOffsetTime;
        float mYOffsetTime;
        float mXOffsetDate;
        float mYOffsetDate;
        float mXOffsetTempHigh;
        float mYOffsetTempHigh;
        float mXOffsetTempLow;
        float mXOffsetWeatherIcon;
        float mYOffsetWeatherIcon;

        //Declare items and images for weather display
        Paint background;
        Paint mTextPaint;
        Paint textDate;
        Paint textDateAmbient;
        Paint tempHigh;
        Paint tempHighAmbient;
        Paint tempLow;
        Paint tempLowAmbient;
        Bitmap weatherImage;

        String highString;
        String lowString;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WearWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WearWatchFace.this.getResources();

            background = new Paint();
            background.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            textDate = new Paint();
            textDate = createTextPaint(resources.getColor(R.color.primary_text_light));

            textDateAmbient = new Paint();
            textDateAmbient = createTextPaint(resources.getColor(R.color.white));

            tempHigh = new Paint();
            tempHigh.setTypeface(Typeface.DEFAULT_BOLD);
            tempHigh.setColor(resources.getColor(R.color.white));
            tempHigh.setAntiAlias(true);

            tempLow = createTextPaint(resources.getColor(R.color.primary_text_light));

            Drawable weatherDrawable = resources.getDrawable(R.drawable.bg, null);
            weatherImage = ((BitmapDrawable) weatherDrawable).getBitmap();

            tempHighAmbient = createTextPaint(resources.getColor(R.color.white));
            tempLowAmbient = createTextPaint(resources.getColor(R.color.white));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();

                if(mGoogleApiClient != null && mGoogleApiClient.isConnected()){
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WearWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WearWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WearWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffsetTime = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.date_digital_x_offset_round : R.dimen.date_digital_x_offset);

            mXOffsetTempHigh = resources.getDimension(isRound
                    ? R.dimen.high_temp_x_offset_round : R.dimen.high_temp_x_offset);

            mXOffsetTempLow = resources.getDimension(isRound
                    ? R.dimen.low_temp_x_offset_round : R.dimen.low_temp_x_offset);

            mXOffsetWeatherIcon = resources.getDimension(isRound
                    ? R.dimen.weather_image_x_offset_round : R.dimen.weather_image_x_offset);

            mYOffsetTime = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            mYOffsetDate = resources.getDimension(isRound
                    ? R.dimen.date_digital_y_offset_round : R.dimen.date_digital_y_offset);

            mYOffsetTempHigh = resources.getDimension(isRound
                    ? R.dimen.high_temp_y_offset_round : R.dimen.high_temp_y_offset);

            mYOffsetWeatherIcon = resources.getDimension(isRound
                    ? R.dimen.weather_image_y_offset_round : R.dimen.weather_image_y_offset);

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float dateSize = resources.getDimension(isRound
                    ? R.dimen.date_digital_text_size_round : R.dimen.date_digital_text_size);

            float weatherSize = resources.getDimension(isRound
                    ? R.dimen.high_temp_text_size_round : R.dimen.high_temp_text_size);

            mTextPaint.setTextSize(textSize);
            textDate.setTextSize(dateSize);
            tempHigh.setTextSize(weatherSize);
            tempLow.setTextSize(weatherSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    boolean antiAlias = !inAmbientMode;
                    mTextPaint.setAntiAlias(antiAlias);
                    textDate.setAntiAlias(antiAlias);
                    tempHigh.setAntiAlias(antiAlias);
                    tempLow.setAntiAlias(antiAlias);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), background);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mCalendar.setTimeZone(TimeZone.getDefault());
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            //Format the time as HH:MM and draw to canvas
            String timeText =  String.format("%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            canvas.drawText(timeText, mXOffsetTime, mYOffsetTime, mTextPaint);

            //Format date to Day, Month Day Year and draw to canvas
            Date mDate = new Date();
            String date = new SimpleDateFormat("EEE,  MMM dd yyyy", Locale.ENGLISH).format(mDate);
            canvas.drawText(date, mXOffsetDate, mYOffsetDate, textDate);

            //check if we have strings and then assign them
            if(highString != null && lowString != null){
                canvas.drawText(highString, mXOffsetTempHigh, mYOffsetTempHigh, tempHigh);
                canvas.drawText(lowString, mXOffsetTempLow, mYOffsetTempHigh, tempLow);
            }

            //Get resource id and set it for drawable
            Drawable image = getResources().getDrawable(getSmallArtResourceIdForWeatherCondition(weatherId));
            weatherImage = ((BitmapDrawable) image).getBitmap();
            weatherImage = Bitmap.createScaledBitmap(weatherImage, 50, 50, true);
            canvas.drawBitmap(weatherImage, mXOffsetWeatherIcon, mYOffsetWeatherIcon, null);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            //Add the Data Listener
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            //request weather info for wear
            requestWeather();

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for(DataEvent event: dataEventBuffer) {

                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String dataPath = event.getDataItem().getUri().getPath();

                    Log.e(TAG, "Data type changed for path: " + dataPath);
                    //If there is a change from the app update the weather in wear
                    if(dataPath.equals(SUNSHINE_PATH)) {
                        Log.e(TAG, "Data from app has changed");
                        updateWeather(dataMap);
                    }
                }
            }

        }

        private void requestWeather() {

            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(REQUEST_SUNSHINE_PATH);
            //Random UUID so it can have a change and update
            putDataMapRequest.getDataMap().putString(RANDOM_UUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                @Override
                public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                    if (!dataItemResult.getStatus().isSuccess()) {
                        Log.e(TAG, "Data request failed");
                    } else {
                        Log.i(TAG, "Data request success");
                    }
                }
            });
        }



        private void updateWeather(DataMap data){

            Log.i(TAG, "Changes were found - update weather");

            //Update the wear items if the data has changed
            if(data.containsKey(WEATHER_ID)){
                weatherId = data.getInt(WEATHER_ID);
            }

            if(data.containsKey(HIGH_TEMP)){
                highString = data.getString(HIGH_TEMP);
            }

            if(data.containsKey(LOW_TEMP)){
                lowString = data.getString(LOW_TEMP);
            }

        }

        public int getSmallArtResourceIdForWeatherCondition(int weatherId) {

        /*
         * Based on weather code data for Open Weather Map.
         * Code snippet from app file SunshineWeatherUtils.java
         */
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 771 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            } else if (weatherId >= 900 && weatherId <= 906) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 958 && weatherId <= 962) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 951 && weatherId <= 957) {
                return R.drawable.ic_clear;
            }

            return R.drawable.ic_storm;
        }



    }
}
