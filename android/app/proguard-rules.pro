# Keep WebView JS bridge methods
-keepclassmembers class com.m328v6.host.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep usb-serial-for-android driver classes (loaded via reflection by UsbSerialProber)
-keep class com.hoho.android.usbserial.driver.** { *; }
