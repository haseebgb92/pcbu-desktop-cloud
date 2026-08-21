# Internet biometric unlock setup

## What is deployed

- Relay: `https://pcbu-relay.advsolar.workers.dev`
- Cloudflare Worker: `pcbu-relay`
- D1 database: `pcbu-relay` (`APAC`)
- The relay stores only short-lived encrypted request/response envelopes. It never receives the PC password, pairing encryption key, biometric data, or decrypted unlock contents.

## Install

1. Build/install the desktop application and its Windows credential provider as administrator.
2. Install `android-app/app/build/outputs/apk/debug/app-debug.apk` on the phone. Android may require enabling installation from the app used to open the APK.
3. Open **PC Bio Unlock Cloud** on Android.
4. Choose a unique user ID and a password of at least 12 characters, then tap **Create profile**.
5. In the desktop pairing wizard choose **Automatic** and **Use Internet relay**.
6. Scan the desktop QR code from the Android app. Initial pairing intentionally happens on the local Wi-Fi because that is the trusted channel that transfers the phone's random device token and the existing end-to-end encryption key.

## Use

After pairing, the PC and phone may be on completely different internet connections. When Windows needs credentials, the PC posts an encrypted one-time challenge. Android shows a high-priority notification. Tap it and approve with a strong fingerprint or face biometric. Windows still validates the real account password normally.

The Android foreground notification must remain enabled so the operating system keeps the internet listener alive. On phones with aggressive battery management, exclude **PC Bio Unlock Cloud** from battery optimization.

## Recovery and safety

- Press left Ctrl + left Alt at the PC to cancel and use the normal password.
- Removing a phone from the desktop invalidates its locally stored pairing.
- Cloud requests expire after two minutes and responses are single-use.
- The Android package ID is `com.pcbiounlock.cloud`, so this development companion installs alongside the Play Store app.

## Verification performed

- Cloud Worker TypeScript strict type-check
- Android API 36 debug APK build
- Production health check
- Production integration transaction: create profile, log in, enroll phone, submit encrypted request, phone poll, submit response, PC consume response
- Synthetic production test data removed after verification
