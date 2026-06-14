# Android Auto Keyboard Lock APIs

To lock the onscreen keyboard, Android Auto and Android Automotive OS rely on a specific set of APIs within the Android framework to query both the vehicle's hardware and the phone's internal sensors.

Here is the breakdown of the exact APIs and classes called during this process:

## 1. Primary Vehicle Telemetry (Android Car API)
The primary and most accurate method uses the **Android Car API** (found in the `android.car` package) to interface with the vehicle's Hardware Abstraction Layer (Vehicle HAL). The Vehicle HAL serves as the bridge allowing the Android OS to communicate directly with the car's physical sensors.

* **`CarPropertyManager`**: This is the core API class at the framework layer used to retrieve and modify vehicle properties.
* **`CarPropertyManager.registerCallback()`**: Android Auto calls this method to register a `CarPropertyEventCallback` listener. This listener receives real-time updates pushed via Binder IPC whenever the vehicle's hardware state changes.
* **Targeted Property IDs**: Android Auto subscribes to specific `VehiclePropertyIds` to determine if it is safe to unlock the keyboard:
    * **`VehiclePropertyIds.PERF_VEHICLE_SPEED`**: Streams the exact current speed directly from the vehicle.
    * **`VehiclePropertyIds.GEAR_SELECTION`**: Monitors the transmission state to determine if the car has been shifted into Drive or Reverse.
    * **`VehiclePropertyIds.PARKING_BRAKE_ON`**: Checks the boolean state of the physical parking brake.
* **Required Permissions**: Accessing these specific speed and hardware states requires privileged system permissions, such as declaring `<uses-permission android:name="android.car.permission.CAR_SPEED" />` and `android.permission.READ_VEHICLE_SPEED` within the manifest.

## 2. Phone Sensor Fallback APIs
If the head unit is an older or aftermarket model that fails to pass hardware telemetry through the Vehicle HAL, Android Auto falls back to native mobile APIs to guess the vehicle's state:

* **Fused Location Provider API (`FusedLocationProviderClient`)**: Part of Google Play Services, this API is called to track GPS coordinates and calculate the device's velocity over time. If the velocity exceeds a walking pace, the keyboard locks.
* **Activity Recognition API (`ActivityRecognitionClient`)**: This API monitors the phone's internal accelerometer and gyroscope. It utilizes machine learning models to detect specific vibration frequencies and outputs an `IN_VEHICLE` confidence score, which Android Auto uses to trigger the driving interface lockout.