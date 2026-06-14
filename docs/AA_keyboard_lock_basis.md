# How Android Auto Detects Vehicle Movement to Lock the Keyboard

To understand this deeply, it helps to look at the system architecture. Android Auto doesn't act as a typical standalone application relying solely on your phone's sensors; instead, it operates via a projection protocol. When you connect your phone to the vehicle's head unit, the vehicle itself acts as the primary source of truth for telemetry. The onscreen keyboard lockout is triggered by a specific status flag sent from the car's hardware to the phone, rather than the phone guessing if it's moving based on GPS.

## The Three Layers of Movement Detection

### 1. Vehicle Telemetry (CAN Bus & VSS)
The fastest and most accurate trigger comes from the vehicle's internal network, the Controller Area Network (CAN bus).
* **Vehicle Speed Sensor (VSS):** Sensors located on the wheel hubs measure exact rotational speed. This raw data is broadcast across the car's internal network.
* **Transmission State:** The transmission control module continuously broadcasts whether the car is in Park, Drive, Reverse, or Neutral.
* The head unit reads these CAN frames. If it detects rotational speed above zero or a shift out of Park, it packages this data into an API payload and commands Android Auto to lock the interface to prevent distracted driving.

### 2. The Parking Brake Circuit (Hardware Level)
Many head units also monitor a physical analog wire connected directly to the parking brake mechanism.
* When the parking brake is engaged, a physical switch closes and grounds the wire.
* The head unit interprets this grounded state as "Vehicle Parked/Safe" and signals Android Auto to allow keyboard input.
* *Bypass Exploit:* Because this relies on a physical circuit, aftermarket installers will sometimes permanently ground this specific wire to the chassis. This tricks the unit's logic gate into believing the parking brake is always engaged, overriding the keyboard lock entirely.

### 3. Phone Sensor Fallback (GPS & Accelerometer)
If a vehicle's head unit is basic and fails to forward CAN bus telemetry or parking brake status, Android Auto will fall back to querying the phone's native hardware.
* **Location Services:** It calculates speed over time using GPS coordinates.
* **Activity Recognition API:** It monitors the phone's accelerometer and gyroscope to detect the specific vibration frequencies and motion profiles associated with driving a car.
* Because this fallback method relies on signal estimation rather than direct mechanical feedback, it can suffer from lag. This is usually why the keyboard sometimes remains "stuck" in a locked state for a few moments after coming to a complete stop.

## Why the Experience Varies by Vehicle
Because the lockout heavily depends on how the Original Equipment Manufacturer (OEM) implemented the head unit firmware, the experience is not uniform. Depending on the car's internal code, one vehicle might trigger the lockout the millisecond the gear shifts to Drive, while another might wait until the VSS registers a speed of 5 km/h.