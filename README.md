# 📱 iOS-Inspired Liquid-Glass Floating Camera Overlay

A premium, gesture-rich Android application designed with a high-fidelity **iOS Liquid-Glass** aesthetic. It projects your camera feed into a fully interactive, draggable floating screen overlay while letting you control it directly from the Quick Settings Control Center or via multi-touch gesture inputs.

---

## ✨ Features & Visual Highlights

### 🎨 Premium iOS Liquid-Glass Theme
* **Frosted Glassmorphism**: Cards and overlay panels leverage a highly translucent frosted glass design, accented with subtle white or glowing green gradient outlines.
* **Ambient Lighting Orbs**: The application background glows with premium radial light orbs in Neon Rose, Cyan, and Deep Purple to resemble the modern iOS control center interface.
* **Liquid Transitions**: Polished Material 3 Compose views with dynamic sizing and responsive state adjustments.

### 🎛️ Control Center Tile Integration
* **Instant Quick Actions**: Toggle the camera overlay directly from your system Quick Settings / Control Center.
* **Smart Confirmation Checks**:
  * **Starting**: Clicking the inactive tile prompts you with a confirmation dialog: *"Do you want to start the video screen overlay?"* Choosing **Yes** starts the service in a default **transparent cutout** mode.
  * **Stopping**: Clicking the active tile displays a prompt: *"Are you sure you want to stop the video screen popup?"* to prevent accidental terminations.

### 🔮 Gestures & Hover Actions (Draggable Bubble)
The camera overlay panel responds dynamically to touch inputs:
* **Drag & Relocate**: Effortlessly position the floating bubble anywhere on your screen.
* **Double Tap**: Instantly toggle orientation (landscape/portrait) and reset current rotation.
* **3-Second Static Long Press**: Opens the **Overlay Settings Popup** right in the middle of the screen to quickly adjust scale, opacity, background cutout, or camera options. Tap outside of the dialog to securely close it.
* **5-Second Static Long Press**: Comfortably launches the main companion app to access deep configuration profiles.

### 📐 Comprehensive Customization Modes
Through the main app or the mid-screen gesture menu, customize:
* **Background Cutout & Modes**: Switch between **Original Feed**, **Transparent Cutout**, **Blurred Glass**, **Solid Canvas**, and **Custom Visual Image**.
* **Precise Opacity Scale**: Adjust the transparency of the video feed from 10% up to 100%.
* **Live Sizing**: Resize the floating bubble scale factor seamlessly from 20% to 150%.
* **Dual Camera Flip**: Instantly toggle between Front and Back camera arrays with runtime binding.

---

## 🚀 How to Run & Build

1. **Prerequisites**: Android SDK with API 21 or higher. Jetpack Compose and CameraX dependencies are pre-configured.
2. **Build Application**: Run Gradle compile or sync to assemble the debugging APK.
3. **Overlay Permission**: Grant the `Display over other apps` system permission upon first launch to enable the floating video popup.
4. **Camera Permission**: Allow camera runtime access so the app can output the preview live.

---

## 📂 Project Architecture

* **`MainActivity.kt`**: The host visual dashboard built with Jetpack Compose, featuring the Liquid-Glass theme, background orbs, and manual controls.
* **`CameraOverlayService.kt`**: The core background service managing the floating camera view, multi-touch listeners, long-press gesture timers, and the floating Settings dialog.
* **`CameraOverlayTileService.kt`**: Connects the Android Quick Settings Tile to the service lifecycle, showing confirmation dialogs before changing states.
* **`OverlayState.kt`**: Holds the unified local state model, notifying active listeners upon modification.
