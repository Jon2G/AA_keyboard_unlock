# Simulating Vehicle Movement in the DHU

Once the Android Auto Desktop Head Unit (DHU) is running, you can simulate real-time vehicle telemetry—such as speed, gear position, and parking brake status—directly from the terminal or command prompt where you launched the DHU. 

The DHU console acts as a live command-line interface. By typing specific sensor commands and hitting **Enter**, you can instantly trick Android Auto into thinking the car is moving, which is essential for testing features like the driving-mode keyboard lockout.

## Essential Movement Commands

Ensure the terminal window running the DHU is focused, then type any of the following commands:

### 1. Speed
Sets the vehicle's speed in **meters per second (m/s)**.
* **To simulate moving:** Type `speed 15` (Simulates moving at 15 m/s, or ~54 km/h).
* **To simulate stopping:** Type `speed 0`.
* **To deactivate sensor:** Type `speed` (with no value).

### 2. Gear Position
Changes the transmission state of the vehicle using specific integer values.
* **Drive:** Type `gear 100`
* **Park:** Type `gear 101`
* **Reverse:** Type `gear 102`
* **Neutral:** Type `gear 0`

### 3. Parking Brake
Engages or disengages the electronic parking brake.
* **Release brake (Ready to drive):** Type `parking_brake false`
* **Engage brake (Parked):** Type `parking_brake true`

---

## Example: Triggering the Keyboard Lockout
To completely simulate a "Driving" state that will lock the Android Auto onscreen keyboard, run these commands in sequence in your terminal:
1. `parking_brake false` *(Releases the parking brake)*
2. `gear 100` *(Shifts into Drive)*
3. `speed 10` *(Accelerates to 10 m/s)*

To unlock the keyboard (simulate parking):
1. `speed 0`
2. `gear 101`
3. `parking_brake true`

*(Note: Depending on your DHU version, some advanced sensor simulations might require starting the DHU with a configuration file, e.g., `./desktop-head-unit -c config/default_sensors.ini`, to explicitly initialize the sensor listeners).*