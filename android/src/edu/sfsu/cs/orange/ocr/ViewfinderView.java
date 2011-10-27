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

import java.util.List;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.robtheis.reverser.R;

import edu.sfsu.cs.orange.ocr.camera.CameraManager;

public final class ViewfinderView extends View {

  private final Paint paint;
  private final int maskColor;
  private final int frameColor;
  private final int cornerColor;
  private OcrResultText resultText;
  List<Rect> wordBoundingBoxes;
  List<Rect> characterBoundingBoxes;
  Rect bounds;

  // This constructor is used when the class is built from an XML resource.
  public ViewfinderView(Context context, AttributeSet attrs) {
    super(context, attrs);

    // Initialize these once for performance rather than calling them every time in onDraw().
    paint = new Paint();
    Resources resources = getResources();
    maskColor = resources.getColor(R.color.viewfinder_mask);
    frameColor = resources.getColor(R.color.viewfinder_frame);
    cornerColor = resources.getColor(R.color.viewfinder_corners);

    bounds = new Rect();
  }

  @Override
  public void onDraw(Canvas canvas) {
    Rect frame = CameraManager.get().getFramingRect();
    if (frame == null) {
      return;
    }
    int width = canvas.getWidth();
    int height = canvas.getHeight();

    // Draw the exterior (i.e. outside the framing rect) darkened
    paint.setColor(maskColor);
    canvas.drawRect(0, 0, width, frame.top, paint);
    canvas.drawRect(0, frame.top, frame.left, frame.bottom + 1, paint);
    canvas.drawRect(frame.right + 1, frame.top, width, frame.bottom + 1, paint);
    canvas.drawRect(0, frame.bottom + 1, width, height, paint);

    if (resultText != null && resultText.getMeanConfidence() > 35) {
      wordBoundingBoxes = resultText.getWordBoundingBoxes();
      characterBoundingBoxes = resultText.getCharacterBoundingBoxes();

      Rect previewFrame = CameraManager.get().getFramingRectInPreview();
      float scaleX = frame.width() / (float) previewFrame.width();
      float scaleY = frame.height() / (float) previewFrame.height();

      // Split the text into words
      String[] words = resultText.getText().replace("\n"," ").split(" ");

      for (int i = 0; i < wordBoundingBoxes.size(); i++) {
        if (i < 100) {
          // Draw a bounding box around each word
          //        paint.setAlpha(0xA0);
          //        paint.setColor(0xFF00CCFF);
          //        paint.setStyle(Style.STROKE);
          //        paint.setStrokeWidth(1);
          Rect r = wordBoundingBoxes.get(i);
          //        canvas.drawRect(frame.left + r.left * scaleX,
          //            frame.top + r.top * scaleY, 
          //            frame.left + r.right * scaleX, 
          //            frame.top + r.bottom * scaleY, paint);

          // Draw a white background around each word
          int[] wordConfidences = resultText.getWordConfidences();
          paint.setColor(Color.WHITE);
          paint.setAlpha(wordConfidences[i] * 255 / 100); // Higher confidence = more opaque, less transparent background
          paint.setStyle(Style.FILL);
          canvas.drawRect(frame.left + r.left * scaleX,
              frame.top + r.top * scaleY, 
              frame.left + r.right * scaleX, 
              frame.top + r.bottom * scaleY, paint);

          if (wordConfidences[i] > 35) {
            // Draw the word in black text
            try {
              paint.setColor(Color.BLACK);
              paint.setAlpha(0xFF);
              paint.setAntiAlias(true);
              paint.setTextAlign(Align.LEFT);
              // Adjust text size to fill rect
              paint.setTextSize(100);
              paint.setTextScaleX(1.0f);
              // ask the paint for the bounding rect if it were to draw this text
              Rect bounds = new Rect();
              paint.getTextBounds(words[i], 0, words[i].length(), bounds);
              // get the height that would have been produced
              int h = bounds.bottom - bounds.top;
              // figure out what textSize setting would create that height of text
              float size  = (((float)(r.height())/h)*100f);
              // and set it into the paint
              paint.setTextSize(size);
              // Now set the scale.
              // do calculation with scale of 1.0 (no scale)
              paint.setTextScaleX(1.0f);
              // ask the paint for the bounding rect if it were to draw this text.
              paint.getTextBounds(words[i], 0, words[i].length(), bounds);
              // determine the width
              int w = bounds.right - bounds.left;
              // calculate the baseline to use so that the entire text is visible including the descenders
              int text_h = bounds.bottom-bounds.top;
              int baseline =bounds.bottom+((r.height()-text_h)/2);
              // determine how much to scale the width to fit the view
              float xscale = ((float) (r.width())) / w;
              // set the scale for the text paint
              paint.setTextScaleX(xscale);
              canvas.drawText(new StringBuffer(words[i]).reverse().toString().trim(), frame.left + r.left * scaleX, frame.top + r.bottom * scaleY - baseline, paint);
            } catch (ArrayIndexOutOfBoundsException e) {
              e.printStackTrace();
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        }
      }    

      //      // Draw bounding boxes around each character
      //      for (int i = 0; i < characterBoundingBoxes.size(); i++) {
      //        paint.setAlpha(0xA0);
      //        paint.setColor(0xFF00FF00);
      //        paint.setStyle(Style.STROKE);
      //        paint.setStrokeWidth(1);
      //        Rect r = characterBoundingBoxes.get(i);
      //        canvas.drawRect(frame.left + r.left * scaleX,
      //            frame.top + r.top * scaleY, 
      //            frame.left + r.right * scaleX, 
      //            frame.top + r.bottom * scaleY, paint);
      //      }

      //      // Draw letters individually
      //      for (int i = 0; i < characterBoundingBoxes.size(); i++) {
      //        Rect r = characterBoundingBoxes.get(i);

      //        // Draw a white background for every letter
      //        int meanConfidence = resultText.getMeanConfidence();
      //        paint.setColor(Color.WHITE);
      //        paint.setAlpha(meanConfidence * (255 / 100));
      //        paint.setStyle(Style.FILL);
      //        canvas.drawRect(frame.left + r.left * scaleX,
      //            frame.top + r.top * scaleY, 
      //            frame.left + r.right * scaleX, 
      //            frame.top + r.bottom * scaleY, paint);

      //        // Draw each letter, in black
      //        paint.setColor(Color.BLACK);
      //        paint.setAlpha(0xFF);
      //        paint.setAntiAlias(true);
      //        paint.setTextAlign(Align.LEFT);
      //        String letter = "";
      //        try {
      //          char c = resultText.getText().replace("\n","").replace(" ", "").charAt(i);
      //          letter = Character.toString(c);
      //
      //          if (!letter.equals("-") && !letter.equals("_")) {
      //
      //            // Adjust text size to fill rect
      //            paint.setTextSize(100);
      //            paint.setTextScaleX(1.0f);
      //
      //            // ask the paint for the bounding rect if it were to draw this text
      //            Rect bounds = new Rect();
      //            paint.getTextBounds(letter, 0, letter.length(), bounds);
      //
      //            // get the height that would have been produced
      //            int h = bounds.bottom - bounds.top;
      //
      //            // figure out what textSize setting would create that height of text
      //            float size  = (((float)(r.height())/h)*100f);
      //
      //            // and set it into the paint
      //            paint.setTextSize(size);
      //
      //            // Draw the text as is. We don't really need to set the text scale, because the dimensions
      //            // of the Rect should already be suited for drawing our letter. 
      //            canvas.drawText(letter, frame.left + r.left * scaleX, frame.top + r.bottom * scaleY, paint);
      //          }
      //        } catch (StringIndexOutOfBoundsException e) {
      //          e.printStackTrace();
      //        } catch (Exception e) {
      //          e.printStackTrace();
      //        }
      //      }
    }

    // Draw a two pixel solid border inside the framing rect
    paint.setAlpha(0);
    paint.setStyle(Style.FILL);
    paint.setColor(frameColor);
    canvas.drawRect(frame.left, frame.top, frame.right + 1, frame.top + 2, paint);
    canvas.drawRect(frame.left, frame.top + 2, frame.left + 2, frame.bottom - 1, paint);
    canvas.drawRect(frame.right - 1, frame.top, frame.right + 1, frame.bottom - 1, paint);
    canvas.drawRect(frame.left, frame.bottom - 1, frame.right + 1, frame.bottom + 1, paint);

    // Draw the framing rect corner UI elements
    paint.setColor(cornerColor);
    canvas.drawRect(frame.left - 15, frame.top - 15, frame.left + 15, frame.top, paint);
    canvas.drawRect(frame.left - 15, frame.top, frame.left, frame.top + 15, paint);
    canvas.drawRect(frame.right - 15, frame.top - 15, frame.right + 15, frame.top, paint);
    canvas.drawRect(frame.right, frame.top - 15, frame.right + 15, frame.top + 15, paint);
    canvas.drawRect(frame.left - 15, frame.bottom, frame.left + 15, frame.bottom + 15, paint);
    canvas.drawRect(frame.left - 15, frame.bottom - 15, frame.left, frame.bottom, paint);
    canvas.drawRect(frame.right - 15, frame.bottom, frame.right + 15, frame.bottom + 15, paint);
    canvas.drawRect(frame.right, frame.bottom - 15, frame.right + 15, frame.bottom + 15, paint);    
  }

  public void drawViewfinder() {
    invalidate();
  }

  public void addResultText(OcrResultText text) {
    resultText = text; 
  }

  public void removeResultText() {
    resultText = null;
  }
}
