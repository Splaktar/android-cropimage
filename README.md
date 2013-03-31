This is a fork of the [android-cropimage][] and [android-cropimage-soundcloud][] projects.

It contains various bugfixes and enhancements over the original version:

 * On API 10+ it uses [BitmapRegionDecoder][] to perform memory efficient resizing
 * Fixed bugs in EXIF support for getting rotation information
 * Removed face detection support
 * Requires API 10+

 # Usage from within your Activity or Fragment:

```java
private crop(Uri input, Uri output, int width, int height)
{
    Intent intent = new Intent(this, CropImageActivity.class)
        .setData(input)
        .putExtra(MediaStore.EXTRA_OUTPUT, output)
        .putExtra("aspectX", 1)
        .putExtra("aspectY", 1)
        .putExtra("maxX", width)
        .putExtra("maxY", height);
        .putExtra("return-data", false);

    startActivityForResult(intent, 0);
}

// handle result
protected void onActivityResult(int requestCode, int resultCode, Intent result) {
    if (resultCode == RESULT_OK) {
       if (result.getExtras().containsKey("error")) {
          Exception e = (Exception) result.getSerializableExtra("error");
          Log.e(CropImageActivity.class.getSimpleName(), e.getMessage(), e);
       } else {
           // crop successful
       }
    }
}
```

[android-cropimage]: https://github.com/lvillani/android-cropimage
[android-cropimage-soundcloud]: https://github.com/soundcloud/android-cropimage
[BitmapRegionDecoder]: http://developer.android.com/reference/android/graphics/BitmapRegionDecoder.html
