# Tutorial: How to Create an LSPosed Module (Hook Extension)

Creating a module for LSPosed follows the exact same API and conventions as traditional Xposed module development. This guide will walk you through setting up a basic project to hook into an Android application.

## Prerequisites

1.  **A Rooted Android Device/Emulator:** You need a device with Magisk/KernelSU and LSPosed installed.
2.  **Android Studio:** The standard IDE for Android app development.
3.  **Basic Android Knowledge:** Familiarity with Java or Kotlin, Android app structure, and Java Reflection.

---

## Step 1: Set Up the Project in Android Studio

1.  Open Android Studio and create a **New Project**.
2.  Choose **Empty Views Activity** (or "No Activity" if you don't need an app UI).
3.  Name your project (e.g., `MyFirstHook`) and choose **Java** or **Kotlin** as the language.

---

## Step 2: Add the Xposed Framework Dependencies

LSPosed relies on the Xposed API. We need to add it to our project dependencies.

1.  Open your project-level `settings.gradle` (or `settings.gradle.kts`) and add the Xposed repository under `dependencyResolutionManagement`:

    ```gradle
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
            google()
            mavenCentral()
            // Add the Xposed API repository
            maven { url 'https://api.xposed.info/' } 
        }
    }
    ```

2.  Open your app-level `build.gradle` (or `build.gradle.kts`) and add the API dependency. **CRITICAL:** You must use `compileOnly` (not `implementation`). The framework provides these classes at runtime; bundling them in your APK will cause crashes.

    ```gradle
    dependencies {
        // ... other dependencies
        compileOnly 'de.robv.android.xposed:api:82' // Use compileOnly!
    }
    ```
3.  Sync your project.

---

## Step 3: Configure the Android Manifest

You must explicitly tell LSPosed that your app is a module. Open `app/src/main/AndroidManifest.xml` and add the following `<meta-data>` tags inside the `<application>` block:

```xml
<application ...>
    
    <!-- Tells LSPosed this is a module -->
    <meta-data
        android:name="xposedmodule"
        android:value="true" />
        
    <!-- A description that appears in the LSPosed Manager -->
    <meta-data
        android:name="xposeddescription"
        android:value="A module that modifies target app behavior." />
        
    <!-- Minimum required Xposed API version -->
    <meta-data
        android:name="xposedminversion"
        android:value="82" />

    <!-- Your activities here... -->
</application>
```

---

## Step 4: Write Your Hook Logic

This is where the magic happens. We'll create a class that intercepts a method.

1.  Create a new Java/Kotlin class. Let's call it `MainHook`.
2.  Have the class implement `IXposedHookLoadPackage`. This interface has one method: `handleLoadPackage`, which runs every time a new app is launched.

**Example (Java):**

```java
package com.example.myfirsthook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        // 1. Filter the package: Only run our code if the target app is launching
        if (!lpparam.packageName.equals("com.target.app.package")) {
            return;
        }

        XposedBridge.log("MyFirstHook: Target app launched!");

        // 2. Find and Hook the target method
        // Assume we want to hook a method called "isFeatureEnabled" inside "com.target.app.Settings"
        
        XposedHelpers.findAndHookMethod(
            "com.target.app.Settings", // Fully qualified class name
            lpparam.classLoader,       // The app's classloader
            "isFeatureEnabled",        // The name of the method to hook
            // If the method has arguments, list their classes here, e.g., String.class, int.class
            
            new XC_MethodHook() {
                
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Code here runs BEFORE the original method
                    // You can modify arguments here using param.args
                    XposedBridge.log("Before isFeatureEnabled is called.");
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // Code here runs AFTER the original method
                    // We can intercept the result and change it
                    
                    XposedBridge.log("Original result was: " + param.getResult());
                    
                    // Force the method to return true, regardless of what the app wanted
                    param.setResult(true); 
                    
                    XposedBridge.log("Forced result to: true");
                }
            }
        );
    }
}
```

---

## Step 5: Register the Hook Class

LSPosed needs to know *where* to find your `MainHook` class.

1.  In Android Studio, switch to the **Project** view (top left dropdown).
2.  Navigate to `app/src/main/`.
3.  Create a new directory named `assets` (Right-click `main` -> New -> Directory -> `assets`).
4.  Inside the `assets` folder, create a standard text file named exactly `xposed_init` (no extension).
5.  Open `xposed_init` and type the fully qualified name of your hook class:

    ```text
    com.example.myfirsthook.MainHook
    ```
    *(Ensure there are no trailing spaces or blank lines).*

---

## Step 6: Test Your Module

1.  **Build the APK:** Compile your project in Android Studio and install the APK onto your rooted device.
2.  **Enable the Module:**
    *   Open the **LSPosed Manager** app.
    *   Go to the **Modules** tab at the bottom.
    *   Find your newly installed module and tap on it.
    *   Toggle the **Enable module** switch.
3.  **Set the Scope:**
    *   Below the enable switch, you'll see a list of installed apps.
    *   Check the box next to the target application (e.g., `com.target.app.package`). This is the "scope."
4.  **Restart the Target App:** Force stop the target application and open it again.
5.  **Check Logs:** Open LSPosed Manager, go to the **Logs** tab, and look for the messages you printed using `XposedBridge.log()`. If you see them, your hook was successful!
