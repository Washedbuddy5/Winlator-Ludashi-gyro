package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.WindowManager;

public class GyroPointerController implements SensorEventListener {

    public interface PointerInjector {
        void movePointerRelative(float dx, float dy);
    }

    private final Context context;
    private final PointerInjector injector;
    private final SensorManager sensorManager;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private Sensor sensor;
    private boolean listening = false;
    private boolean enabled = false;
    private boolean calibrateNext = true;

    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] lastOrientation = new float[3];

    // Higher = faster cursor movement.
    private float sensitivity = 12.0f;

    // Ignores tiny hand shake.
    private float deadzoneDegrees = 0.35f;

    // Prevents huge jumps.
    private float maxDegreesPerEvent = 8.0f;

    private boolean invertX = false;
    private boolean invertY = false;

    private float pendingDX = 0f;
    private float pendingDY = 0f;
    private boolean flushScheduled = false;

    public GyroPointerController(Context context, PointerInjector injector) {
        this.context = context.getApplicationContext();
        this.injector = injector;
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        if (listening || sensorManager == null) return;

        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);

        if (sensor == null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        if (sensor == null) {
            return;
        }

        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        listening = true;
    }

    public void stop() {
        if (!listening) return;

        sensorManager.unregisterListener(this);
        listening = false;

        uiHandler.removeCallbacksAndMessages(null);
        flushScheduled = false;
        pendingDX = 0f;
        pendingDY = 0f;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        if (!enabled) {
            calibrateNext = true;
            pendingDX = 0f;
            pendingDY = 0f;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void calibrate() {
        calibrateNext = true;
        pendingDX = 0f;
        pendingDY = 0f;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public void setDeadzoneDegrees(float deadzoneDegrees) {
        this.deadzoneDegrees = deadzoneDegrees;
    }

    public void setInvertX(boolean invertX) {
        this.invertX = invertX;
    }

    public void setInvertY(boolean invertY) {
        this.invertY = invertY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!enabled || !listening || sensor == null) return;
        if (event.sensor.getType() != sensor.getType()) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        remapForCurrentDisplay(rotationMatrix, remappedMatrix);
        SensorManager.getOrientation(remappedMatrix, orientation);

        if (calibrateNext) {
            System.arraycopy(orientation, 0, lastOrientation, 0, 3);
            calibrateNext = false;
            pendingDX = 0f;
            pendingDY = 0f;
            return;
        }

        float yawDelta = wrapAngle(orientation[0] - lastOrientation[0]);
        float pitchDelta = wrapAngle(orientation[1] - lastOrientation[1]);

        System.arraycopy(orientation, 0, lastOrientation, 0, 3);

        float dxDegrees = applyDeadzone((float) Math.toDegrees(yawDelta));
        float dyDegrees = applyDeadzone((float) Math.toDegrees(pitchDelta));

        float dx = dxDegrees * sensitivity;
        float dy = dyDegrees * sensitivity;

        float maxDelta = maxDegreesPerEvent * sensitivity;

        dx = clamp(dx, -maxDelta, maxDelta);
        dy = clamp(dy, -maxDelta, maxDelta);

        if (invertX) dx = -dx;

        // Usually tilting forward should move cursor up.
        if (!invertY) dy = -dy;

        pendingDX += dx;
        pendingDY += dy;

        scheduleFlush();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed.
    }

    private void scheduleFlush() {
        if (flushScheduled) return;

        flushScheduled = true;

        // Around 120 Hz. Lower number = more responsive but more CPU usage.
        uiHandler.postDelayed(() -> {
            flushScheduled = false;

            float dx = pendingDX;
            float dy = pendingDY;

            pendingDX = 0f;
            pendingDY = 0f;

            if (dx != 0f || dy != 0f) {
                injector.movePointerRelative(dx, dy);
            }
        }, 8);
    }

    private void remapForCurrentDisplay(float[] in, float[] out) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        int rotation = Surface.ROTATION_0;

        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            rotation = windowManager.getDefaultDisplay().getRotation();
        }

        int axisX;
        int axisY;

        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;

            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;

            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;

            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }

        SensorManager.remapCoordinateSystem(in, axisX, axisY, out);
    }

    private float applyDeadzone(float degrees) {
        float abs = Math.abs(degrees);

        if (abs < deadzoneDegrees) {
            return 0f;
        }

        float sign = Math.signum(degrees);
        return sign * (abs - deadzoneDegrees);
    }

    private static float wrapAngle(float angle) {
        while (angle > (float) Math.PI) {
            angle -= 2f * (float) Math.PI;
        }

        while (angle < -(float) Math.PI) {
            angle += 2f * (float) Math.PI;
        }

        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
          }
