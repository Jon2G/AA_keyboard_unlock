# How to Set Up the Android Auto Screen Simulator (DHU)

To test Android Auto apps without a physical car, Google provides the **Desktop Head Unit (DHU)**. It acts as an emulator for the car's display, connecting directly to your Android phone via Android Studio's SDK tools.

Here is the step-by-step tutorial to get it running.

## Prerequisites
* **Android Studio** installed on your computer.
* An Android phone with the **Android Auto** app installed.
* A USB cable to connect your phone to your computer.

---

## Step 1: Install the DHU via SDK Manager
1. Open **Android Studio**.
2. Go to **Tools** > **SDK Manager** (or click the SDK Manager icon in the toolbar).
3. Select the **SDK Tools** tab.
4. Find and check the box for **Android Auto Desktop Head Unit emulator**.
5. Click **Apply** and wait for the download and installation to complete, then click **Finish**.

## Step 2: Enable Developer Mode in Android Auto
You need to activate developer mode on your physical phone's Android Auto app.
1. Open your phone's **Settings** and search for **Android Auto**.
2. Scroll down to the bottom where it says **Version** (or **Version and permission info**).
3. Tap on the **Version** text **10 times** consecutively.
4. A prompt will appear asking to enable Developer settings; tap **OK**.

## Step 3: Start the Head Unit Server
1. While still in the Android Auto settings on your phone, tap the **three-dot menu** in the top right corner.
2. Select **Developer settings** (or **Start head unit server** if it appears directly in the menu).
3. If inside Developer settings, tap **Start head unit server**. A persistent notification should appear on your phone indicating the server is running.

## Step 4: Set Up Port Forwarding (ADB)
Now, connect your phone to your computer via USB. You need to forward the connection so the DHU can communicate with the phone's head unit server.
1. Open your computer's terminal or command prompt.
2. Run the following ADB (Android Debug Bridge) command:
   `adb forward tcp:5277 tcp:5277`
   *(Note: Ensure USB debugging is enabled on your phone and you have accepted the computer's RSA key fingerprint).*

## Step 5: Launch the Simulator
Finally, you can launch the DHU application.
1. In your terminal, navigate to the directory where the DHU is installed. This is located inside your Android SDK path:
   * **Windows:** `%LOCALAPPDATA%\Android\Sdk\extras\google\auto`
   * **Mac:** `~/Library/Android/sdk/extras/google/auto`
   * **Linux:** `~/Android/Sdk/extras/google/auto`
2. Run the executable:
   * **Windows:** `desktop-head-unit.exe`
   * **Mac/Linux:** `./desktop-head-unit`

Once executed, the DHU window will pop up on your screen, and your phone will project the Android Auto interface into this simulator just as it would in your physical vehicle.


### Note:
The path for my setup is "/Users/jon2g/android/android-sdk"