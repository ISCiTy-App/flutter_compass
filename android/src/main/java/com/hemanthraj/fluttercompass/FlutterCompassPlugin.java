package com.hemanthraj.fluttercompass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;

public final class FlutterCompassPlugin implements FlutterPlugin, StreamHandler {
    private static final String TAG = "FlutterCompass";
    // The rate sensor events will be delivered at. As the Android documentation
    // states, this is only a hint to the system and the events might actually be
    // received faster or slower than this specified rate. Since the minimum
    // Android API levels about 9, we are able to set this value ourselves rather
    // than using one of the provided constants which deliver updates too quickly
    // for our use case. The default is set to 100ms
    private static final int SENSOR_DELAY_MICROS = 10 * 1000;

    // Filtering coefficient 0 < ALPHA < 1
    private static final float ALPHA = 0.97f;

    // Controls the compass update rate in milliseconds
    private static final int COMPASS_UPDATE_RATE_MS = 32;

    private SensorEventListener sensorEventListener;

    private Display display;
    private SensorManager sensorManager;

    @Nullable
    private Sensor gravitySensor;
    @Nullable
    private Sensor magneticFieldSensor;

    private float[] truncatedRotationVectorValue = new float[4];
    private float[] rotationMatrix = new float[9];
    private float lastHeading;
    private int lastAccuracySensorStatus;

    private long compassUpdateNextTimestamp;
    private float[] gravityValues = new float[3];
    private float[] magneticValues = new float[3];

    public FlutterCompassPlugin() {
        // no-op
    }

    private FlutterCompassPlugin(Context context) {
        display = ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
    }

    // New Plugin APIs

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        EventChannel channel = new EventChannel(binding.getBinaryMessenger(), "hemanthraj/flutter_compass");
        channel.setStreamHandler(new FlutterCompassPlugin(binding.getApplicationContext()));
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    }

    public void onListen(Object arguments, EventSink events) {
        sensorEventListener = createSensorEventListener(events);
        if (gravitySensor != null && magneticFieldSensor != null) {
            sensorManager.registerListener(sensorEventListener, gravitySensor, SENSOR_DELAY_MICROS);
            sensorManager.registerListener(sensorEventListener, magneticFieldSensor, SENSOR_DELAY_MICROS);
        } else {
            events.error("404", "Thiết bị này không hỗ trợ từ kế.\nChúng tôi rất tiếc vì không thể hỗ trợ bạn.", null);
        }
    }

    public void onCancel(Object arguments) {
        if (gravitySensor != null && magneticFieldSensor != null) {
            sensorManager.unregisterListener(sensorEventListener, gravitySensor);
            sensorManager.unregisterListener(sensorEventListener, magneticFieldSensor);
        }
    }

    SensorEventListener createSensorEventListener(final EventSink events) {
        return new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    Log.d(TAG, "Compass sensor is unreliable, device calibration is needed.");
                }

                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    gravityValues[0] = ALPHA * gravityValues[0] + (1 - ALPHA) * event.values[0];
                    gravityValues[1] = ALPHA * gravityValues[1] + (1 - ALPHA) * event.values[1];
                    gravityValues[2] = ALPHA * gravityValues[2] + (1 - ALPHA) * event.values[2];
                    updateOrientation();
                } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                    magneticValues[0] = ALPHA * magneticValues[0] + (1 - ALPHA) * event.values[0];
                    magneticValues[1] = ALPHA * magneticValues[1] + (1 - ALPHA) * event.values[1];
                    magneticValues[2] = ALPHA * magneticValues[2] + (1 - ALPHA) * event.values[2];
                    updateOrientation();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                if (lastAccuracySensorStatus != accuracy) {
                    lastAccuracySensorStatus = accuracy;
                }
            }

            @SuppressWarnings("SuspiciousNameCombination")
            private void updateOrientation() {
                // check when the last time the compass was updated, return if too soon.
                long currentTime = SystemClock.elapsedRealtime();
                if (currentTime < compassUpdateNextTimestamp) {
                    return;
                }
                SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magneticValues);


                // Transform rotation matrix into azimuth/pitch/roll
                float[] orientation = new float[3];
                SensorManager.getOrientation(rotationMatrix, orientation);

                double[] v = new double[3];
                v[0] = (Math.toDegrees(orientation[0]) + 360) % 360;
                v[2] = getAccuracy();
                // The x-axis is all we care about here.
                notifyCompassChangeListeners(v);

                // Update the compassUpdateNextTimestamp
                compassUpdateNextTimestamp = currentTime + COMPASS_UPDATE_RATE_MS;
            }

            private void notifyCompassChangeListeners(double[] heading) {
                events.success(heading);
                lastHeading = (float) heading[0];
            }

            private double getAccuracy() {
                if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) {
                    return 15;
                } else if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                    return 30;
                } else if (lastAccuracySensorStatus == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                    return 45;
                } else {
                    return -1; // unknown
                }
            }
        };
    }
}
