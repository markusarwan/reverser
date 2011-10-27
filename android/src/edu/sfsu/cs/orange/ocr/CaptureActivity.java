/*
 * Copyright (C) 2008 ZXing authors
 * Copyright 2011 Robert Theis
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

package edu.sfsu.cs.orange.ocr;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.robtheis.reverser.R;

import edu.sfsu.cs.orange.ocr.camera.CameraManager;
import edu.sfsu.cs.orange.ocr.HelpActivity;
import edu.sfsu.cs.orange.ocr.OcrResult;
import edu.sfsu.cs.orange.ocr.PreferencesActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * This activity opens the camera and does the actual scanning on a background thread.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

  private static final String TAG = CaptureActivity.class.getSimpleName();
  
  // Note these values might be overridden by the ones in preferences.xml
  public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "eng";
  public static final String DEFAULT_TARGET_LANGUAGE_CODE = "es";
  public static final boolean DEFAULT_TOGGLE_CONTINUOUS = false;
  public static final String DEFAULT_ACCURACY_VS_SPEED_MODE = PreferencesActivity.AVS_MODE_MOST_ACCURATE;
  public static final String DEFAULT_CHARACTER_BLACKLIST = "";
  public static final String DEFAULT_CHARACTER_WHITELIST = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890!@#$%^&*()-_=+[]?<>.,`\"/;";
  public static final String DEFAULT_PAGE_SEGMENTATION_MODE = "Auto";

  // Minimum mean confidence score necessary to not reject single-shot OCR result
  public static final int MINIMUM_MEAN_CONFIDENCE = 0; // 0 means don't reject any scored results
  
  // Length of time between subsequent autofocus requests. Used in CaptureActivityHandler.
  static final long CONTINUOUS_AUTOFOCUS_INTERVAL_MS = 9000L; // originally 1500L
  static final long PREVIEW_AUTOFOCUS_INTERVAL_MS = 9000L; // originally 1500L
  
  // Context menu
  private static final int SETTINGS_ID = Menu.FIRST;
  private static final int ABOUT_ID = Menu.FIRST + 1;

  private CaptureActivityHandler handler;
  private ViewfinderView viewfinderView;
  private SurfaceView surfaceView;
  private SurfaceHolder surfaceHolder;
  private OcrResult lastResult;
  private boolean hasSurface;
  private TessBaseAPI baseApi; // Java interface for the Tesseract OCR engine
  private String sourceLanguageCodeOcr; // ISO 639-3 language code
  private String sourceLanguageReadable; // Language name, for example, "English"
  private int pageSegmentationMode = TessBaseAPI.PSM_AUTO;
  private Integer accuracyVsSpeedMode = TessBaseAPI.AVS_MOST_ACCURATE;
  private String characterBlacklist;
  private String characterWhitelist = CaptureActivity.DEFAULT_CHARACTER_WHITELIST;
  private boolean isContinuousModeActive; // Whether we are doing OCR in continuous mode
  private SharedPreferences prefs;
  private OnSharedPreferenceChangeListener listener;
  private ProgressDialog dialog; // for initOcr - language download & unzip
  private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine
  private boolean isEngineReady;
  private boolean isPaused;

  Handler getHandler() {
    return handler;
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    
    Window window = getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    setContentView(R.layout.capture);

    CameraManager.init(this);
    viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
    
    handler = null;
    lastResult = null;
    hasSurface = false;

    isEngineReady = false;
    
    // Set listener to change the size of the viewfinder rectangle.
    viewfinderView.setOnTouchListener(new View.OnTouchListener() {
      int lastX = -1;
      int lastY = -1;

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          lastX = -1;
          lastY = -1;
          return true;
        case MotionEvent.ACTION_MOVE:
          int currentX = (int) event.getX();
          int currentY = (int) event.getY();

          try {
            Rect rect = CameraManager.get().getFramingRect();

            final int BUFFER = 50;
            final int BIG_BUFFER = 60;
            if (lastX >= 0) {
              // Adjust the size of the viewfinder rectangle. Check if the touch event occurs in the corner areas first, because the regions overlap.
              if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                  && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                // Top left corner: adjust both top and left sides
                CameraManager.get().adjustFramingRect( 2 * (lastX - currentX), 2 * (lastY - currentY));
                viewfinderView.removeResultText();
              } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER)) 
                  && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                // Top right corner: adjust both top and right sides
                CameraManager.get().adjustFramingRect( 2 * (currentX - lastX), 2 * (lastY - currentY));
                viewfinderView.removeResultText();
              } else if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER))
                  && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                // Bottom left corner: adjust both bottom and left sides
                CameraManager.get().adjustFramingRect(2 * (lastX - currentX), 2 * (currentY - lastY));
                viewfinderView.removeResultText();
              } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER)) 
                  && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                // Bottom right corner: adjust both bottom and right sides
                CameraManager.get().adjustFramingRect(2 * (currentX - lastX), 2 * (currentY - lastY));
                viewfinderView.removeResultText();
              } else if (((currentX >= rect.left - BUFFER && currentX <= rect.left + BUFFER) || (lastX >= rect.left - BUFFER && lastX <= rect.left + BUFFER))
                  && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                // Adjusting left side: event falls within BUFFER pixels of left side, and between top and bottom side limits
                CameraManager.get().adjustFramingRect(2 * (lastX - currentX), 0);
                viewfinderView.removeResultText();
              } else if (((currentX >= rect.right - BUFFER && currentX <= rect.right + BUFFER) || (lastX >= rect.right - BUFFER && lastX <= rect.right + BUFFER))
                  && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                // Adjusting right side: event falls within BUFFER pixels of right side, and between top and bottom side limits
                CameraManager.get().adjustFramingRect(2 * (currentX - lastX), 0);
                viewfinderView.removeResultText();
              } else if (((currentY <= rect.top + BUFFER && currentY >= rect.top - BUFFER) || (lastY <= rect.top + BUFFER && lastY >= rect.top - BUFFER))
                  && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                // Adjusting top side: event falls within BUFFER pixels of top side, and between left and right side limits
                CameraManager.get().adjustFramingRect(0, 2 * (lastY - currentY));
                viewfinderView.removeResultText();
              } else if (((currentY <= rect.bottom + BUFFER && currentY >= rect.bottom - BUFFER) || (lastY <= rect.bottom + BUFFER && lastY >= rect.bottom - BUFFER))
                  && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                // Adjusting bottom side: event falls within BUFFER pixels of bottom side, and between left and right side limits
                CameraManager.get().adjustFramingRect(0, 2 * (currentY - lastY));
                viewfinderView.removeResultText();
              }     
            }
          } catch (NullPointerException e) {
            Log.e(TAG, "Framing rect not available", e);
          }
          v.invalidate();
          lastX = currentX;
          lastY = currentY;
          return true;
        case MotionEvent.ACTION_UP:
          lastX = -1;
          lastY = -1;
          return true;
        }
        return false;
      }
    });
  }
  
  /**
   * Called to resume recognition after translation in continuous mode.
   */
  void resumeContinuousDecoding() {
    isPaused = false;
    resetStatusView();
    setStatusViewForContinuous();
    handler.resetState();
  }
  
  @Override
  protected void onResume() {
    super.onResume();
    
    resetStatusView();
    retrievePreferences();
    
    // Set up the camera preview surface.
    surfaceView = (SurfaceView) findViewById(R.id.preview_view);
    surfaceHolder = surfaceView.getHolder();
    if (!hasSurface) {
      //Log.d(TAG, "onResume(): adding the callback...");
      surfaceHolder.addCallback(this);
      surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }
    
    //Log.v(TAG,"Maximum memory available: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
    
    // Comment out the following block to test non-OCR functions without an SD card
    
    // Do OCR engine initialization, if necessary
    boolean doNewInit = (baseApi == null);
    if (doNewInit) {      
      // Initialize the OCR engine
      File storageDirectory = getStorageDirectory();
      if (storageDirectory != null) {
        initOcrEngine(storageDirectory, sourceLanguageCodeOcr, sourceLanguageReadable);
      }
    } else {
      // We already have the engine initialized, so just start the camera.
      resumeOCR();
    }
  }
  
  void resumeOCR() {
    Log.d(TAG, "resumeOCR()");
    
    // This method is called when Tesseract has already been successfully initiliazed, so set 
    // isEngineReady = true here.
    isEngineReady = true;
    
    isPaused = false;

    if (handler != null) {
      handler.resetState();
    }
    if (baseApi != null) {
      baseApi.setPageSegMode(pageSegmentationMode);
      baseApi.setVariable(TessBaseAPI.VAR_ACCURACYVSPEED, accuracyVsSpeedMode.toString());
      baseApi.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, characterBlacklist);
      baseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, characterWhitelist);
    }

    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      initCamera(surfaceHolder);
    }
  }

  public void surfaceCreated(SurfaceHolder holder) {
    Log.d(TAG, "surfaceCreated()");
    
    if (holder == null) {
      Log.e(TAG, "surfaceCreated gave us a null surface");
    }
    
    // Only initialize the camera if the OCR engine is ready to go.
    if (!hasSurface && isEngineReady) {
      Log.d(TAG, "surfaceCreated(): calling initCamera()...");
      initCamera(holder);
    }
    hasSurface = true;
  }
  
  private void initCamera(SurfaceHolder surfaceHolder) {
    Log.d(TAG, "initCamera()");
    try {

      // Open and initialize the camera
      CameraManager.get().openDriver(surfaceHolder);
      
      // Creating the handler starts the preview, which can also throw a RuntimeException.
      handler = new CaptureActivityHandler(this, baseApi, isContinuousModeActive);
      
    } catch (IOException ioe) {
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    } catch (RuntimeException e) {
      // Barcode Scanner has seen crashes in the wild of this variety:
      // java.?lang.?RuntimeException: Fail to connect to camera service
      showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
    }   
  }
  
  @Override
  protected void onPause() {
    super.onPause();
    if (handler != null) {
      handler.quitSynchronously();
    }
    //    inactivityTimer.onPause();
    
    // Stop using the camera, to avoid conflicting with other camera-based apps
    CameraManager.get().closeDriver();
  }

  void stopHandler() {
    if (handler != null) {
      handler.stop();
    }
  }

  @Override
  protected void onDestroy() {
    //    inactivityTimer.shutdown();
    if (baseApi != null) {
      baseApi.end();
    }
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {

      // First check if we're paused in continuous mode, and if so, just unpause.
      if (isPaused) {
        Log.d(TAG, "only resuming continuous recognition, not quitting...");
        resumeContinuousDecoding();
        return true;
      }

      // Exit the app if we're not viewing an OCR result.
      if (lastResult == null) {
        setResult(RESULT_CANCELED);
        finish();
        return true;
      } else {
        // Go back to previewing in regular OCR mode.
        resetStatusView();
        if (handler != null) {
          handler.sendEmptyMessage(R.id.restart_preview);
        }
        return true;
      }
    } else if (keyCode == KeyEvent.KEYCODE_CAMERA) {

      return true;
    } else if (keyCode == KeyEvent.KEYCODE_FOCUS) {      
      // Only perform autofocus if user is not holding down the button.
      if (event.getRepeatCount() == 0) {
        handler.requestDelayedAutofocus(500L, R.id.user_requested_auto_focus_done);
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    //    MenuInflater inflater = getMenuInflater();
    //    inflater.inflate(R.menu.options_menu, menu);
    super.onCreateOptionsMenu(menu);
    menu.add(0, ABOUT_ID, 0, "About").setIcon(android.R.drawable.ic_menu_info_details);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    Intent intent;
    switch (item.getItemId()) {
    case SETTINGS_ID: {
      intent = new Intent().setClass(this, PreferencesActivity.class);
      startActivity(intent);
      break;
    }
    case ABOUT_ID: {
      intent = new Intent(this, HelpActivity.class);
      intent.putExtra(HelpActivity.REQUESTED_PAGE_KEY, HelpActivity.ABOUT_PAGE);
      startActivity(intent);
      break;
    }
    }
    return super.onOptionsItemSelected(item);
  }

  public void surfaceDestroyed(SurfaceHolder holder) {
    hasSurface = false;
  }

  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
  }
  
  private void retrievePreferences() {
      prefs = PreferenceManager.getDefaultSharedPreferences(this);
      isContinuousModeActive = true;

      // Retrieve from preferences, and set in this Activity, the page segmentation mode preference
      String[] pageSegmentationModes = getResources().getStringArray(R.array.pagesegmentationmodes);
      String pageSegmentationModeName = prefs.getString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, pageSegmentationModes[0]);
      if (pageSegmentationModeName.equals(pageSegmentationModes[0])) {
        pageSegmentationMode = TessBaseAPI.PSM_AUTO;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[1])) {
        pageSegmentationMode = TessBaseAPI.PSM_SINGLE_BLOCK;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[2])) {
        pageSegmentationMode = TessBaseAPI.PSM_SINGLE_CHAR;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[3])) {
        pageSegmentationMode = TessBaseAPI.PSM_SINGLE_COLUMN;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[4])) {
        pageSegmentationMode = TessBaseAPI.PSM_SINGLE_LINE;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[5])) {
        pageSegmentationMode = TessBaseAPI.PSM_SINGLE_WORD;
      }

      // Retrieve from preferences, and set in this Activity, the accuracy vs. speed preference
      String accuracyVsSpeedModeName = prefs.getString(PreferencesActivity.KEY_ACCURACY_VS_SPEED_MODE, PreferencesActivity.AVS_MODE_MOST_ACCURATE);
      if (accuracyVsSpeedModeName.equals(PreferencesActivity.AVS_MODE_FASTEST)) {
        accuracyVsSpeedMode = TessBaseAPI.AVS_FASTEST;
      } else {
        accuracyVsSpeedMode = TessBaseAPI.AVS_MOST_ACCURATE;
      }
      
      // Retrieve from preferences, and set in this Activity, the character blacklist and whitelist
      characterBlacklist = prefs.getString(PreferencesActivity.KEY_CHARACTER_BLACKLIST, CaptureActivity.DEFAULT_CHARACTER_BLACKLIST);
      characterWhitelist = prefs.getString(PreferencesActivity.KEY_CHARACTER_WHITELIST, CaptureActivity.DEFAULT_CHARACTER_WHITELIST);
      
      prefs.registerOnSharedPreferenceChangeListener(listener);    
  }

  private File getStorageDirectory() {
    //Log.d(TAG, "getStorageDirectory(): API level is " + Integer.valueOf(android.os.Build.VERSION.SDK_INT));
    
    String state = null;
    try {
      state = Environment.getExternalStorageState();
    } catch (RuntimeException e) {
      Log.e(TAG, "Is the SD card visible?", e);
      showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
    }
    
    if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
        // We can read and write the media
    	if (Integer.valueOf(android.os.Build.VERSION.SDK_INT) > 7) {
    	    // For Android 2.2 and above
          return getExternalFilesDir(Environment.MEDIA_MOUNTED);
        } else {
          // For Android 2.1 and below, explicitly give the path as, for example,
          // "/mnt/sdcard/Android/data/edu.sfsu.cs.orange.ocr/files/"
          return new File(Environment.getExternalStorageDirectory().toString() + File.separator + 
                  "Android" + File.separator + "data" + File.separator + getPackageName() + 
                  File.separator + "files" + File.separator);
        }
    } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
    	// We can only read the media
    	Log.e(TAG, "External storage is read-only");
      showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable for data storage.");
    } else {
    	// Something else is wrong. It may be one of many other states, but all we need
      // to know is we can neither read nor write
    	Log.e(TAG, "External storage is unavailable");
    	showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
    }
    return null;
  }

  private void initOcrEngine(File storageRoot, String languageCode, String languageName) {
    isEngineReady = false;
    
    if (dialog != null) {
      dialog.dismiss();
    }
    dialog = new ProgressDialog(this);
    indeterminateDialog = new ProgressDialog(this);
    indeterminateDialog.setTitle("Please wait");
    indeterminateDialog.setMessage("Initializing...");
    indeterminateDialog.setCancelable(false);
    indeterminateDialog.show();
    
    if (handler != null) {
      handler.quitSynchronously();     
    }
  
    // Start AsyncTask to install language data and init OCR
    baseApi = new TessBaseAPI();
    new OcrInitAsyncTask(this, baseApi, dialog, indeterminateDialog, languageCode, languageName)
      .execute(storageRoot.toString());
  }
  
  void handleOcrContinuousDecode(OcrResult ocrResult) {
   
    lastResult = ocrResult;
    
    // Send an OcrResultText object to the ViewfinderView for text rendering
    viewfinderView.addResultText(new OcrResultText(ocrResult.getText(), 
                                                   ocrResult.getWordConfidences(),
                                                   ocrResult.getMeanConfidence(),
                                                   ocrResult.getCharacterBoundingBoxes(),
                                                   ocrResult.getWordBoundingBoxes()));
  }
  
  /**
   * Version of handleOcrContinuousDecode for failed OCR requests
   */
  void handleOcrContinuousDecode(OcrResultFailure obj) {
    lastResult = null;
    viewfinderView.removeResultText();
  }

  private void resetStatusView() {
    viewfinderView.setVisibility(View.VISIBLE);
    lastResult = null;
  }

  void setStatusViewForContinuous() {
    viewfinderView.removeResultText();
  }


  void drawViewfinder() {
    viewfinderView.drawViewfinder();
  }
  
  void showErrorMessage(String title, String message) {
	  new AlertDialog.Builder(this)
	    .setTitle(title)
	    .setMessage(message)
	    .setOnCancelListener(new FinishListener(this))
	    .setPositiveButton( "Done", new FinishListener(this))
	    .show();
  }

}
