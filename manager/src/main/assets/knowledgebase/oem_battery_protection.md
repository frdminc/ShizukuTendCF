# Background Keep-Alive & Battery Optimization

### Keeping ShizukuPlus Alive
Modern Android manufacturers enforce aggressive background task killers that terminate background services even if battery optimization is turned off.

### Manufacturer-Specific Instructions

#### 1. Samsung Galaxy (One UI 6, 7, 8)
- **Auto Blocker (Knox Rampart)**:
  - Settings → Security and privacy → **Auto Blocker** → Turn **OFF**.
  - One UI 6.1+ Auto Blocker actively terminates background ADB processes and blocks local debugging sockets.
- **Never Sleeping Apps**:
  - Settings → Battery → **Background usage limits** → **Never sleeping apps**.
  - Add **ShizukuPlus** to prevent Samsung from placing it in the frozen deep sleep state.

#### 2. Xiaomi / Redmi / POCO (HyperOS & MIUI)
- **Autostart Permission**:
  - Settings → Apps → Manage apps → ShizukuPlus → Turn **Autostart ON**.
- **Battery Saver**:
  - Set Battery Saver to **No restrictions** (stops the Xiaomi *Millet* daemon from suspending the process).

#### 3. OnePlus / OPPO / Realme (ColorOS / OxygenOS)
- **Background Activity**:
  - App info → Battery usage → Allow **Foreground activity** and **Background activity**.
  - Turn off **Auto-freeze**.

#### 4. Google Pixel & Stock Android
- **App Battery Usage**:
  - Settings → Apps → ShizukuPlus → App battery usage → Select **Unrestricted** (prevents Android from demoting the app to Standby Bucket 45).

### ShizukuPlus Device Hardening
Under **Settings → Behavior → Device Hardening**, ShizukuPlus can automatically maintain its own battery exemptions and reset restrictive app-op flags directly using shell privileges.
