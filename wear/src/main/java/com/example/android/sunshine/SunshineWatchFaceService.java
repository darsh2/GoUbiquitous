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
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * <p>Modified by darshan on 26/3/17
 *
 * <p>Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    /**
     * The path in the Android Wear network where weather data can be fetched,
     */
    private static final String SUNSHINE_REST_PATH = "/sunshinerestpath";

    /**
     * Key for storing weather id in the {@link com.google.android.gms.wearable.DataMap DataMap}
     */
    private static final String KEY_WEATHER_ID = "weather_id";

    /**
     * Key for storing the temperature in the {@link com.google.android.gms.wearable.DataMap DataMap}
     */
    private static final String KEY_TEMPERATURE_RANGE = "temperature_range";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null && msg.what == MSG_UPDATE_TIME) {
                engine.handleUpdateTimeMessage();
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {
        final Handler updateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver timeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        boolean registeredTimeZoneReceiver = false;

        private GoogleApiClient googleApiClient;

        private Paint backgroundPaint;

        private String time;
        private Paint timePaint;

        private Calendar calendar;
        private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(
                "EEE, MMM d yyyy", Locale.getDefault());
        private String date;
        private Paint datePaint;

        private Paint dividerPaint;

        private int weatherId;
        private Bitmap weatherBitmap;

        private float weatherTemperaturePadding;

        private String temperatureRange;
        private Paint temperatureRangePaint;

        boolean isAmbient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean isLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            DebugLog.logMethod();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            buildGoogleApiClient();

            backgroundPaint = new Paint();
            backgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.material_blue_a400));

            calendar = Calendar.getInstance();
            date = simpleDateFormat.format(calendar.getTime());
            timePaint = createTextPaint(R.color.white, R.dimen.time_text_size);
            datePaint = createTextPaint(R.color.white, R.dimen.date_text_size);

            dividerPaint = new Paint();
            dividerPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.material_blue_50));

            loadDataFromSharedPreferences();

            createWeatherBitmap();
            weatherTemperaturePadding = getResources().getDimension(R.dimen.weather_temperature_padding);

            temperatureRangePaint = createTextPaint(R.color.white, R.dimen.temperature_text_size);
        }

        /**
         * Initializes {@link #weatherId} and {@link #temperatureRange} with the previously shown
         * values. If no such data is available, sets weatherId to -1 and temperatureRange to an
         * empty string.
         */
        private void loadDataFromSharedPreferences() {
            DebugLog.logMethod();
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            weatherId = sharedPreferences.getInt(KEY_WEATHER_ID, -1);
            temperatureRange = sharedPreferences.getString(KEY_TEMPERATURE_RANGE, "");
        }

        @Override
        public void onDestroy() {
            DebugLog.logMethod();
            backgroundPaint = null;
            timePaint = null;
            calendar = null;
            datePaint = null;
            dividerPaint = null;
            if (weatherBitmap != null && !weatherBitmap.isRecycled()) {
                weatherBitmap.recycle();
            }
            weatherBitmap = null;
            temperatureRangePaint = null;

            if (googleApiClient.isConnected() || googleApiClient.isConnecting()) {
                googleApiClient.disconnect();
            }
            googleApiClient = null;

            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        /**
         * Returns the desired Paint object with its typeface set to {@link #NORMAL_TYPEFACE},
         * color set to resource colorId, textSize set to dimension dimenId and antiAlias as
         * true.
         */
        private Paint createTextPaint(int colorId, int dimenId) {
            DebugLog.logMethod();
            Paint paint = new Paint();
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(getResources().getDimension(dimenId));
            paint.setColor(ContextCompat.getColor(getApplicationContext(), colorId));
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            DebugLog.logMethod();
            DebugLog.logMessage("Is visible: " + visible);

            if (visible) {
                onWallpaperVisible();
            } else {
                onWallpaperInvisible();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Connects googleApiClient and registers {@link #timeZoneReceiver} to actively udpate
         * view when the wallpaper is visible. Additionally resets the calendar timezone in case
         * it had changed when wallpaper was not visible. Calls invalidate to redraw WatchFace.
         */
        private void onWallpaperVisible() {
            DebugLog.logMethod();
            if (!googleApiClient.isConnected() && !googleApiClient.isConnecting()) {
                googleApiClient.connect();
            }

            registerReceiver();

            // Update time zone in case it changed while we weren't visible.
            calendar.setTimeZone(TimeZone.getDefault());
            invalidate();
        }

        /**
         * Disconnects googleApiClient and unregisters the {@link #timeZoneReceiver} when
         * wallpaper is not visible to save battery.
         */
        private void onWallpaperInvisible() {
            DebugLog.logMethod();
            if (googleApiClient != null
                    && (googleApiClient.isConnected()
                    || googleApiClient.isConnecting())) {
                googleApiClient.disconnect();
            }
            unregisterReceiver();
        }

        /**
         * Registers the {@link #timeZoneReceiver} to listen for changes in device time.
         */
        private void registerReceiver() {
            DebugLog.logMethod();
            if (registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = true;

            /*
            In addition to setting a filter for listening to timezone change,
            listen for time or date change as user can manually change time or
            date as well.
             */
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(timeZoneReceiver, filter);
        }

        /**
         * Unregisters {@link #timeZoneReceiver}.
         */
        private void unregisterReceiver() {
            DebugLog.logMethod();
            if (!registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(timeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            DebugLog.logMethod();
            // Same resources used for round and square watch faces
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            DebugLog.logMethod();

            /*
            Need not handle PROPERTY_BURNT_IN_PROTECTION because text shown use normal
            typeface, ie, no text is styled in bold.
             */

            isLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            DebugLog.logMethod();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            DebugLog.logMethod();
            if (isAmbient != inAmbientMode) {
                isAmbient = inAmbientMode;
                /*
                If isLowBitAmbient is true, disable antialias, else enable it.
                 */
                timePaint.setAntiAlias(!isLowBitAmbient);
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            Rect rect = new Rect();
            canvas.getClipBounds(rect);

            float viewItemWidth;
            float paddingX;

            float screenWidth = rect.width();
            float screenHeight = rect.height();
            float rowHeight = screenHeight / 4;

            calendar.setTimeInMillis(System.currentTimeMillis());
            time = String.format(Locale.ENGLISH, "%d:%02d",
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
            viewItemWidth = timePaint.measureText(time, 0, time.length());
            paddingX = (screenWidth - viewItemWidth) / 2;
            canvas.drawText(time, rect.left + paddingX, rowHeight * 1.25f, timePaint);

            /*
            Display only time when in ambient mode.
             */
            if (isInAmbientMode()) {
                return;
            }

            date = simpleDateFormat.format(calendar.getTime());
            viewItemWidth = datePaint.measureText(date, 0, date.length());
            paddingX = (screenWidth - viewItemWidth) / 2;
            canvas.drawText(date, rect.left + paddingX, rowHeight * 1.75f, datePaint);

            /*
            If no information is available about the weatherId and temperature range,
            do not draw rest of the views.
             */
            if (weatherId == -1 && temperatureRange.isEmpty()) {
                return;
            }

            viewItemWidth = screenWidth / 10;
            float dividerStartX = rect.left + viewItemWidth * 3;
            float dividerStopX = rect.right - viewItemWidth * 3;
            canvas.drawLine(dividerStartX, rect.centerY(), dividerStopX, rect.centerY(), dividerPaint);

            viewItemWidth = weatherBitmap.getWidth() + weatherTemperaturePadding +
                    + temperatureRangePaint.measureText(temperatureRange, 0, temperatureRange.length());
            paddingX = (screenWidth - viewItemWidth) / 2;
            canvas.drawBitmap(weatherBitmap, rect.left + paddingX, rowHeight * 2.25f, null);

            paddingX += weatherBitmap.getWidth() + weatherTemperaturePadding;
            canvas.drawText(temperatureRange, rect.left + paddingX, rowHeight * 2f + weatherBitmap.getHeight(), temperatureRangePaint);
        }

        /**
         * Starts the {@link #updateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            //DebugLog.logMethod();
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #updateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            //DebugLog.logMethod();
            //DebugLog.logMessage("... " + (isVisible() && !isInAmbientMode()));
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            //DebugLog.logMethod();
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void buildGoogleApiClient() {
            DebugLog.logMethod();
            googleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            DebugLog.logMethod();
            /*
            Register this class for dataChanges after googleApiClient is connected.
             */
            Wearable.DataApi.addListener(googleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            DebugLog.logMethod();
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            DebugLog.logMethod();
            DebugLog.logMessage(connectionResult.toString());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            DebugLog.logMethod();
            for (int i = 0, l = dataEventBuffer.getCount(); i < l; i++) {
                DataEvent dataEvent = dataEventBuffer.get(i);
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                String path = dataEvent.getDataItem().getUri().getPath();
                if (!path.equals(SUNSHINE_REST_PATH)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataEvent.getDataItem());
                DataMap dataMap = dataMapItem.getDataMap();
                if (dataMap == null) {
                    continue;
                }

                int newWeatherId = dataMap.getInt(KEY_WEATHER_ID, -1);
                String newTemperatureRange = dataMap.getString(KEY_TEMPERATURE_RANGE, "");
                DebugLog.logMessage("===New data - WeatherId: " + newWeatherId + ", TemperatureRange: " + newTemperatureRange + "===");
                /*
                If the newly received data is invalid or is the same as the old data, do nothing.
                 */
                if ((newWeatherId == -1 && newTemperatureRange.isEmpty())
                        || (newWeatherId == weatherId && newTemperatureRange.equals(temperatureRange))) {
                    DebugLog.logMessage("Invalid or stale data");
                    continue;
                }

                saveWeatherData();
                weatherId = newWeatherId;
                temperatureRange = newTemperatureRange;
                createWeatherBitmap();
                invalidate();
            }
        }

        /**
         * Saves the current weather data in the default {@link SharedPreferences}.
         */
        private void saveWeatherData() {
            DebugLog.logMethod();
            SharedPreferences.Editor editor = PreferenceManager
                    .getDefaultSharedPreferences(getApplicationContext())
                    .edit();
            editor.putInt(KEY_WEATHER_ID, weatherId);
            editor.putString(KEY_TEMPERATURE_RANGE, temperatureRange);
            editor.apply();
        }

        /**
         * Creates the weather bitmap from the {@link #weatherId}.
         */
        private void createWeatherBitmap() {
            DebugLog.logMethod();
            DebugLog.logMessage("WeatherId: " + weatherId);

            int weatherResourceId = SunshineWatchFaceService
                    .getSmallArtResourceIdForWeatherCondition(weatherId);
            if (weatherBitmap != null && !weatherBitmap.isRecycled()) {
                weatherBitmap.recycle();
            }
            weatherBitmap = BitmapFactory.decodeResource(getResources(), weatherResourceId);
        }
    }

    /**
     * Helper method to provide the icon resource id according to the weather condition id returned
     * by the OpenWeatherMap call.
     *
     * @param weatherId from OpenWeatherMap API response
     *                  See http://openweathermap.org/weather-conditions for a list of all IDs
     *
     * @return resource id for the corresponding icon. -1 if no relation is found.
     */
    public static int getSmallArtResourceIdForWeatherCondition(int weatherId) {
        DebugLog.logMethod();
        /*
         * Based on weather code data for Open Weather Map.
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
