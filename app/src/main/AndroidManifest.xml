<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.NFC" />
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
    <uses-feature android:name="android.hardware.nfc" android:required="false" />

    <application
        android:name=".EslScannerApp"
        android:allowBackup="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="ESL Scanner"
        android:theme="@style/Theme.EslScanner"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.ScannerActivity"
            android:exported="false"
            android:launchMode="singleTop" />
        <activity android:name=".ui.SettingsActivity" android:exported="false" />
        <activity android:name=".ui.PendingActivity" android:exported="false" />

    </application>

</manifest>
