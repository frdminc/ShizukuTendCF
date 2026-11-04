# Terminal & Rish Shell Setup

### What is Rish?
`rish` (Rikka Shizuku Shell) runs privileged terminal commands under `uid=2000(shell)` directly inside Termux or any terminal emulator, without requiring a computer, network cables, or root access.

### Setting Up in Termux
1. In ShizukuPlus, tap **Use Shizuku in terminal apps** on the Home screen.
2. Tap **Export files** to save `rish` and `rish_shizuku.dex`.
3. In Termux, run:
   ```sh
   # Copy binary and dex to Termux bin directory
   cp /sdcard/Android/data/af.shizuku.plus.api/files/rish ~/../usr/bin/rish
   chmod +x ~/../usr/bin/rish
   ```
4. Verify execution:
   ```sh
   rish -c "id"
   ```
5. Approve the ShizukuPlus authorization prompt when it appears.

### Interactive Terminal (PTY Support)
ShizukuPlus features a native Pseudo-Terminal (PTY) engine. Running `rish` without arguments launches an interactive shell that supports text editors (`nano`, `micro`, `vim`), pager tools (`less`, `top`), and full ANSI color formatting with terminal window resizing.

### Exclusive Plus Commands
- `plus freeze <package_name>`: Instantly disables an app without deleting data.
- `plus unfreeze <package_name>`: Restores a frozen app.
- `plus appops set <pkg> <op> <mode>`: Manages fine-grained runtime permissions.
