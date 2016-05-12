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

package com.example.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceDigital extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String TAG = SunshineWatchFaceDigital.class.getSimpleName();

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
        private final WeakReference<SunshineWatchFaceDigital.Engine> mWeakReference;


        public EngineHandler(SunshineWatchFaceDigital.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceDigital.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextPaint;

        Paint mDatePaint;
        Paint mIconPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        String[] mDayNames;
        String[] mMonthNames;


        String mLowTemp;
        String mHighTemp;

        Bitmap mIcon;


        boolean mAmbient;
        Time mTime;

        GoogleApiClient mGoogleApiClient;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceDigital.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceDigital.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            //BackGround
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            //Time
            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();


            mDatePaint = new Paint();
            mDatePaint.setColor(resources.getColor(R.color.digital_text));
            mDatePaint.setTypeface(NORMAL_TYPEFACE);
            mDatePaint.setAntiAlias(true);
            //Icon
            mIconPaint = new Paint();
            //High Temp
            mHighTempPaint = new Paint();
            mHighTempPaint.setColor(resources.getColor(R.color.digital_text));
            mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
            mHighTempPaint.setAntiAlias(true);
            //Low Temp
            mLowTempPaint = new Paint();
            mLowTempPaint.setColor(resources.getColor(R.color.digital_text_light_blue));
            mLowTempPaint.setTypeface(NORMAL_TYPEFACE);
            mLowTempPaint.setAntiAlias(true);


            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceDigital.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();

            DateFormatSymbols symbols = new DateFormatSymbols();
            mDayNames = symbols.getShortWeekdays();
            mMonthNames = symbols.getShortMonths();
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
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
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
            SunshineWatchFaceDigital.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceDigital.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceDigital.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float dateSize = resources.getDimension(isRound ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);

            mTextPaint.setTextSize(textSize);

            mDatePaint.setTextSize(dateSize);
            mHighTempPaint.setTextSize(dateSize);
            mLowTempPaint.setTextSize(dateSize);
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
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
            Resources resources = SunshineWatchFaceDigital.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
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
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            //Get center
            float centerX = bounds.centerX();
            float centerY = bounds.centerY();
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            String text = mAmbient
                    ? String.format(Locale.ENGLISH, "%d:%02d", mTime.hour, mTime.minute)
                    : String.format(Locale.ENGLISH, "%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            String dateText = String.format(Locale.ENGLISH,
                    "%s, %s %d %d",
                    mDayNames[mTime.weekDay],
                    mMonthNames[mTime.month],
                    mTime.monthDay,
                    mTime.year
            );

            float dateTextSize = mDatePaint.measureText(dateText);
            float dateYOffset = mYOffset + getResources().getDimension(R.dimen.digital_time_text_margin_bottom);
            canvas.drawText(dateText.toUpperCase(), centerX - dateTextSize / 2, dateYOffset, mDatePaint);

            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            if (mHighTemp != null && mLowTemp != null) {
                float tempYOffset = dateYOffset + getResources().getDimension(R.dimen.digital_date_text_margin_bottom);
                //Icon
                if (mIcon != null && !mLowBitAmbient)
                    canvas.drawBitmap(mIcon, centerX - mIcon.getWidth() - mIcon.getWidth() / 4, tempYOffset - mIcon.getHeight() / 2, mIconPaint);
                //High temp
                canvas.drawText(mHighTemp, centerX, tempYOffset, mHighTempPaint);
                //Low temp
                float highTempSize = mHighTempPaint.measureText(mHighTemp);
                float highTempRightMargin = getResources().getDimension(R.dimen.digital_temp_text_margin_right);
                canvas.drawText(mLowTemp, centerX + highTempSize + highTempRightMargin, tempYOffset, mLowTempPaint);
            }

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
            Log.d(TAG, "Connected to Google Play...");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Disconnected from Google Play...");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "New data received");

            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/sunshine-temp-update") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        new GetBitmapForWeatherTask().execute(dataMap.getAsset("icon"));

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Google Play connection failed...");
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }


        public class GetBitmapForWeatherTask extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... assets) {
                Asset asset = assets[0];
                mIcon = loadBitmapFromAsset(asset);

                int size = Double.valueOf(SunshineWatchFaceDigital.this.getResources().getDimension(R.dimen.digital_icon_size)).intValue();
                mIcon = Bitmap.createScaledBitmap(mIcon, size, size, false);
                postInvalidate();

                return null;
            }
        }
    }
}
