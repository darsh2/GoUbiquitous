package com.example.android.sunshine.data;

import android.content.Context;
import android.database.Cursor;

import com.example.android.sunshine.utilities.SunshineWeatherUtils;

/**
 * Created by darshan on 26/3/17.
 */

public class WeatherWearable {
    static final String PREFERENCE_WEATHER_ID = "preference_weather_id";
    static final String PREFERENCE_TEMPERATURE_RANGE = "preference_temperature_range";

    private int weatherId;

    private String temperatureRange;

    private WeatherWearable() {
        this.weatherId = -1;
        this.temperatureRange = "";
    }

    /**
     * Returns the {@link WeatherWearable} object having set the {@link WeatherWearable#weatherId}
     * and {@link WeatherWearable#temperatureRange}. Returns null if cursor is null or is of size 0.
     * @param context context in which this method was called
     * @param cursor cursor containing the weather data
     */
    public static WeatherWearable getWeatherWearable(Context context, Cursor cursor) {
        if (cursor == null || cursor.getCount() == 0) {
            return null;
        }

        /*
        On orientation change, the cursor position was getting changed arbitrarily. Hence to ensure
        that today's weather data is what is always shown, move to the 0th position of the cursor
        and get the data.
         */
        cursor.moveToPosition(0);

        WeatherWearable weatherWearable = new WeatherWearable();
        int weatherId = cursor.getInt(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
        /*
        Use SunshineWeatherUtils.formatTemperature instead of SunshineWeatherUtils.formatHighLows because
        in Imperial mode, formatHighLows rounds the value whereas in the app's adapter, formatTemperature
        is used which is more accurate. Hence there is a difference in value shown in phone and the wearable.
        Therefore use formatTemperature itself.
         */
        String maxTemperature = SunshineWeatherUtils.formatTemperature(
                context,
                cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP))
        );
        maxTemperature = maxTemperature.trim();
        String minTemperature = SunshineWeatherUtils.formatTemperature(
                context,
                cursor.getDouble(cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP))
        );
        minTemperature = minTemperature.trim();
        String temperatureRange = maxTemperature + " / " + minTemperature;

        weatherWearable.setWeatherId(weatherId);
        weatherWearable.setTemperatureRange(temperatureRange);
        return weatherWearable;
    }

    WeatherWearable(int weatherId, String temperatureRange) {
        this.weatherId = weatherId;
        this.temperatureRange = temperatureRange;
    }

    public int getWeatherId() {
        return weatherId;
    }

    private void setWeatherId(int weatherId) {
        this.weatherId = weatherId;
    }

    public String getTemperatureRange() {
        return temperatureRange;
    }

    private void setTemperatureRange(String temperatureRange) {
        this.temperatureRange = temperatureRange;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WeatherWearable that = (WeatherWearable) o;
        return ((this.weatherId == that.weatherId)
                && (this.temperatureRange.equals(that.temperatureRange)));
    }

    @Override
    public int hashCode() {
        int result = weatherId;
        result = 31 * result + (temperatureRange != null ? temperatureRange.hashCode() : 0);
        return result;
    }
}
