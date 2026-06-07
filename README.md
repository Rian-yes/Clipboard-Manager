# Clipboard Manager

A highly advanced, reactive, and context-aware clipboard manager for Android. By utilizing **Shizuku** and **Hidden API Bypass**, this application securely bypasses background clipboard restrictions introduced in modern Android versions, allowing for robust, low-latency background monitoring, comprehensive metadata harvesting, and automated clip organization.

---

## 🚀 Key Features

* **Low-Level Background Monitoring via Shizuku:** Leverages Shizuku to communicate directly with system services (`IClipboard`, `IActivityManager`, `IPackageManager`) through IPC transactions. This bypasses background clipboard access limitations seamlessly without needing root permissions.
* **Rich Device Context & Metadata Harvesting:** Captures more than just text or images; it reads the source application name, package identifier, current user ID, active window title labels, and specific target activity names. It even evaluates the *previous* app context to trace copy-paste origins.
* **Automation Rules Engine:** Automatically tags, pins, stars, or marks incoming clipboard content as sensitive based on user-defined criteria, such as app package matchers or regex pattern rules.
* **On-Device SQL Console:** Includes an interactive developer pane to execute raw or custom SQLite strings directly on the background database.
* **Lock-Screen Awareness:** Features a failure-resilient system that automatically queues and retries pending clip acquisitions as soon as the screen is unlocked (`ACTION_USER_PRESENT`).
* **Granular Privacy Filters:** Explicitly blacklists certain applications from being monitored, classifies individual packages as sensitive, and controls exactly which structural fields are allowed to persist in the local database.
* **Scoped Storage Backups:** Seamlessly export or import complete database configurations into compressed `.zip` archives via Android's Storage Access Framework (SAF).
* **Quick Settings Integration:** Synchronizes tracking toggles on the fly with a Quick Settings developer tile (`ClipboardTileService`).

---

## 🛠️ Architecture & Component Breakdown

### 1. Application Core & Initialization

* **`MainApplication.kt`**: Coordinates application initialization by enabling multi-process support for Shizuku and applying non-SDK reflection exemptions via `HiddenApiBypass` for Android 9+ (API 28+).
* **`BootReceiver.kt`**: A standard `BroadcastReceiver` that handles `ACTION_BOOT_COMPLETED` and `QUICKBOOT_POWERON` to automatically start monitoring services as soon as the device boots up, respecting individual preference settings.

### 2. Background Infrastructure

* **`ClipboardService.kt`**: The core component of the app. Running as a foreground service, it establishes a persistent hook via `IOnPrimaryClipChangedListener` through a Shizuku binder proxy. It evaluates incoming clips against active blacklists, manages multi-user profiles, hashes and saves clipboard image binaries to disk, and runs matching routines for automation rule criteria.

### 3. UI & Reactive Presentation Layer

* **`MainActivity.kt`**: The main entry point for interaction. It manages user permissions, updates display options based on runtime states, and handles context-menu interactions.
* **`ClipboardViewModel.kt`**: Manages data flow reactively across IO streams. It filters the database across distinct scopes (`ALL`, `PINNED`, `FAVORITES`, `IMAGES`, `SENSITIVE`), hosts live string search matching, updates clipboard state configurations, and handles atomic multi-selection batch deletions.
* **`ClipboardAdapter.kt`**: A `ListAdapter` backed by a high-performance `DiffUtil` callback. It handles layout updates dynamically, manages expandable detail panels for field telemetry, hides sensitive items until tapped, and updates views efficiently using structural payloads.

### 4. Customization & Advanced Modules

* **`AdvancedActivity.kt`**: Hosts a `ViewPager2` workspace featuring:
1. *SQL Console Panel*: Allows developers to input raw SQL statements directly into the database interface.
2. *Automation Rules Configurator*: An interface to declare conditional match rules targeting properties like source packages, string text patterns, and type classifications to trigger custom metadata transformations.


* **`SettingsActivity.kt`**: Provides configuration panels for system toggles, metadata logging constraints, metadata visibility displays, cross-profile app selection pickers, and zip file backup mechanics.
* **`ShizukuActivity.kt`**: Manages connection states for the Shizuku API binder, provides visual guides for user permissions, and prompts users for notification authorizations and background battery optimization exclusions.
* **`EditClipActivity.kt`**: Provides full edit control over existing items, allowing manual adjustments to core text strings, package indicators, and window tags.

---

## ⚙️ Technical Blueprint

### Background Clipboard Bypassing (Android 10+)

Since Android 10, background apps are blocked from reading clipboard contents via the standard `ClipboardManager`. This application overcomes that by leveraging **Shizuku** to access the underlying system service binder directly:

```kotlin
// Transacting via ShizukuBinderWrapper to route actions through shell UID 2000
amBinder.transact(transCode, data, reply, 0)
reply.readException()

```

This interaction provides the necessary authorization level to query foreground window state properties and monitor clipboard streams securely.

### Context-Aware Processing Pipeline

1. **Event Fired**: `IOnPrimaryClipChangedListener` captures an updated event.
2. **Context Analysis**: Service queries `IActivityManager` to identify the package, class name, and active window parameters responsible for the copy transaction.
3. **Sanitization Evaluation**: The item is cross-checked with active user filters (e.g., if it matches an excluded package, it is safely skipped).
4. **Automation Engine Execution**: Custom automation rule filters look for a text string or type match to adjust settings like pinning or favoriting.
5. **Persistence**: The parsed clip item is stored in the database, updating the reactive UI view models instantly.

---

## 📋 Setup & Requirements

1. **Android OS**: Android 10.0 (API 29) or higher.
2. **Prerequisite**: A running instance of the [Shizuku](https://shizuku.rikka.app/) manager app on the target device (authorized via ADB or root access).
3. **Permissions**:
* Shizuku Binder Access (requested at runtime).
* Post Notifications (for background execution stability).
* Battery Optimization Exclusion (optional, to keep the background service alive reliably).
