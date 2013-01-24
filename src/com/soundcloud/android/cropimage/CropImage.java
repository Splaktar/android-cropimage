/*
 * Copyright (C) 2007 The Android Open Source Project
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

// originally from AOSP Camera code. modified to only do cropping and return
// data to caller. Removed saving to file, MediaManager, unneeded options, etc.
package com.soundcloud.android.cropimage;

import android.annotation.TargetApi;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * The activity can crop specific region of interest from an image.
 */
public class CropImage extends MonitoredActivity {

    private static final String TAG = CropImage.class.getSimpleName();
    public static final boolean IN_MEMORY_CROP = Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1;

    private int mAspectX, mAspectY;
    private final Handler mHandler = new Handler();

    // These options specify the output image size and whether we should
    // scale the output to fit it (or just crop it).
    private int mMaxX, mMaxY, mExifRotation;
    private Uri mSaveUri;

    boolean mSaving; // Whether the "save" button is already clicked.

    private CropImageView mImageView;

    private RotateBitmap mRotateBitmap;
    HighlightView mCrop;

    private Uri mSourceUri;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.cropimage);

        mImageView = (CropImageView) findViewById(R.id.cropimage_image);
        mImageView.mContext = this;
        mImageView.setRecycler(new ImageViewTouchBase.Recycler() {
            @Override
            public void recycle(Bitmap b) {
                b.recycle();
                System.gc();
            }
        });

        Intent intent = getIntent();
        Bundle extras = intent.getExtras();

        if (extras != null) {
            mAspectX = extras.getInt("aspectX");
            mAspectY = extras.getInt("aspectY");
            mMaxX = extras.getInt("maxX");
            mMaxY = extras.getInt("maxY");
            mExifRotation = extras.getInt("exifRotation");
            mSaveUri = (Uri) extras.getParcelable(MediaStore.EXTRA_OUTPUT);
            if (extras.containsKey("data")){
                mRotateBitmap = new RotateBitmap((Bitmap) extras.getParcelable("data"), mExifRotation);
            }
        }

        mSourceUri = intent.getData();
        if (mSourceUri != null && mRotateBitmap == null) {
            mExifRotation = getExifRotation(getFromMediaUri(getContentResolver(), mSourceUri));

            InputStream is = null;
            try {
                is = getContentResolver().openInputStream(mSourceUri);
                mRotateBitmap =  new RotateBitmap(BitmapFactory.decodeStream(is), mExifRotation);
            } catch (IOException e) {
                Log.e(TAG, "error reading picture: " + e.getMessage(), e);
                finish();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        if (mRotateBitmap == null) {
            finish();
            return;
        }

        // Make UI fullscreen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        findViewById(R.id.cropimage_discard).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });

        findViewById(R.id.cropimage_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onSaveClicked();
            }
        });

        startCrop();
    }

    private void startCrop() {
        if (isFinishing()) {
            return;
        }
        mImageView.setImageRotateBitmapResetBase(mRotateBitmap, true);
        startBackgroundJob(this, null,
                getResources().getString(R.string.please_wait),
                new Runnable() {
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        mHandler.post(new Runnable() {
                            public void run() {
                                if (mImageView.getScale() == 1F) {
                                    mImageView.center(true, true);
                                }
                                latch.countDown();
                            }
                        });
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        mRunCrop.run();
                    }
                }, mHandler);
    }



    private static class BackgroundJob extends
            MonitoredActivity.LifeCycleAdapter implements Runnable {

        private final MonitoredActivity mActivity;
        private final ProgressDialog mDialog;
        private final Runnable mJob;
        private final Handler mHandler;
        private final Runnable mCleanupRunner = new Runnable() {
            public void run() {
                mActivity.removeLifeCycleListener(BackgroundJob.this);
                if (mDialog.getWindow() != null)
                    mDialog.dismiss();
            }
        };

        public BackgroundJob(MonitoredActivity activity, Runnable job,
                ProgressDialog dialog, Handler handler) {
            mActivity = activity;
            mDialog = dialog;
            mJob = job;
            mActivity.addLifeCycleListener(this);
            mHandler = handler;
        }

        public void run() {
            try {
                mJob.run();
            } finally {
                mHandler.post(mCleanupRunner);
            }
        }

        @Override
        public void onActivityDestroyed(MonitoredActivity activity) {
            // We get here only when the onDestroyed being called before
            // the mCleanupRunner. So, run it now and remove it from the queue
            mCleanupRunner.run();
            mHandler.removeCallbacks(mCleanupRunner);
        }

        @Override
        public void onActivityStopped(MonitoredActivity activity) {
            mDialog.hide();
        }

        @Override
        public void onActivityStarted(MonitoredActivity activity) {
            mDialog.show();
        }
    }

    private static void startBackgroundJob(MonitoredActivity activity,
            String title, String message, Runnable job, Handler handler) {
        // Make the progress dialog uncancelable, so that we can guarantee
        // the thread will be done before the activity getting destroyed.
        ProgressDialog dialog = ProgressDialog.show(activity, title, message,
                true, false);
        new Thread(new BackgroundJob(activity, job, dialog, handler)).start();
    }

    Runnable mRunCrop = new Runnable() {
        float mScale = 1F;

        // Create a default HightlightView if we found no face in the picture.
        private void makeDefault() {
            HighlightView hv = new HighlightView(mImageView);
            final int width = mRotateBitmap.getWidth();
            final int height = mRotateBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            // make the default size about 4/5 of the width or height
            int cropWidth = Math.min(width, height) * 4 / 5;
            int cropHeight = cropWidth;

            if (mAspectX != 0 && mAspectY != 0) {
                if (mAspectX > mAspectY) {
                    cropHeight = cropWidth * mAspectY / mAspectX;
                } else {
                    cropWidth = cropHeight * mAspectX / mAspectY;
                }
            }

            int x = (width - cropWidth) / 2;
            int y = (height - cropHeight) / 2;

            RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
            hv.setup(mImageView.getUnrotatedMatrix(), imageRect, cropRect, false,
                    mAspectX != 0 && mAspectY != 0);
            mImageView.add(hv);
        }

        public void run() {
            mScale = 1.0F / mScale;
            mHandler.post(new Runnable() {
                public void run() {
                    makeDefault();
                    mImageView.invalidate();
                    if (mImageView.mHighlightViews.size() == 1) {
                        mCrop = mImageView.mHighlightViews.get(0);
                        mCrop.setFocus(true);
                    }
                }
            });
        }
    };

    private void onSaveClicked() {
        // TODO this code needs to change to use the decode/crop/encode single
        // step api so that we don't require that the whole (possibly large)
        // bitmap doesn't have to be read into memory
        if (mCrop == null) {
            return;
        }

        if (mSaving)
            return;
        mSaving = true;

        Bitmap croppedImage = null;
        Rect r = mCrop.getCropRect();
        int width = r.width();
        int height = r.height();

        int outWidth = width, outHeight = height;
        if (mMaxX > 0 && mMaxY > 0 && (width > mMaxX || height > mMaxY)) {
            float ratio = (float) width / (float) height;
            if ((float) mMaxX / (float) mMaxY > ratio) {
                outHeight = mMaxY;
                outWidth = (int) ((float) mMaxY * ratio + .5f);
            } else {
                outWidth = mMaxX;
                outHeight = (int) ((float) mMaxX / ratio + .5f);
            }
        }

        if (IN_MEMORY_CROP) {
            croppedImage = inMemoryCrop(croppedImage, r, width, height, outWidth, outHeight);
            if (croppedImage != null) {
                mImageView.setImageBitmapResetBase(croppedImage, true);
                mImageView.center(true, true);
                mImageView.mHighlightViews.clear();
            }
        } else {
            try {
                croppedImage = decodeRegionCrop(croppedImage, r);
            } catch (IllegalArgumentException e) {
                setResult(RESULT_OK, new Intent().putExtra("error", e));
                finish();
                return;
            }

            if (croppedImage != null) {
                mImageView.setImageRotateBitmapResetBase(new RotateBitmap(croppedImage, mExifRotation), true);
                mImageView.center(true, true);
                mImageView.mHighlightViews.clear();
            }
        }

        // Return the cropped image directly or save it to the specified URI.
        Bundle myExtras = getIntent().getExtras();
        if (myExtras != null && (myExtras.getParcelable("data") != null
                || myExtras.getBoolean("return-data"))) {
            Bundle extras = new Bundle();
            if (croppedImage != null) {
                extras.putParcelable("data", croppedImage);
            }
            setResult(RESULT_OK,
                    (new Intent()).setAction("inline-data").putExtras(extras));
            finish();
        } else {
            if (croppedImage != null){
                final Bitmap b = croppedImage;
                Util.startBackgroundJob(this, null,
                        getResources().getString(R.string.savingImage),
                        new Runnable() {
                            public void run() {
                                saveOutput(b);
                            }
                        }, mHandler);
            } else {
                finish();
            }

        }
    }

    /**
     * @param croppedImage the cropped image
     * @param rect rectangle to crop
     * @return the bitmap
     * @throws IllegalArgumentException if the rectangle is outside of the image
     */
    @TargetApi(10)
    private Bitmap decodeRegionCrop(Bitmap croppedImage, Rect rect) {
        // release memory now
        clearImageView();

        InputStream is = null;
        try {
            is = getContentResolver().openInputStream(mSourceUri);
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
            final int width  = decoder.getWidth();
            final int height = decoder.getHeight();

            if (mExifRotation != 0) {

                // adjust crop area to account for image rotation
                Matrix matrix = new Matrix();
                matrix.setRotate(-mExifRotation);

                RectF adjusted = new RectF();
                matrix.mapRect(adjusted, new RectF(rect));

                // adjust to account for origin at 0,0
                adjusted.offset(adjusted.left < 0 ? width : 0, adjusted.top < 0 ? height : 0);
                rect = new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
            }

            try {
                croppedImage = decoder.decodeRegion(rect, new BitmapFactory.Options());

            } catch (IllegalArgumentException e) {
                // rethrow with some extra information
                throw new IllegalArgumentException(
                        "rectangle "+rect+" is outside of the image ("+width+","+height+","+mExifRotation+")", e);
            }

        } catch (IOException e) {
            Log.e(TAG, "error cropping picture: " + e.getMessage(), e);
            finish();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
        return croppedImage;
    }

    private Bitmap inMemoryCrop(Bitmap croppedImage, Rect r, int width, int height, int outWidth, int outHeight) {
        // in memory crop, potential OOM errors,
        // but we have no choice as we can't selectively decode a bitmap with this sdk
        System.gc();

        try {
            croppedImage = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);

            Canvas canvas = new Canvas(croppedImage);
            RectF dstRect = new RectF(0, 0, width, height);

            Matrix m = new Matrix();
            m.setRectToRect(new RectF(r), dstRect, Matrix.ScaleToFit.FILL);
            m.preConcat(mRotateBitmap.getRotateMatrix());
            canvas.drawBitmap(mRotateBitmap.getBitmap(), m, null);

        } catch (OutOfMemoryError e){
            Log.e(TAG, "error cropping picture: " + e.getMessage(), e);
            System.gc();
        }

        // Release bitmap memory as soon as possible
        clearImageView();
        return croppedImage;
    }

    private void clearImageView() {
        mImageView.clear();
        mRotateBitmap.recycle();
        System.gc();
    }

    private void saveOutput(Bitmap croppedImage) {
        if (mSaveUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = getContentResolver().openOutputStream(mSaveUri);
                if (outputStream != null) {
                    croppedImage.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                }

            } catch (IOException ex) {
                // TODO: report error to caller
                Log.e(TAG, "Cannot open file: " + mSaveUri, ex);
            } finally {
                Util.closeSilently(outputStream);
            }

            if (!IN_MEMORY_CROP){
                // in memory crop negates the rotation
                copyExifRotation(
                        getFromMediaUri(getContentResolver(), mSourceUri),
                        getFromMediaUri(getContentResolver(), mSaveUri)
                );
            }

            Bundle extras = new Bundle();
            setResult(RESULT_OK, new Intent(mSaveUri.toString())
                    .putExtras(extras));
        }

        final Bitmap b = croppedImage;
        mHandler.post(new Runnable() {
            public void run() {
                mImageView.clear();
                b.recycle();
            }
        });

        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRotateBitmap.recycle();
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    public static int getExifRotation(File imageFile) {
        if (imageFile == null) return -1;
        try {
            ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
            // We only recognize a subset of orientation tag values.
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return ExifInterface.ORIENTATION_UNDEFINED;
            }
        } catch (IOException e) {
            Log.e(TAG, "error", e);
            return -1;
        }
    }

    public static boolean copyExifRotation(File sourceFile, File destFile) {
            if (sourceFile == null || destFile == null) return false;
            try {
                ExifInterface exifSource = new ExifInterface(sourceFile.getAbsolutePath());
                ExifInterface exifDest = new ExifInterface(destFile.getAbsolutePath());
                exifDest.setAttribute(ExifInterface.TAG_ORIENTATION, exifSource.getAttribute(ExifInterface.TAG_ORIENTATION));
                exifDest.saveAttributes();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "error", e);
                return false;
            }
        }

    public static File getFromMediaUri(ContentResolver resolver, Uri uri) {
        if (uri == null) return null;

        if ("file".equals(uri.getScheme())) {
            return new File(uri.getPath());
        } else if ("content".equals(uri.getScheme())) {
            final String[] filePathColumn = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME};
            Cursor cursor = null;
            try {
                cursor = resolver.query(uri, filePathColumn, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    final int columnIndex = (uri.toString().startsWith("content://com.google.android.gallery3d")) ?
                            cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME) :
                            cursor.getColumnIndex(MediaStore.MediaColumns.DATA); // if it is a picasa image on newer devices with OS 3.0 and up
                    if (columnIndex != -1) {
                        return new File(cursor.getString(columnIndex));
                    }
                }
            } catch (SecurityException ignored) {
                // nothing to be done
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return null;
    }
}

