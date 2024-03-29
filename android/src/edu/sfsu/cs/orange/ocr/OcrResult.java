/*
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

import android.graphics.Rect;

public final class OcrResult {
  private final String text;
  
  private final int[] wordConfidences;
  private final int meanConfidence;
  
  private final List<Rect> wordBoundingBoxes;
  private final List<Rect> characterBoundingBoxes;
  
  private final long timestamp;
  private final long recognitionTimeRequired;
  
  public OcrResult(String text,
                   int[] wordConfidences,
                   int meanConfidence,
                   List<Rect> characterBoundingBoxes,
                   List<Rect> wordBoundingBoxes,
                   long recognitionTimeRequired) {
    this.text = text;
    this.wordConfidences = wordConfidences;
    this.meanConfidence = meanConfidence;
    this.characterBoundingBoxes = characterBoundingBoxes;
    this.wordBoundingBoxes = wordBoundingBoxes;
    this.recognitionTimeRequired = recognitionTimeRequired;
    this.timestamp = System.currentTimeMillis();
  }
  
  public String getText() {
    return text;
  }

  public int[] getWordConfidences() {
    return wordConfidences;
  }

  public int getMeanConfidence() {
    return meanConfidence;
  }

  public long getRecognitionTimeRequired() {
    return recognitionTimeRequired;
  }
  
  public List<Rect> getCharacterBoundingBoxes() {
    return characterBoundingBoxes;
  }
  
  public List<Rect> getWordBoundingBoxes() {
    return wordBoundingBoxes;
  }
  
  public long getTimestamp() {
    return timestamp;
  }
  
  @Override
  public String toString() {
    return text + " " + meanConfidence + " " + recognitionTimeRequired + " " + timestamp;
  }
}
