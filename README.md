
This is a fork of the [android-cropimage][] project, used in the [SoundCloud Android][] app.

It contains various bugfixes and enhancements over the original version:

 * On API 10+ it uses [BitmapRegionDecoder.decodeRegion][] to perform memory efficient resizing
 * Added support for EXIF to get rotation information
 * Removed face detection support

 # Usage

```java
private crop(Uri input, Uri output, int width, int height) {
    Intent intent = new Intent(this, CropImageActivity.class)
        .setData(input)
        .putExtra(MediaStore.EXTRA_OUTPUT, output)
        .putExtra("aspectX", 1)
        .putExtra("aspectY", 1)
        .putExtra("maxX", width)
        .putExtra("maxY", height);

    startActivityForResult(intent, 0);
}

// handle result
protected void onActivityResult(int requestCode, int resultCode, Intent result) {
    if (resultCode == RESULT_OK) {
       if (result.getExtras().containsKey("error")) {
          Exception e = (Exception) result.getSerializableExtra("error"));
       } else {
           // crop successful
       }
    }
}
```

[android-cropimage]: https://github.com/lvillani/android-cropimage
[SoundCloud Android]: https://play.google.com/store/apps/details?id=com.soundcloud.android
[BitmapRegionDecoder.decodeRegion]: http://developer.android.com/reference/android/graphics/BitmapRegionDecoder.html#decodeRegion(android.graphics.Rect, android.graphics.BitmapFactory.Options)
