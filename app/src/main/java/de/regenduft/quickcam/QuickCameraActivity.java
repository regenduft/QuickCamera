package de.regenduft.quickcam;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.hardware.Camera.Size;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.view.WindowManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.media.AudioManager;

public class QuickCameraActivity extends Activity implements SurfaceHolder.Callback, View.OnTouchListener,
        AutoFocusCallback, ShutterCallback, PictureCallback, OnGlobalLayoutListener, LocationListener {
    
    private enum CameraState {
        READY, PREVIEW, FOCUSSING, FOCUSSED, FOCUSFAIL, TAKING, SAVING
    }
    
    private enum ShutterSound {
        OFF, ON, LOW, CLICK
    }

    // configurable settings
    private ShutterSound bMuteShutter = ShutterSound.CLICK;
    private boolean bMuteShutterModified = false;
    private int bMuteShutterSystemVolumeBackup = -1;
    private int bMuteShutterMotoVolumeBackup = -1;
    private boolean bGps = true;
    private int bCameraOrientation = 90;
    private boolean bFrontCamera = false;

    // private members
    private Camera mCameraDevice = null;
    private Parameters mCameraParams = null;
    private SurfaceHolder mHolder = null;
    private FocusRectangle mFocusView = null;
    private View mCameraView; 
    private CameraState takingPictureStep = CameraState.READY;
    private boolean cancelFoto = false;
    private boolean layoutReady = false;
    private boolean mPreviewSizeIsSet = false;
    
    private LocationManager mLocationManager;
    private Location mLastLocationGPS = new Location(LocationManager.GPS_PROVIDER);
    private boolean mValidLocationGPS = false;
    private Location mLastLocationNET = new Location(LocationManager.NETWORK_PROVIDER);
    private boolean mValidLocationNET = false;

    private int mOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private OrientationEventListener mOrientationEventListener;
    
    private AudioManager mAudio = null;
    
    private MenuItem mFlashMenuItem;

    /**
     * create the options menu
     * @param menu the options menu
     * @return true
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final Intent galleryIntent = new Intent(Intent.ACTION_VIEW);
        galleryIntent.setDataAndType( Uri.fromFile(getMediaStorateDir()), Images.Media.CONTENT_TYPE);
        galleryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.add("Gallery").setIntent(galleryIntent);

        menu.add("Settings").setIntent(new Intent(getApplicationContext(), CameraPrefs.class));

        final String flashMenuText = mCameraDevice == null ? "Flash: n/a"
                : ("Flash: " + mCameraDevice.getParameters().getFlashMode());
        mFlashMenuItem = menu.add(flashMenuText);
        mFlashMenuItem.setOnMenuItemClickListener(
            new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    toggleFlash(item);
                    return true;
                }
            }
        );
        return true;
    }
    
    /**
     * Called when the activity is first created.
     * @param savedInstanceState saved state
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //make fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //initialize views
        setContentView(R.layout.main);

        mFocusView = (FocusRectangle)findViewById(R.id.focusView);
        
        mCameraView = findViewById(R.id.cameraView);
        mCameraView.setOnTouchListener(this);
        
        SurfaceHolder sh = ((SurfaceView)mCameraView).getHolder();
        sh.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        sh.addCallback(this);
        
        View mainL = findViewById(R.id.mainView);
        mainL.getViewTreeObserver().addOnGlobalLayoutListener(this);

        mOrientationEventListener = new OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    mOrientation = orientation;
                    
                }
            };
        }

    /**
     * initialize hardware
     */
    @Override
    protected void onResume() {
        super.onResume();

        // reset and open camera
        takingPictureStep = CameraState.READY;
        openCamera();
        startPreview();

        // listen to orientation changes
        if (mOrientationEventListener != null) {
            mOrientationEventListener.enable();
        }

        // initialize audio manager
        Object o = this.getSystemService(Context.AUDIO_SERVICE);
        if (o instanceof AudioManager) {
            mAudio = ((AudioManager)o);
        }
    }

    /**
     * release hardware
     */
    @Override
    protected void onPause() {
        super.onPause();

        releaseCamera();

        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
        }

        // re-enable system sound
        ensureShuttersound(true);
    }

    /**
     * initialize camera hardware
     */
    private synchronized void openCamera() {
        Log.d("QuickCamera", "Camera open");

        // reset camera
        if (mCameraDevice != null) {
            mCameraDevice.release();
        }

        mCameraDevice = android.hardware.Camera.open();

        // read configuration
        mCameraParams = mCameraDevice.getParameters();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        for (String stringParam: CameraPrefs.cameraParams) {
            CameraPrefs.setParam(mCameraDevice, mCameraParams, stringParam, prefs.getString(stringParam, null));
        }
        CameraPrefs.setParam(mCameraDevice, mCameraParams, "exposurecompensation",  prefs.getString("exposurecompensation", null));
        bGps = prefs.getBoolean("useGps", true);
        bMuteShutter = ShutterSound.valueOf(prefs.getString("shutterSound", ShutterSound.CLICK.name()));

        // apply configuration to hardware
        mCameraDevice.setParameters(mCameraParams);

        // update options menu according to new configuration
        if (mFlashMenuItem != null) {
            mFlashMenuItem.setTitle("Flash: " + mCameraParams.getFlashMode());
        }

        mPreviewSizeIsSet = false;
        ensurePreviewAspectRatio();
    }

    /**
     * release the camera hardware
     */
    private synchronized void releaseCamera() {
        if (mCameraDevice != null) {
            try {
                if (takingPictureStep.ordinal() >= CameraState.PREVIEW.ordinal()) {
                    mCameraDevice.stopPreview();
                }
            } finally {
                Log.d("QuickCamera", "Camera release");
                mCameraDevice.release();
                mCameraDevice = null;
                takingPictureStep = CameraState.READY;
            }
        }
    }

    /**
     * get geolocation and listen for updates
     */
    private synchronized void startReceivingLocationUpdates() {
        // initialize location manager
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }

        // get last known coarse network based location
        try {
            Location locNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (locNet != null) {
                mLastLocationNET.set(locNet);
            }
        } catch (java.lang.SecurityException ex) {
            Log.i("QuickCamera", "fail to request last location, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d("QuickCamera", "provider does not exist " + ex.getMessage());
        }

        // get last known fine gps based location
        try {
            Location locGps = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (locGps != null) {
                mLastLocationGPS.set(locGps);
            }
        } catch (java.lang.SecurityException ex) {
            Log.i("QuickCamera", "fail to request last location, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d("QuickCamera", "provider does not exist " + ex.getMessage());
        }

        // listen to network location updates (they may be available faster than gps location, but not as accurate)
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000,
                    0F,
                    this);
        } catch (java.lang.SecurityException ex) {
            Log.i("QuickCamera", "fail to request location update, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d("QuickCamera", "provider does not exist " + ex.getMessage());
        }

        // listen to gps location updates (they are more accurate and thus preferred, but may not always be available)
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0F,
                    this);
        } catch (java.lang.SecurityException ex) {
            Log.i("QuickCamera", "fail to request location update, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d("QuickCamera", "provider does not exist " + ex.getMessage());
        }
    }

    /**
     * stop listening for gps and/or network location
     */
    private void stopReceivingLocationUpdates() {
        if (mLocationManager != null) {
            try {
                mLocationManager.removeUpdates(this);
                mLocationManager.removeUpdates(this);
            } catch (Exception ex) {
                Log.i("QuickCamera", "fail to remove location listners, ignore", ex);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (bGps) {
            startReceivingLocationUpdates();
        }

    }
    
    @Override
    protected void onStop() {
        super.onStop();
        releaseCamera();
        stopReceivingLocationUpdates();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }

    /**
     * enable the camera preview
     */
    private synchronized void startPreview() {
        try {
            if (mCameraDevice != null && mHolder != null) {
                if (takingPictureStep.ordinal() <= CameraState.PREVIEW.ordinal()) {
                    Log.d("QuickCamera", "Preview display setup");
                    takingPictureStep = CameraState.PREVIEW;
                    mCameraDevice.setPreviewDisplay(mHolder);

                    // search and enable the preview size that is best matching screen aspect ratio
                    if (mCameraDevice.getParameters().getSupportedPreviewSizes() != null) {
                        Size picsSize = mCameraDevice.getParameters().getPictureSize();
                        int prefHeight = -1;
                        int holderHeight = Math.abs(mHolder.getSurfaceFrame().bottom - mHolder.getSurfaceFrame().top);
                        for (Size preSize: mCameraDevice.getParameters().getSupportedPreviewSizes()) {
                            if (Math.abs(preSize.height - holderHeight) < Math.abs(prefHeight - holderHeight)) {
                                prefHeight = preSize.height;
                            }
                        }
                        int perfectWidth =  (int) (((double)prefHeight) / ((double)picsSize.height) * ((double)picsSize.width));
                        int prefWidth = -1;
                        for (Size preSize: mCameraDevice.getParameters().getSupportedPreviewSizes()) {
                            if (preSize.height == prefHeight && (Math.abs(preSize.width - perfectWidth) < Math.abs(prefWidth - perfectWidth))) {
                                prefWidth = preSize.width;
                            }
                        } 
                        Parameters params = mCameraDevice.getParameters();
                        if (prefWidth != -1 && prefHeight != -1) {
                            params.setPreviewSize(prefWidth, prefHeight);
                            mCameraDevice.setParameters(params);
                        }
                        Log.d("set preview size", "perfect: " + perfectWidth + "x" + holderHeight  + ", best available:" + prefWidth + "x" + prefHeight);
                    }

                    // finally - enable the preview
                    mCameraDevice.startPreview();
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder pSurfaceHolder, int paramInt1,
            int paramInt2, int paramInt3) {
        if (mCameraDevice != null && takingPictureStep == CameraState.PREVIEW) {
            Log.d("QuickCamera", "Surface changed");
            mCameraDevice.stopPreview();
            takingPictureStep = CameraState.READY;
        } else {
            Log.d("QuickCamera", "Surface created");
        }
        mHolder = pSurfaceHolder;
        startPreview();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // better do nothing here, surfaceChanged will always be called afterwards.
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder pSurfaceHolder) {
        Log.d("QuickCamera", "Surface destroyed");
        mHolder = null;
        takingPictureStep = CameraState.READY;
        if (mCameraDevice != null) {
            Log.d("QuickCamera", "Preview stop");
            mCameraDevice.stopPreview();
        }
    }

    /**
     * actually take the picture (called when user releases the finger - touch MotionEvent.ACTION_UP)
     */
    private void takePicture() {
        if (mCameraDevice != null) {
            Parameters params = mCameraDevice.getParameters();

            // set orientation of the image to be taken
            if (mOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                int orientation = (mOrientation+45)/90*90;
                if (bFrontCamera) {
                    params.setRotation( (bCameraOrientation - orientation + 360) % 360 );
                } else {  // back-facing camera
                    params.setRotation( (bCameraOrientation + orientation) % 360 );
                }
            } else {
                params.setRotation(0);
            }

            // set the gps data of the image to be taken
            params.removeGpsData();
            params.setGpsTimestamp(System.currentTimeMillis() / 1000);
            if (bGps) {
                boolean gpsHasLatLon = mLastLocationGPS != null && (mLastLocationGPS.getLatitude() != 0.0d || mLastLocationGPS.getLongitude() != 0.0d);
                Location loc = mValidLocationGPS && gpsHasLatLon ? mLastLocationGPS : (mValidLocationNET ? mLastLocationNET : (gpsHasLatLon ? mLastLocationGPS : mLastLocationNET));
                if (loc != null) {
                    double lat = loc.getLatitude();
                    double lon = loc.getLongitude();
                    boolean hasLatLon = (lat != 0.0d) || (lon != 0.0d);
        
                    if (hasLatLon) {
                        params.setGpsLatitude(lat);
                        params.setGpsLongitude(lon);
                        if (loc.hasAltitude()) {
                            params.setGpsAltitude(loc.getAltitude());
                        } else {
                            params.setGpsAltitude(0);
                        }
                        if (loc.getTime() != 0) {
                            long utcTimeSeconds = loc.getTime() / 1000;
                            params.setGpsTimestamp(utcTimeSeconds);
                        }
                    }
                }
            }
    
            Log.d("QuickCamera", "Take picture start");

            // change sound settings to configured shutter sound
            ensureShuttersound(false);

            // take the picture
            mCameraDevice.setParameters(params);
            mCameraDevice.takePicture(this, null, null, this);
            takingPictureStep = CameraState.TAKING;
        }
    }

    /**
     * handle touch events:
     *  - autofocus while holding,
     *  - take picture when releasing
     * @param v view that has been touched
     * @param event the touch event
     * @return always true
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mCameraDevice != null) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // restart preview, if not yet active
                // autofocus, if preview already active
                cancelFoto = false;
                if (takingPictureStep == CameraState.READY) {
                    Log.d("QuickCamera", "Preview start");
                    mCameraDevice.startPreview();
                    mFocusView.clear();
                    takingPictureStep = CameraState.PREVIEW;
                } else if (takingPictureStep == CameraState.PREVIEW) {
                    Log.d("QuickCamera", "Autofocus start");
                    takingPictureStep = CameraState.FOCUSSING;
                    mFocusView.showStart();
                    mCameraDevice.autoFocus(this);
                } else {
                    Log.d("QuickCamera", "Picture taking in progress, ignoring touch event");
                }
            }
            else if (event.getAction() == MotionEvent.ACTION_UP) {
                // take picture (if not cancelled by moving to the border)
                if (cancelFoto) {
                    mCameraDevice.cancelAutoFocus();
                    takingPictureStep = CameraState.PREVIEW;
                    mFocusView.clear();
                } else if (takingPictureStep.ordinal() >= CameraState.FOCUSSING.ordinal() && takingPictureStep.ordinal() < CameraState.TAKING.ordinal()) {
                    takePicture();
                } else {
                    Log.d("QuickCamera", "Not focussing started or cancelFoto, not taking picture, ignoring touch event");
                }
            }
            else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                // cancel picture taking by moving to a screen border
                // uncancel when moving away from screen border again
                if (event.getX() < 25 || event.getX() > v.getWidth() - 25 || event.getY() < 25 || event.getY() > v.getHeight() - 25) {
                    mFocusView.clear();
                    cancelFoto = true;
                } else {
                    cancelFoto = false;
                    if (takingPictureStep == CameraState.PREVIEW) {
                        if (event.getEventTime() - event.getDownTime() > 650) {
                            Log.d("QuickCamera", "Autofocus restart");
                            takingPictureStep = CameraState.FOCUSSING;
                            mCameraDevice.autoFocus(this);
                        }
                    }
                    if (takingPictureStep == CameraState.FOCUSSED) {
                        mFocusView.showSuccess();
                    } else if(takingPictureStep == CameraState.FOCUSFAIL) {
                        mFocusView.showFail();
                    } else if(takingPictureStep == CameraState.FOCUSSING) {
                        mFocusView.showStart();
                    }
                }
            }
        }
        return true;
    }

    /**
     * Display the autofocus frame
     * @param success if focussing was successfull, display green frame, else red frame
     * @param camera the camera
     */
    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        Log.d("QuickCamera", "Autofocus callback " + success);
        if (bMuteShutter == ShutterSound.CLICK && mAudio != null) {
            mAudio.playSoundEffect(AudioManager.FX_KEY_CLICK, -1);
        }
        if (!cancelFoto && success) {
            mFocusView.showSuccess();
            takingPictureStep = CameraState.FOCUSSED;
        } else {
            mFocusView.showFail();
            takingPictureStep = CameraState.FOCUSFAIL;
        }
    }

    /**
     * ensure to play shutter sound if enabled
     */
    @Override
    public void onShutter() {
        Log.d("QuickCamera", "Shutter callback ");
        takingPictureStep = CameraState.SAVING;
        if (bMuteShutter == ShutterSound.CLICK && mAudio != null) {
            mAudio.playSoundEffect(AudioManager.FX_KEY_CLICK, -1);
        }
    }

    /**
     * save the picture
     * @param data the picture data to be saved
     * @param camera the camera
     */
    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        Log.d("QuickCamera", "Picture saved callback ");
        takingPictureStep = CameraState.READY;
        mFocusView.clear();
        ensureShuttersound(true);
        if (bMuteShutter == ShutterSound.CLICK && mAudio != null) {
            mAudio.playSoundEffect(AudioManager.FX_KEY_CLICK, -1);
        }

        File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
        if (pictureFile == null){
            Log.d("PictureTaken", "Error creating media file, check storage permissions: ");
            return;
        }

        try {
            FileOutputStream fos = new FileOutputStream(pictureFile);
            fos.write(data);
            fos.close();
        } catch (FileNotFoundException e) {
            Log.d("PictureTaken", "File not found: " + e.getMessage());
        } catch (IOException e) {
            Log.d("PictureTaken", "Error accessing file: " + e.getMessage());
        }

    }

    @Override
    public void onGlobalLayout() {
        layoutReady = true;
        View mainL = findViewById(R.id.mainView);
        mainL.getViewTreeObserver().removeGlobalOnLayoutListener(this);
        ensurePreviewAspectRatio();
    }

    /**
     * change the size of the preview according to the aspect ratio of the picture to be taken
     */
    private synchronized void ensurePreviewAspectRatio() {
        if (mCameraDevice != null && layoutReady && !mPreviewSizeIsSet) {
            Size size = mCameraDevice.getParameters().getPictureSize();
            if (size != null) {
                View mainL = findViewById(R.id.mainView);
                int cfWidth = (int) (((double) mainL.getHeight()) / ((double) size.height) * ((double) size.width));
                View spacerLeft = findViewById(R.id.spacerLeft);
                View spacerRight = findViewById(R.id.spacerRight);
                int spacerWidth = mainL.getWidth() - cfWidth;
                if (spacerWidth != 0) {
                    spacerWidth /= 2;
                }
                spacerLeft.setMinimumWidth(spacerWidth);
                spacerRight.setMinimumWidth(spacerWidth);
                spacerLeft.requestLayout();
                spacerRight.requestLayout();
                Log.d("Current Size", size.width + "x" + size.height + " --> min buttons width: " + (mainL.getWidth() - cfWidth) + ", min preview width: " + cfWidth);
                mPreviewSizeIsSet = true;
            }
        }
    }

    /**
     * gerneric method to toggle a configuration setting
     * @param available available settings
     * @param current current settings
     * @param <T> class type of the settings
     * @return the setting that is active after the method finishes
     */
    private <T> T toggleParam(List<T> available, T current) {
        T newMode = null;
        boolean found = true;
        if (available != null) {
            for (T mode: available) {
                if (mode != null && mode.equals(current)) {
                    found = true;
                } else if (found) {
                    newMode = mode;
                    found = false;
                }
            }
        }
        return newMode == null ? current : newMode;
    }


    /**
     * toggle the flash mode
     * @param v the flash menu item
     */
    public void toggleFlash(MenuItem v) {
        if (mCameraDevice != null) {
            Parameters params = mCameraDevice.getParameters();
            params.setFlashMode(toggleParam(params.getSupportedFlashModes(), params.getFlashMode()));
            mCameraDevice.setParameters(params);
            if (v != null) {
                v.setTitle("Flash: " + params.getFlashMode());
            }
        }
    }

    /**
     * Change the shuttersound to the configured setting
     * @param reEnable if true, we restore the default settings
     */
    private void ensureShuttersound(boolean reEnable) {
        if (mAudio != null) {
            if (reEnable) {
                // restore old shutter sound setting that were active before starting quickcamera
                if (bMuteShutterModified) {
                    try {
                        mAudio.setStreamMute(AudioManager.STREAM_SYSTEM, false);
                    } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                    if (bMuteShutterSystemVolumeBackup >= 0) {
                        try {
                            mAudio.setStreamVolume(AudioManager.STREAM_SYSTEM, bMuteShutterSystemVolumeBackup, 0);
                            bMuteShutterSystemVolumeBackup = -1;
                        } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                    }
                    try {
                        mAudio.setStreamMute(7, false);
                    } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                    if (bMuteShutterMotoVolumeBackup >= 0) {
                        try {
                            mAudio.setStreamVolume(7, bMuteShutterMotoVolumeBackup, 0);
                            bMuteShutterMotoVolumeBackup = -1;
                        } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                    }
                    bMuteShutterModified = false;
                }
            } else if (bMuteShutter != ShutterSound.ON) {
                // change shutter sound to configured setting
                bMuteShutterModified = true;
                if (bMuteShutter != ShutterSound.CLICK) {
                    try {
                        bMuteShutterSystemVolumeBackup = mAudio.getStreamVolume(AudioManager.STREAM_SYSTEM);
                        mAudio.setStreamVolume(AudioManager.STREAM_SYSTEM, bMuteShutter==ShutterSound.LOW ? (mAudio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)/4) : 0, 0);
                    } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                }
                try {
                    bMuteShutterMotoVolumeBackup = mAudio.getStreamVolume(7);
                    mAudio.setStreamVolume(7, bMuteShutter==ShutterSound.LOW ? (mAudio.getStreamMaxVolume(7)/4) : 0, 0);
                } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                if (bMuteShutter != ShutterSound.LOW) {
                    if (bMuteShutter != ShutterSound.CLICK) {
                        try {
                            mAudio.setStreamMute(AudioManager.STREAM_SYSTEM, true);
                        } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                    }
                    try {
                        mAudio.setStreamMute(7, true);
                    } catch (Exception e){/*if this stream doesnt exist, we cannot do anything*/}
                }
            }
        }
    }
    
    
    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;

    /** Create a file Uri for saving an image or video */
    private static Uri getOutputMediaFileUri(int type){
          return Uri.fromFile(getOutputMediaFile(type));
    }

    /**
     * Create a File for saving an image or video
     * @param type MEDIA_TYPE_IMAGE or  MEDIA_TYPE_VIDEO
     * @return File to write the media into
     */
    private static File getOutputMediaFile(int type){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = getMediaStorateDir();
        
        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d("QuickCamera", "failed to create directory " + mediaStorageDir.getAbsolutePath());
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
            "IMG_"+ timeStamp + ".jpg");
        } else if(type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
            "VID_"+ timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    /**
     * find the directory to store pictures
     * @return File representing the media storage directory
     */
    private static File getMediaStorateDir() {
        File mediaStorageDir = null;
        try {
            // This location works best if you want the created images to be shared
            // between applications and persist after your app has been uninstalled.
            String cons = (String)(Environment.class.getField("DIRECTORY_DCIM").get(null));
            mediaStorageDir = new File(
                (File) Environment.class.getMethod( "getExternalStoragePublicDirectory", new Class[]{String.class})
                       .invoke( null, cons ), 
                "QuickCamera" );
        } catch (IllegalArgumentException e) {
            Log.d("reflection", e.toString(),e);
        } catch (SecurityException e) {
            Log.d("reflection", e.toString(),e);
        } catch (IllegalAccessException e) {
            Log.d("reflection", e.toString(),e);
        } catch (InvocationTargetException e) {
            Log.d("reflection", e.toString(),e);
        } catch (NoSuchMethodException e) {
            Log.d("reflection", e.toString(),e);
        } catch (NoSuchFieldException e) {
            Log.d("reflection", e.toString(),e);
        }
        if (mediaStorageDir == null) {
            mediaStorageDir = new File(Environment.getExternalStorageDirectory(), "QuickCamera");
        }
        return mediaStorageDir;
    }

    /**
     * process location updates
     * @param location the location
     */
    @Override
    public void onLocationChanged(Location location) {
        if (location.getLatitude() == 0.0
                && location.getLongitude() == 0.0) {
            // Filter out 0.0,0.0 locations
            return;
        }
        
        if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(location.getProvider())) {
            mLastLocationGPS.set(location);
            mValidLocationGPS = true;
            if (location.getAccuracy() <= 300) {
                stopReceivingLocationUpdates();
            }
        } else {
            mLastLocationNET.set(location);
            mValidLocationNET = true;
            if (location.getAccuracy() <= 300) {
                stopReceivingLocationUpdates();
            }
        }
    }

    /**
     * listen to location provider disabled events
     * @param provider location provider that has been disabled
     */
    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(provider)) {
            mValidLocationGPS = false;
        } else {
            mValidLocationNET = false;
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        switch(status) {
            case LocationProvider.OUT_OF_SERVICE:
            case LocationProvider.TEMPORARILY_UNAVAILABLE: {
                if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(provider)) {
                    mValidLocationGPS = false;
                } else {
                    mValidLocationNET = false;
                }
                break;
            }
        }
    }
    
    
    
}