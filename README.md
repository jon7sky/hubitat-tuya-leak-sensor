# Tuya Zigbee Water Leak Sensor, Hubitat driver

A fork of Krassimir Kossev's [Tuya NEO Coolcam Zigbee Water Leak Sensor](https://github.com/kkossev/Hubitat/tree/main/Drivers/Tuya%20NEO%20Coolcam%20Zigbee%20Water%20Leak%20Sensor) driver (ver. 1.1.4), with an offline check that keeps working.

## Why this fork exists

The upstream driver reports `healthStatus` **offline** when a sensor has been silent for about 13 hours, but the check that decides this can stop running and never come back. When that happens, a dead leak sensor shows **online** and **dry** indefinitely.

The check was a chain: each run used `runIn()` to book the next one an hour later. If one link was lost (a database restore, a job dropped around a reboot, an exception in the check before it booked the next one), nothing restarted it. A dead sensor sends nothing that could. In practice this left a sensor silent for 14 months, still reported online, with no scheduled jobs on its device page.

## Changes from upstream

1. **Persistent schedule.** The health check is an hourly cron `schedule()`, which the hub keeps across reboots. It does not reschedule itself. It is also re-asserted at hub start-up (via capability `Initialize`), on **Save Preferences**, on **Configure**, and whenever the driver version changes.
2. **Time-based offline detection.** Offline is decided from the time the sensor was last heard, not by counting checks, so a missed or extra check can't shift the deadline. On the first check after this driver is assigned, the hub's own *Last Activity* time is used, so a sensor that is already dead goes offline immediately instead of 13 hours later.
3. **"Mark offline after" preference**: 4, 8, 13 or 24 hours. The default is 13, the upstream timeout.
4. **`initialize()` no longer wipes the device.** Upstream's `initialize()` ran `configure()`, a full reset. It now only restores the schedules. **Configure** is still the full reset, but it keeps the last-heard time so it doesn't restart the offline clock.
5. **State `lastSeen`** shows when the sensor was last heard. `notPresentCounter` now counts hours of silence.
6. **Exact-match fingerprint for TS0207 `_TZ3000_k4ej3ww2`** (1.2.1). These units report `outClusters: 000A` at join, without the `0019` the upstream fingerprint expects. Hubitat therefore never matched the upstream fingerprint exactly and paired them with whichever driver matched the clusters best, for example *Tuya Multi Sensor 4 In 1*. A second fingerprint with the clusters these units actually report fixes that.

Parsing, and water and battery handling, are unchanged.

## If a sensor pairs with the wrong driver

Hubitat picks a driver by comparing what the sensor reports at join against each driver's fingerprints. To see what a sensor reported, open **Logs → Past logs** and search for `inClusters`. Look for the *Zigbee device endpoints* line from the time it joined. If its model, manufacturer, or cluster lists aren't in this driver's fingerprints, open an issue with that line.

## Install

**Hubitat Package Manager:** *Install → From a URL*, then paste:

```
https://raw.githubusercontent.com/jon7sky/hubitat-tuya-leak-sensor/main/packageManifest.json
```

Or manually: *Drivers Code → New Driver → Import*, then paste:

```
https://raw.githubusercontent.com/jon7sky/hubitat-tuya-leak-sensor/main/drivers/tuya-water-leak-sensor.groovy
```

## Moving existing sensors to this driver

1. On the device page, change **Type** to *Tuya Zigbee Water Leak Sensor* (under the user drivers) and click **Save Device**.
2. Click **Save** on the Preferences tab. This books the hourly health check. **Configure** also works but resets the device's state.
3. Check the **Scheduled Jobs** tab. It should list `deviceHealthCheck` with an hourly cron schedule.

A sensor that has already been silent longer than the "Mark offline after" time goes **offline** at the next hourly check.

## Alerts

`healthStatus` changing to `offline` is the event to alert on, for example with a Rule Machine *Custom Attribute* trigger on `healthStatus` = `offline` and a notification action. When a sensor goes offline, `water` is also set to `unknown`.

## Licence

Apache License 2.0, inherited from upstream. See [LICENSE](LICENSE). The modified file carries a notice of the changes in its header.
