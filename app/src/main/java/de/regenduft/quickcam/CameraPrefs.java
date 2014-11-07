/*
 ******************************************************************************
 * Parts of this code sample are licensed under Apache License, Version 2.0   *
 * Copyright (c) 2009, Android Open Handset Alliance. All rights reserved.    *
 *                                                                            *                                                                         *
 * Except as noted, this code sample is offered under a modified BSD license. *
 * Copyright (C) 2010, Motorola Mobility, Inc. All rights reserved.           *
 *                                                                            *
 * For more details, see MOTODEV_Studio_for_Android_LicenseNotices.pdf        * 
 * in your installation folder.                                               *
 ******************************************************************************
 */

package de.regenduft.quickcam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Log;

import de.regenduft.quickcam.R;

/***
 * PreferenceActivity is a built-in Activity for preferences management
 * 
 * To retrieve the values stored by this activity in other activities use the
 * following snippet:
 * 
 * SharedPreferences sharedPreferences =
 * PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
 * <Preference Type> preferenceValue = sharedPreferences.get<Preference
 * Type>("<Preference Key>",<default value>);
 */
public class CameraPrefs extends PreferenceActivity {
    
    public static final String[] cameraParams = new String[] {"antibanding", "coloreffect", "flashmode", "focusmode", "scenemode", "whitebalance", "picturesize"};

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        PreferenceScreen ps = getPreferenceScreen();
        Preference csp = ps.findPreference("camerasettings");
        if (csp instanceof PreferenceGroup) {
            Log.d("CameraPrefs", "searching camera settings");
            PreferenceGroup cs = (PreferenceGroup)csp;
            Camera cam = Camera.open();
            Parameters param = cam.getParameters();
            cam.release();
            int expoMin = 0;
            int expoMax = 0;
            float expoStep = 0.0f;
            int expoVal = 0;
            
            // search camera param "getSupportedXXX" with reflection
            Method[] methods = Parameters.class.getMethods();
            final String PSUPPORTED = "getSupported";
            if (methods != null) {
                for (Method supported: methods) {
                    if (supported != null && supported.getName() != null && supported.getName().startsWith(PSUPPORTED) && supported.getParameterTypes().length == 0) {
                        for (String nameMatch: cameraParams) {
                            if (supported.getName().toLowerCase().startsWith(PSUPPORTED.toLowerCase() + nameMatch)) {
                                ListPreference lp = findGetterAndCreateListParam(PSUPPORTED, supported, methods, param);
                                if (lp != null) {
                                    lp.setKey(nameMatch);
                                    cs.addPreference(lp);
                                }
                            }
                        }
                    }
                    else if (supported != null && "getMinExposureCompensation".equals(supported.getName()) && int.class.equals(supported.getReturnType())) {
                        try {
                            expoMin = (Integer)supported.invoke(param);
                        } catch (IllegalArgumentException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalAccessException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (InvocationTargetException e) {
                            Log.d("CameraPrefs", e.toString());
                        }
                    }
                    else if (supported != null && "getMaxExposureCompensation".equals(supported.getName()) && int.class.equals(supported.getReturnType())) {
                        try {
                            expoMax = (Integer)supported.invoke(param);
                        } catch (IllegalArgumentException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalAccessException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (InvocationTargetException e) {
                            Log.d("CameraPrefs", e.toString());
                        }
                    }
                    else if (supported != null && "getExposureCompensationStep".equals(supported.getName()) && float.class.equals(supported.getReturnType())) {
                        try {
                            expoStep = (Float)supported.invoke(param);
                        } catch (IllegalArgumentException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalAccessException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (InvocationTargetException e) {
                            Log.d("CameraPrefs", e.toString());
                        }
                    }
                    else if (supported != null && "getExposureCompensation".equals(supported.getName()) && int.class.equals(supported.getReturnType())) {
                        try {
                            expoVal = (Integer)supported.invoke(param);
                        } catch (IllegalArgumentException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalAccessException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (InvocationTargetException e) {
                            Log.d("CameraPrefs", e.toString());
                        }
                    }
                }
            }
            if (expoMin != 0 && expoMax != 0 && expoStep != 0.0f) {
                ListPreference lp = new ListPreference(this);
                lp.setKey("exposurecompensation");
                lp.setTitle("Exposure Compensation");
                lp.setDefaultValue(String.valueOf(expoVal));
                List<String> values = new ArrayList<String>();
                List<String> labels = new ArrayList<String>();
                for (int i = expoMin; i <= expoMax; i+=1) {
                    values.add(String.valueOf(i));
                    double val = ((double)i)*((double)expoStep)*100.0;
                    labels.add((i < 0 ?"-":"")+String.valueOf((int)(Math.abs(val/100.0)))+"."+String.valueOf(Math.round(Math.abs(val))%100));
                }
                lp.setEntryValues(values.toArray(new String[0]));
                lp.setEntries(labels.toArray(new String[0]));
                cs.addPreference(lp);
            }
        }
    }
    
    private ListPreference findGetterAndCreateListParam(String prefix, Method supported, Method[] methods, Parameters param) {
        // try to find the according getter method for current setting
        String PGETTER = "get" + supported.getName().substring(prefix.length(), supported.getName().length()-1);
        for (Method getter: methods) {
            if (getter != null && getter.getName() != null && getter.getName().startsWith(PGETTER) && getter.getParameterTypes().length == 0) {
                Log.d("CameraPrefs", supported.getName() + " - " + getter.getName());
                try {
                    Object oVal = supported.invoke(param);
                    if (oVal instanceof List) {
                        @SuppressWarnings("rawtypes")
                        List values = (List)oVal;
                        String defaultValue = toString(getter.invoke(param));
                        Log.d("CameraPrefs", defaultValue + " - " + Arrays.deepToString(values.toArray()));
                        if (values.size() > 0) {
                            ListPreference lp = new ListPreference(this);
                            lp.setKey(getter.getName());
                            lp.setTitle(getter.getName().substring(3));
                            lp.setDefaultValue(defaultValue);
                            String[] eValues = new String[values.size()];
                            String[] entries= new String[values.size()];
                            for (int i = 0; i < values.size(); i++) {
                                String val = toString(values.get(i));
                                eValues[i] = val;
                                entries[i] = val.substring(0,1).toUpperCase() + val.substring(1).toLowerCase(); 
                            }
                            lp.setEntryValues(eValues);
                            lp.setEntries(entries);
                            return lp;
                        }
                    }
                } catch (InvocationTargetException e) {
                    Log.d("CameraPrefs", e.toString());
                } catch (IllegalAccessException e) {
                    Log.d("CameraPrefs", e.toString());
                } catch (IllegalArgumentException e) {
                    Log.d("CameraPrefs", e.toString());
                }
            }
        }
        return null;
    }
    
    private String toString(Object o) {
        if (o==null) return "";
        if (o instanceof Camera.Size) {
            return ((Camera.Size)o).width + "x" + ((Camera.Size)o).height; 
        }
        return String.valueOf(o);
    }

    public static void setParam(Camera mCameraDevice, Camera.Parameters mCameraParams, String param, String value) {
        if (value != null) {
            Method[] methods = Parameters.class.getMethods();
            final String PSET = "set" + param.toLowerCase();
            if (methods != null) {
                for (Method setter: methods) {
                    if (setter != null && setter.getName() != null && setter.getName().toLowerCase().startsWith(PSET) && (setter.getParameterTypes().length == 1 || setter.getParameterTypes().length == 2)) {
                        try {
                            if (setter.getParameterTypes().length == 1 && setter.getParameterTypes()[0].equals(String.class)) {
                                Log.d("CameraPrefs", "settting " + setter.getName() + " to " + value);
                                setter.invoke(mCameraParams, value);
                            }
                            else if (setter.getParameterTypes()[0].equals(Size.class)) {
                                String[] sSize = value.split("x");
                                if (sSize.length == 2) {
                                    Size sVal = mCameraDevice.new Size(Integer.parseInt(sSize[0]), Integer.parseInt(sSize[1]));
                                    Log.d("CameraPrefs", "settting " + setter.getName() + " to " + sVal);
                                    setter.invoke(mCameraParams, sVal);
                                }
                            }
                            else if (setter.getParameterTypes().length == 2 && setter.getParameterTypes()[0].equals(int.class) && setter.getParameterTypes()[1].equals(int.class)) {
                                String[] sSize = value.split("x");
                                if (sSize.length == 2) {
                                    Log.d("CameraPrefs", "settting " + setter.getName() + " to " + Integer.parseInt(sSize[0])+"x"+Integer.parseInt(sSize[1]));
                                    setter.invoke(mCameraParams, Integer.parseInt(sSize[0]), Integer.parseInt(sSize[1]));
                                }
                            }
                            else if (setter.getParameterTypes().length == 1 && setter.getParameterTypes()[0].equals(int.class)) {
                                Log.d("CameraPrefs", "settting " + setter.getName() + " to " + value);
                                setter.invoke(mCameraParams, Integer.parseInt(value));
                            }
                        } catch (NumberFormatException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalArgumentException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (IllegalAccessException e) {
                            Log.d("CameraPrefs", e.toString());
                        } catch (InvocationTargetException e) {
                            Log.d("CameraPrefs", e.toString());
                        }
                    }
                }
            }
        }
    }
        
}