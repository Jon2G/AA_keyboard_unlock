# How LSPosed Works: Architecture and Mechanics

LSPosed (recently rebranded as **Vector**) is a modern, systemless implementation of the original Xposed Framework. It allows you to modify the behavior of the Android system and installed applications at runtime without physically altering any system partitions or APK files.

## 1. Core Foundation: Zygisk

The bedrock of LSPosed's modern architecture is **Zygisk**, a feature introduced in modern versions of Magisk and KernelSU.

*   **Zygote Process:** In Android, every application starts from a core process called `Zygote`. When a new app is launched, Android "forks" the Zygote process to create the new app's environment.
*   **Zygote Injection:** Zygisk allows LSPosed to inject code directly into the Zygote process during the device's boot sequence. 
*   **Systemless Nature:** Because LSPosed lives in the Zygote process and only modifies apps in memory (RAM) as they are launched, it doesn't touch the physical files on the device's storage. This systemless approach ensures the device can receive Over-The-Air (OTA) updates smoothly and helps evade detection by apps looking for traditional root modifications.

## 2. The Hooking Engine: LSPlant

To actually change an app's behavior, LSPosed needs a way to intercept the app's internal functions. It does this through "hooking."

*   **LSPlant Framework:** LSPosed uses its own custom-built ART (Android Runtime) hooking library known as **LSPlant**.
*   **Method Interception:** LSPlant works by intercepting method calls in real-time. When a target app tries to run a specific function (e.g., `isDeviceMoving()`), LSPlant catches that call. Instead of running the app's original function, LSPlant redirects the execution flow to the custom code provided by an LSPosed module. The custom code can modify the inputs, the outputs, or completely bypass the original function.

## 3. API Compatibility & Modularity

LSPosed was designed to be a direct, but improved, successor to the Xposed Framework.

*   **API Parity:** LSPosed maintains full compatibility with the classic Xposed Framework API (`de.robv.android.xposed`). This means most modules written for the original Xposed work flawlessly on LSPosed without modification.
*   **Scope Control:** Unlike the original Xposed, which globally injected hooks into every running process (causing performance and stability issues), LSPosed uses a strict **Scope** mechanism. Modules only load and execute within the specific apps you select in the LSPosed Manager. If an app isn't selected, LSPosed ignores it completely, ensuring zero performance penalty for untouched apps.

## 4. The LSPosed Manager

The user-facing component of the architecture is the **LSPosed Manager** app.

*   **Module Management:** The Manager provides a graphical interface to enable or disable installed modules.
*   **Scope Definition:** Crucially, it allows you to define the scope for each module. When an app launches, the injected Zygote checks the Manager's configuration. If the app is within the scope of an enabled module, the LSPlant engine applies the necessary hooks.

## Summary of the Execution Flow

1.  **Boot Phase:** The device boots, and Magisk/KernelSU initializes Zygisk.
2.  **Injection Phase:** Zygisk injects the core LSPosed framework into the Android `Zygote` process.
3.  **App Launch Phase:** You open an application on your phone.
4.  **Scope Verification:** As the app forks from Zygote, the LSPosed framework checks the configuration defined in the LSPosed Manager to see if any enabled modules target this specific app.
5.  **Hooking Phase:** If the app is targeted, LSPosed uses **LSPlant** to load the module's code and hook the requested methods inside the app's memory space, altering its behavior on the fly.
