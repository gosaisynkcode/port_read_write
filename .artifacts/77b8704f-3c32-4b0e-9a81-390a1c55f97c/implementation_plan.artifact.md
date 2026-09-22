# ICT Bill Acceptor Serial Integration Plan

This plan outlines the steps to integrate the ICT L704 bill acceptor into the Android app using serial communication. Based on the images provided, the device is connected to `/dev/ttyS4` and requires RS232 communication (ICT-002 protocol).

## User Review Required

> [!IMPORTANT]
> **Serial Port Access**: Accessing `/dev/ttyS4` usually requires the device to be rooted or have specific permissions set on the serial port file (e.g., `chmod 666 /dev/ttyS4`). Ensure your kiosk machine allows the app to access this path.

> [!NOTE]
> **ICT-002 Protocol**: The plan assumes the standard ICT-002 RS232 protocol where:
> - **Enable Command**: `0x3E`
> - **Disable Command**: `0x5E`
> - **Baud Rate**: 9600 bps

## Proposed Changes

### UI Layer

#### [MODIFY] [activity_main.xml](file:///C:/Users/Admin/AndroidStudioProjects/AndroidEasySerialPort/app/src/main/res/layout/activity_main.xml)
- Add a `ScrollView` containing a `TextView` to display incoming bill data and status logs.
- Add "Enable" and "Disable" buttons to control the bill acceptor.

### Logic Layer

#### [MODIFY] [MainActivity2.kt](file:///C:/Users/Admin/AndroidStudioProjects/AndroidEasySerialPort/app/src/main/java/com/example/androideasyserialport/MainActivity2.kt)
- Initialize the `SerialPort` using the `cn.lalaki:SerialPort.Android` library.
- Configure port: path `/dev/ttyS4`, speed `9600`, 8 data bits, 1 stop bit, no parity.
- Implement `DataCallback` to process incoming bytes from the bill acceptor.
- Map received bytes (e.g., `0x81`, `0x82`) to bill denominations.
- Implement `enable()` and `disable()` methods to send control bytes to the device.

---

## Verification Plan

### Manual Verification
1. **Deployment**: Run the app on the kiosk machine.
2. **Connection**: Check if the serial port opens successfully (log message).
3. **Control**: Press "Enable" and verify the bill acceptor lights up/starts.
4. **Data Reading**: Insert a bill and verify the `TextView` displays the bill value (e.g., "Bill Accepted: $1").
5. **Disable**: Press "Disable" and verify the bill acceptor stops accepting bills.
