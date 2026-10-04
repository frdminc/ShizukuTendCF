# ShizukuTendCF Testing & Diagnostics

This guide outlines compilation targets, diagnostic tools, and validation steps for ShizukuTendCF development.

---

## 🛠️ Build Validation

- **Release Compile**:
  Run this command to build and verify a release build of the manager application:
  ```bash
  ./gradlew :manager:assembleRelease
  ```
- **Local Iteration (Debug)**:
  Debug builds skip Sentry symbol uploads and compile faster:
  ```bash
  ./gradlew :manager:assembleDebug
  ```

---

## 🔬 Diagnostics & Troubleshooting

### Service Doctor
If the background ShizukuTendCF daemon fails to start, utilize the diagnostic script provided in the repository:
```bash
bash crash-diagnostics.sh
```
This script audits binder states, system permissions, and identifies blocked execution contexts (such as Samsung's Auto Blocker mechanism).

### Local ADB Proxy (Port 15555)
ShizukuTendCF emulates a local ADB server. To test connection compatibility:
1. Ensure the ShizukuTendCF service is running (Wireless Debugging or Root).
2. Connect legacy apps to the proxy port `15555`.
3. Verify connection logs inside the Activity Log screen.

### Third-Party App Compatibility (Compat Hub)
Because third-party apps look specifically for the package `moe.shizuku.privileged.api`, ShizukuTendCF relies on the **Compat Hub** helper:
- Ensure the Compat Hub companion app is installed on the target device.
- It forwards permissions and binder connections from legacy Shizuku applications to ShizukuTendCF.
