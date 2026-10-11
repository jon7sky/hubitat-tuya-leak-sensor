/**
 *  Tuya / NEO Coolcam Zigbee Water Leak Sensor driver for Hubitat
 *
 *  Copyright (c) Krassimir Kossev
 *  Copyright (c) 2026 John Sevinsky
 *
 *  Derived from "Tuya NEO Coolcam Zigbee Water Leak Sensor" ver. 1.1.4 by Krassimir Kossev:
 *  https://github.com/kkossev/Hubitat/tree/main/Drivers/Tuya%20NEO%20Coolcam%20Zigbee%20Water%20Leak%20Sensor
 *  https://community.hubitat.com/t/release-tuya-neo-coolcam-zigbee-water-leak-sensor/91370
 *
 *  MODIFIED 2026-10-11 by John Sevinsky. Changes from the original:
 *
 *  1. The offline check survives. The original ran it as a chain: each check used runIn() to book
 *     the next one an hour later. If one link was ever lost (a database restore, a job dropped
 *     around a reboot, an exception in the check before it booked the next one), nothing restarted
 *     it -- a dead sensor sends nothing that could -- and it stayed "online" forever. This was seen
 *     in practice: a sensor silent for 14 months, still online, with no scheduled jobs.
 *     Here the check is a cron schedule(), which the
 *     hub keeps across reboots, and it is re-asserted on hub start-up (capability Initialize), on
 *     Save Preferences, on Configure, and whenever the driver version changes.
 *  2. Offline is decided from the time the sensor was last heard, not from a counter of checks. A
 *     missed or extra check can no longer shift the deadline. On the first check after the driver
 *     is assigned, the hub's own Last Activity time is used, so a sensor that was already dead is
 *     marked offline at once instead of 13 hours later.
 *  3. Preference "Mark offline after" (4, 8, 13 or 24 hours; default 13, the original's timeout).
 *  4. initialize() no longer wipes the device state; it only restores the schedules. Configure is
 *     still the full reset it was.
 *  5. State lastSeen shows when the sensor was last heard; notPresentCounter now counts hours of
 *     silence.
 *
 *  Everything else -- parsing, fingerprints, water and battery handling -- is unchanged.
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 * 
 * ver. 1.0.0 2022-03-26 kkossev  - Inital test version
 * ver. 1.0.1 2022-04-12 kkossev  - added _TYST11_qq9mpfhw fingerprint
 * ver. 1.0.2 2022-04-14 kkossev  - Check-in info logs; model 'q9mpfhw' inClusters correction
 * ver. 1.0.3 2022-04-16 kkossev  - 'Last Updated' workaround for NEO sensors
 * ver. 1.0.4 2022-05-14 kkossev  - code cleanup; debug logging is off by default; fixed debug logging not turning off after 24 hours; added Configure button
 * ver. 1.0.5 2022-08-03 kkossev  - added batterySource, added watchDog, set battery 0% if OFFLINE
 * ver. 1.0.6 2022-11-15 kkossev  - fixed _TZ3000_qdmnmddg fingerprint; added _TZ3000_rurvxhcx ; added _TZ3000_kyb656no ;
 * ver. 1.0.7 2022-11-20 kkossev  - offline timeout increased to 12 hours; Import button loads the dev. branch version; Configure will not reset power source to '?'; Save Preferences will update the driver version state; water is set to 'unknown' when offline
 *                                  added lastWaterWet time in human readable format; added device rejoinCounter state; water is set to 'unknown' when offline; added feibit FNB56-WTS05FB2.0; added 'tested' water state; pollPresence misfire after hub reboot bug fix
 *                                  added Momentary capability - push() button will generate a 'tested' event for 2 seconds; added Presence capability; 
 * ver. 1.0.8 2023-05-13 kkossev  -  'unprocessed water event unknown' fix; lastWaterWet update bug fix;
 * ver. 1.1.0 2023-07-11 kkossev  - replaced Presence w/ healthStatus; added TS0207 _TZ3000_js34cuma; removed manipulating the powerSource and dropping the battery level 0% when offline.
 * ver. 1.1.1 2023-07-28 kkossev  - added ping rtt; added zigbee.enrollResponse() in the configuration; added TS0207 _TZ3000_2wcynpml
 * ver. 1.1.2 2024-05-11 kkossev  - added ThirdReality 3RWS18BZ 
 * ver. 1.1.3 2024-08-02 kkossev  - added Sonoff SNZB-05P
 * ver. 1.1.4 2025-03-01 kkossev  - added TS0207 _TZ3000_k4ej3ww2 _TZ3000_d16y6col _TZ3000_ww9i3e0y _TZ3000_qhozxs2b _TZ3000_85czd6fy _TZ3000_ocjlo4ea _TZ3000_fxvjhdyl _TZ3000_eit7p838 _TZ3000_it1hm1cr _TZ3000_awvmkayh _TZ3000_kstbkt6a _TZ3000_arw23zcs _TZ3000_wuep9zng
 * ver. 1.2.0 2026-10-11 jon7sky  - fork: persistent cron health check, time-based offline detection, 'Mark offline after' preference (see above)
 *
 *                                  TODO:  scheduleCommandTimeoutCheck()
 *                                  TODO: check why Neo Coolcam is not sending actual battery reports : https://community.hubitat.com/t/release-tuya-neo-coolcam-zigbee-water-leak-sensor/91370/86?u=kkossev 
 *                                  TODO: add batteryLastReplaced event; add 'Testing option'; add 'isTesting' state; 
 *
*/

def version() { "1.2.0" }
def timeStamp() {"2026/10/11 10:30 AM"}

@Field static final Boolean debug = false
@Field static final Boolean debugLogsDefault = true

import groovy.json.*
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import hubitat.device.HubAction
import hubitat.device.Protocol
import hubitat.zigbee.clusters.iaszone.ZoneStatus
 
metadata {
    definition (name: "Tuya Zigbee Water Leak Sensor", namespace: "jon7sky", author: "Krassimir Kossev, John Sevinsky", importUrl: "https://raw.githubusercontent.com/jon7sky/hubitat-tuya-leak-sensor/main/drivers/tuya-water-leak-sensor.groovy", singleThreaded: true ) {
        capability "Sensor"
        capability "Initialize"         // hub calls initialize() at start-up; used to restore the health check schedule
        capability "Battery"
        capability "WaterSensor"        
        capability "PowerSource"
        capability "TestCapability"
        capability "Momentary"
        capability "Health Check"		// replaced capability "PresenceSensor"
        //capability "TamperAlert"      // tamper - ENUM ["clear", "detected"]
        
        attribute 'healthStatus', 'enum', ['unknown', 'offline', 'online']
        attribute "rtt", "number" 

        
        command "configure", [[name: "Manually initialize the sensor after switching drivers.  \n\r   ***** Will load the device default values! *****" ]]
        command "wet", [[name: "Manually switch the Water Leak Sensor to WET state" ]]
        command "dry", [[name: "Manually switch the Water Leak Sensor to DRY state" ]]
        command "push", [[name: "Manually switch the Water Leak Sensor to TESTED state" ]]
        if (debug==true) {
            command "test"
        }
        
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0004,0005,EF00",      outClusters:"0019,000A", model:"TS0601", manufacturer:"_TZE200_qq9mpfhw", deviceJoinName: "NEO Coolcam Leak Sensor"          // vendor: 'Neo', model: 'NAS-WS02B0', 'NAS-DS07'
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003",                outClusters:"0003,0019", model:"q9mpfhw",manufacturer:"_TYST11_qq9mpfhw", deviceJoinName: "NEO Coolcam Leak Sensor SNTZ009" // SNTZ009
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0004,0005,EF00",      outClusters:"0019,000A", model:"TS0601", manufacturer:"_TZE200_jthf7vb6", deviceJoinName: "Tuya Leak Sensor TS0601"          // vendor: 'TuYa', model: 'WLS-100z'
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0500,EF01", outClusters:"0003,0019", model:"TS0207", manufacturer:"_TYZB01_sqmd19i1", deviceJoinName: "Tuya Leak Sensor TS0207 Type I"   // round cabinet, sensors on the bottom
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0500,EF01", outClusters:"0003,0019", model:"TS0207", manufacturer:"_TYZB01_o63ssaah", deviceJoinName: "Blitzwolf Leak Sensor BW-IS5" 
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_upgcbody", deviceJoinName: "Tuya Leak Sensor TS0207 Type II"  // rerctangular cabinet, external sensor; +BatteryLowAlarm!?
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_t6jriawg", deviceJoinName: "Moes Leak Sensor TS0207"          // Moes
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,000A,0500,0001",      outClusters:"0019",      model:"TS0207", manufacturer:"_TZ3000_qdmnmddg", deviceJoinName: "Tuya Leak Sensor TS0207 Type II"  // https://community.hubitat.com/t/aliexpress-has-flash-sale-on-tuya-zigbee-leak-sensor-9-28/93727/3?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_rurvxhcx", deviceJoinName: "Tuya Leak Sensor TS0207 Type III" // https://community.hubitat.com/t/aliexpress-has-flash-sale-on-tuya-zigbee-leak-sensor-9-28/93727/13?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_kyb656no", deviceJoinName: "MEIAN Water Leak Sensor"          // https://community.hubitat.com/t/release-tuya-neo-coolcam-zigbee-water-leak-sensor/91370/22?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,000A,0019,0001,0500,0501,1000", outClusters:"0004,0003,0001,0500,0501", model:"FNB56-WTS05FB2.0", manufacturer:"feibit", deviceJoinName: "Feibit SWA01ZB Water Leakage  Sensor"         // https://community.hubitat.com/t/release-tuya-neo-coolcam-zigbee-water-leak-sensor/91370/41?u=kkossev 
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,000A,0019,0001,0500,0501,1000", outClusters:"0004,0003,0001,0500,0501", model:"FNB56-WTS05FB2.4", manufacturer:"feibit", deviceJoinName: "Feibit SWA01ZB Water Leakage  Sensor"         // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_js34cuma", deviceJoinName: "Tuya Leak Sensor TS0207 Type II"  // KK
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_2wcynpml", deviceJoinName: "Tuya Leak Sensor TS0207"          // https://community.hubitat.com/t/2nd-water-sensor-will-not-report/122112?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,FF01,FF00,0001,0500", outClusters:"0019,0006", model:"3RWS18BZ", manufacturer:"Third Reality, Inc", deviceJoinName: "ThirdReality Water Leak Sensor"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0020,0500,FC57", outClusters:"0003,0019", model:"SNZB-05P", manufacturer:"SONOFF", controllerType: "ZGB", deviceJoinName: "Sonoff SNZB-05 Water Leak Sensor"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_k4ej3ww2", deviceJoinName: "Tuya Leak Sensor TS0207"          // https://community.hubitat.com/t/release-tuya-neo-coolcam-zigbee-water-leak-sensor/91370/159?u=kkossev
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_d16y6col", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_ww9i3e0y", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_qhozxs2b", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_85czd6fy", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_ocjlo4ea", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_fxvjhdyl", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_eit7p838", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_it1hm1cr", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_awvmkayh", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_kstbkt6a", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_arw23zcs", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0001,0003,0500,0000",      outClusters:"0019,000A", model:"TS0207", manufacturer:"_TZ3000_wuep9zng", deviceJoinName: "Tuya Leak Sensor TS0207"          // not tested
    }
    preferences {
        input (name: "logEnable", type: "bool", title: "Debug logging", description: "<i>Debug information, useful for troubleshooting. Recommended value is <b>false</b></i>", defaultValue: debugLogsDefault)
        input (name: "txtEnable", type: "bool", title: "Description text logging", description: "<i>Display sensor states in HE log page. Recommended value is <b>true</b></i>", defaultValue: true)
        input (name: "offlineHours", type: "enum", title: "Mark offline after", description: "<i>Hours without hearing from the sensor before healthStatus becomes <b>offline</b>. Most sensors check in every few hours; default is <b>13</b></i>", options: ["4": "4 hours", "8": "8 hours", "13": "13 hours", "24": "24 hours"], defaultValue: "13")
        /*
        input (name: "testingDelay", type: "bool", title: "Enable Delayed Testing", description: "<i>If state stays 'wet' for less than NN seconds, assume this was a test</i>", defaultValue: false)
        if (testingDelay?.value == true) {
            input (name: "minimumWetTime", type: "number", title: "<b>Minimum 'wet' time</b>", description: "<i>The minimum time the leak sensor must report 'wet' state to be considered as a real alarm. Default = 3 seconds</i>", range: "0..7200", defaultValue: 3)
        }
        */
        
    }
}

// Constants
@Field static final int COMMAND_TIMEOUT = 10                // Command timeout before setting healthState to offline
@Field static final Integer DEFAULT_OFFLINE_HOURS = 13      // original PRESENCE_COUNT_THRESHOLD x 1 hour
@Field static final Long    MILLIS_PER_HOUR = 3600000L
@Field static final Integer MAX_PING_MILISECONDS = 10000    // rtt more than 10 seconds will be ignored
@Field static String UNKNOWN = "UNKNOWN"

private getCLUSTER_TUYA()       { 0xEF00 }
private getSETDATA()            { 0x00 }
private getSETTIME()            { 0x24 }

// Tuya Commands
private getTUYA_REQUEST()       { 0x00 }
private getTUYA_REPORTING()     { 0x01 }
private getTUYA_QUERY()         { 0x02 }
private getTUYA_STATUS_SEARCH() { 0x06 }
private getTUYA_TIME_SYNCHRONISATION() { 0x24 }

// tuya DP type
private getDP_TYPE_RAW()        { "01" }    // [ bytes ]
private getDP_TYPE_BOOL()       { "01" }    // [ 0/1 ]
private getDP_TYPE_VALUE()      { "02" }    // [ 4 byte value ]
private getDP_TYPE_STRING()     { "03" }    // [ N byte string ]
private getDP_TYPE_ENUM()       { "04" }    // [ 0-255 ]
private getDP_TYPE_BITMAP()     { "05" }    // [ 1,2,4 bytes ] as bits

// Parse incoming device messages to generate events
def parse(String description) {
    checkDriverVersion()
    if (state.rxCounter != null) state.rxCounter = state.rxCounter + 1
    setPresent()
    //if (settings?.logEnable == true) log.debug "${device.displayName} parse() descMap = ${zigbee.parseDescriptionAsMap(description)}"
    if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
            if (descMap.attrInt == 0x0021) {
                getBatteryPercentageResult(Integer.parseInt(descMap.value,16))
            } else if (descMap.attrInt == 0x0020){
                getBatteryResult(Integer.parseInt(descMap.value, 16))
            }
            else {
                logWarn "unparsed power cluster attrint ${descMap.attrInt}"
            }
        }     
        else if (descMap?.clusterInt == CLUSTER_TUYA) {
            processTuyaCluster( descMap )
        } 
        else if (descMap?.clusterId == "0013") {    // device announcement, profileId:0000
            state.rejoinCounter = (state.rejoinCounter ?: 0) + 1
            logDebug "device announcement"
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId == "0001") {
            logDebug "Tuya check-in (0001) app version ${descMap?.value}"
            if ((state.pingTime ?: '0').toInteger() > 0) {
                def now = new Date().getTime()
                def timeRunning = now.toInteger() - (state.pingTime ?: '0').toInteger()
                if (timeRunning > 0 && timeRunning < MAX_PING_MILISECONDS) {
                    sendRttEvent()
                }
                state.pingTime = null
            }
        } 
        else if (descMap?.cluster == "0000" && descMap?.attrId in ["FFDF", "FFE2", "FFE4","FFFE"]) {
            logDebug "Tuya check-in (${descMap?.attrId}) value is ${descMap?.value}"
        } 
        else if (descMap?.cluster == "0500" && descMap?.command == "01") {    //read attribute response
            logDebug "IAS read attribute ${descMap?.attrId} response is ${descMap?.value}"
        } 
        else if (descMap?.clusterId == "0500" && descMap?.command == "04") {    //write attribute response
            logDebug "IAS enroll write attribute response is ${descMap?.data[0] == "00" ? "success" : "failure"}"
        } 
        else {
            logDebug "<b> NOT PARSED </b> : descMap = ${descMap}"
        }
    } // if 'catchall:' or 'read attr -'
    else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {	
        logDebug "Zone status: $description"
        parseIasMessage(description)
    }
    else if (description?.startsWith('enroll request')) {
         /* The Zone Enroll Request command is generated when a device embodying the Zone server cluster wishes to be  enrolled as an active  alarm device. It  must do this immediately it has joined the network  (during commissioning). */
        logDebug "Sending IAS enroll response..."
        ArrayList<String> cmds = zigbee.enrollResponse() + zigbee.readAttribute(0x0500, 0x0000)
        logDebug "enroll response: ${cmds}"
        sendZigbeeCommands( cmds )  
    }    
    else {
        logDebug "<b> UNPROCESSED </b> description = ${description} descMap = ${zigbee.parseDescriptionAsMap(description)}"
    }
}

def parseIasMessage(String description) {
    // https://developer.tuya.com/en/docs/iot-device-dev/tuya-zigbee-water-sensor-access-standard?id=K9ik6zvon7orn 
    try {
        Map zs = zigbee.parseZoneStatusChange(description)
        //if (settings?.logEnable == true) log.trace "zs = $zs"
        processWaterEvent( zs.alarm1Set == true ? 'wet' : 'dry')
    }
    catch (e) {
        log.error "This driver requires HE version 2.2.7 (May 2021) or newer!"
        return null
    }
}

def processWaterEvent( String value, boolean isDigital=false, boolean wasChecked=false ) {
    def valueToBeSent = value
    switch (value) {
        case 'checking' :
            valueToBeSent = 'checking'
            state.isTesting = true
            break
        case 'tested' :
            valueToBeSent = 'tested'
            state.isTesting = true
            runIn( 2, setDryDelayed, [overwrite: true])
            break
        case 'wet' :
            if (settings?.testingDelay == false ) {
                // send 'wet' without delays and additional checks
                valueToBeSent = "wet"
                state.isTesting = false
            }
            else {
                // check whether this is the initial 'wet' event, or a confirmation after few seconds?
                if (wasChecked == true) {
                    // scheduled call, confirmed from "checkIfStillWet()"
                    valueToBeSent = "wet"
                }
                else {
                    // this is the inital 'wet' event - to be verified!
                    valueToBeSent = "checking"
                    state.isTesting = true
                    runIn( settings?.minimumWetTime ?: 1, "checkIfStillWet")
                }
            }
            if (isDigital==false ) {
                state.lastWaterWet = FormattedDateTimeFromUnix( now() )
                logDebug "setting state.lastWaterWet to ${state.lastWaterWet}"
            }
            break
        case 'dry' :
            // 'dry' may come after a test or after a real alarm
            valueToBeSent = "dry"
            descriptionText = "${device.displayName} is ${valueToBeSent}"
            state.isTesting = false
            break
        case 'unknown' :
            // 'unknown' is sent when the water leak sensor healthStatus goes in offline state
            valueToBeSent = "unknown"
            descriptionText = "${device.displayName} status is ${valueToBeSent}"
            state.isTesting = false
            break
        default :
            log.warn "processWaterEvent: unprocessed water event '${value}'"
            return
    }
    sendWaterEvent( valueToBeSent, isDigital )
}

// should be called from processWaterEvent() only!
def sendWaterEvent( String value, boolean isDigital=false) {
    def type = isDigital == true ? "digital" : "physical"
    String descriptionText
    switch (value) {
        case 'checking' :
            descriptionText = "${device.displayName} checking"
            break
        case 'tested' :
            descriptionText = "${device.displayName} is tested"
            break
        case 'wet' :
            if (settings?.testingDelay == false ) {
                // send 'wet' without delays and additional checks
                descriptionText = "<b>${device.displayName} is wet</b>"
            }
            else {
                // check whether this is the initial 'wet' event, or a confirmation after few seconds?
                if (wasChecked == true) {
                    // scheduled call, confirmed from "checkIfStillWet()"
                    descriptionText = "<b>${device.displayName} is wet</b> (checked)"
                }
                else {
                    // this is the inital 'wet' event - to be verified!
                    descriptionText = "${device.displayName} received wet status - checking again in ${settings?.minimumWetTime ?: 1} seconds"
                }
            }
            break
        case 'dry' :
            // 'dry' may come after a test or after a real alarm
            if (state.isTesting  == true) {
                descriptionText = "${device.displayName} is dry (test finished)"
            }
            else {
                descriptionText = "${device.displayName} is dry"
            }
            break
        case 'unknown' :
            // 'unknown' is sent when the water leak sensor healthStatus goes in offline state
            descriptionText = "${device.displayName} status is unknown"
            break
        default :
            log.warn "sendWaterEvent: unprocessed water event '${value}'"
            return
    }
    if (isDigital == true) descriptionText += " (digital)"
    if (settings?.txtEnable==true) log.info "$descriptionText"    // includes deviceName
    sendEvent(name: "water", value: value, descriptionText: descriptionText, type: type , isStateChange: true)    
}

def setDryDelayed() {
    processWaterEvent( 'dry', isDigital=true, wasChecked=true  )
}

def checkIfStillWet() {
    // called when the first 'wet' event is received
    if (device.currentValue('water', true) == 'checking') {
        logDebug "sensor still reprots 'checking' after ${settings?.minimumWetTime ?: 1} seconds - this is a real alarm!"
        state.isTesting = false
        processWaterEvent( 'wet', isDigital=false, wasChecked=true  )
    }
    else {
        // the leak sensor status is back to 'tested' or 'dry', before the check timer expired - it was a test!
        logDebug "it was a test (${device.currentValue('water', true)})"
        state.isTesting = true
        processWaterEvent( 'tested', isDigital=false, wasChecked=true )
    }
    unschedule("checkIfStillWet")
}


def wet() {
    processWaterEvent( "wet", isDigital=true  )
}

def dry() {
    processWaterEvent( "dry", isDigital=true  )
}

def push() {
    processWaterEvent( "tested", isDigital=true )
}

def processTuyaCluster( descMap ) {
    if (descMap?.clusterInt==CLUSTER_TUYA && descMap?.command == "24") {        //getSETTIME
        // Tuya time sync request is sent by NEO Coolcam sensors every 1 hour
        logDebug "${device.displayName} Tuya time synchronization request"
        def offset = 0
        try {
            offset = location.getTimeZone().getOffset(new Date().getTime())
        }
        catch(e) {
            if (settings?.logEnable) log.error "${device.displayName} cannot resolve current location. please set location in Hubitat location setting. Setting timezone offset to zero"
        }
        def cmds = zigbee.command(CLUSTER_TUYA, SETTIME, "0008" +zigbee.convertToHexString((int)(now()/1000),8) +  zigbee.convertToHexString((int)((now()+offset)/1000), 8))
        logDebug "time now is: ${FormattedDateTimeFromUnix( now() )}"
        logDebug "sending time data : ${cmds}"
        cmds.each{ sendHubCommand(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)) }
        if (state.txCounter != null) state.txCounter = state.txCounter + 1
        getBatteryPercentageResult((device.currentState('battery').value as int)* 2, isDigital=true)         // added 04/06/2022 : send latest known battery level event to update the 'Last Activity At' timestamp
    }
    else if (descMap?.clusterInt==CLUSTER_TUYA && descMap?.command == "0B") {    // ZCL Command Default Response
        String clusterCmd = descMap?.data[0]
        def status = descMap?.data[1]            
        logDebug "device has received Tuya cluster ZCL command 0x${clusterCmd} response 0x${status} data = ${descMap?.data}"
        if (status != "00") {
            logWarn "ATTENTION! manufacturer = ${device.getDataValue("manufacturer")} unsupported Tuya cluster ZCL command 0x${clusterCmd} response 0x${status} data = ${descMap?.data} !!!"                
        }
    } 
    else if ((descMap?.clusterInt==CLUSTER_TUYA) && (descMap?.command == "01" || descMap?.command == "02"))
    {
        def transid = zigbee.convertHexToInt(descMap?.data[1])
        def dp = zigbee.convertHexToInt(descMap?.data[2])
        def dp_id = zigbee.convertHexToInt(descMap?.data[3])
        def fncmd = getTuyaAttributeValue(descMap?.data)                 // 
        logDebug "Tuya Cluster command: dp_id=${dp_id} dp=${dp} fncmd=${fncmd}"
        switch (dp) {
            case 0x65 : // dry/wet
                processWaterEvent( fncmd == 0 ? "dry" : "wet" )
                break
            case 0x66 : // battery
                logDebug "Tuya battery status report dp_id=${dp_id} dp=${dp} fncmd=${fncmd}"
                def rawValue = 0
                if (fncmd == 0) rawValue = 100           // Battery Full
                else if (fncmd == 1) rawValue = 75       // Battery High
                else if (fncmd == 2) rawValue = 50       // Battery Medium
                else if (fncmd == 3) rawValue = 25       // Battery Low
                getBatteryPercentageResult(rawValue*2)
                break 
            default :
                logWarn "<b>NOT PROCESSED TUYA COMMAND</b> Tuya cmd: dp=${dp} value=${fncmd} descMap.data = ${descMap?.data}" 
                break
        }
    }
}


private int getTuyaAttributeValue(ArrayList _data) {
    int retValue = 0
    if (_data.size() >= 6) {
        int dataLength = _data[5] as Integer
        int power = 1;
        for (i in dataLength..1) {
            retValue = retValue + power * zigbee.convertHexToInt(_data[i+5])
            power = power * 256
        }
    }
    return retValue
}


// called on initial install of device during discovery
// also called from initialize() in this driver!
def installed() {
    logInfo "${device.displayName} installed()... driver version ${driverVersionAndTimeStamp()}"
}

// called when preferences are saved
// runs when save is clicked in the preferences section
def updated() {
    checkDriverVersion()
    scheduleHealthCheck()           // Save Preferences always restores the schedule
    if (settings?.txtEnable == true) log.info "${device.displayName} Updating ${device.getLabel()} (${device.getName()}) model ${device.getDataValue('model')} manufacturer <b>${device.getDataValue('manufacturer')}</b>"
    if (settings?.txtEnable == true) log.info "${device.displayName} Debug logging is <b>${logEnable}</b>; Description text logging is <b>${txtEnable}</b>"
}


def refresh() {
    logDebug "refresh()..."
    zigbee.readAttribute(0, 1)
}


def powerSourceEvent( state = null) {
    if (state != null && state == 'unknown' ) {
        sendEvent(name : "powerSource",	value : "unknown", descriptionText: "device is OFFLINE", type: "digital")
    }
    else {
        sendEvent(name : "powerSource",	value : "battery", descriptionText: "device is back online", type: "digital")
    }
}

def ping() {
    logInfo 'ping...'
    scheduleCommandTimeoutCheck()
    state.pingTime = new Date().getTime()
    sendZigbeeCommands( zigbee.readAttribute(zigbee.BASIC_CLUSTER, 0x01, [:], 0) )
}

def sendRttEvent() {
    def now = new Date().getTime()
    def timeRunning = now.toInteger() - (state.pingTime ?: '0').toInteger()
    def descriptionText = "Round-trip time is ${timeRunning} (ms)"
    logInfo "${descriptionText}"
    sendEvent(name: "rtt", value: timeRunning, descriptionText: descriptionText, unit: "ms", isDigital: true)    
}

private void scheduleCommandTimeoutCheck(int delay = COMMAND_TIMEOUT) {
    runIn(delay, 'deviceCommandTimeout')
}

private void scheduleDeviceHealthCheck(int intervalMins) {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(9)}/${intervalMins} * ? * * *", 'ping')
}

void deviceCommandTimeout() {
    logWarn 'no response received (sleepy device or offline?)'
}

// called when any event was received from the Zigbee device in parse() method..
def setPresent() {
    powerSourceEvent()
    if ((device.currentValue("healthStatus", true) ?: "") != "online") {
        sendHealthStatusEvent("online")
    	sendEvent(name: "powerSource", value: "battery")
    }
    long nowMs = now()
    state.lastRxTime = nowMs
    state.lastSeen = FormattedDateTimeFromUnix(nowMs)
    state.notPresentCounter = 0
    unschedule('deviceCommandTimeout')
}

Integer offlineHoursSetting() {
    Integer h = safeToInt(settings?.offlineHours, DEFAULT_OFFLINE_HOURS)
    return (h > 0) ? h : DEFAULT_OFFLINE_HOURS
}

// When was the sensor last heard from? state.lastRxTime is set by every incoming message.
// The first time the check runs without it (driver just assigned, or Configure just cleared the
// state), fall back to the hub's own Last Activity time, so a sensor that is already dead is not
// given a fresh 13-hour grace period. If neither is known, start counting from now.
Long lastHeardMillis() {
    if (state.lastRxTime != null) return (state.lastRxTime as Long)
    Long baseline = null
    try {
        Date la = device.getLastActivity()
        if (la != null) baseline = la.getTime()
    }
    catch (e) {
        logDebug "lastActivity not available: ${e}"
    }
    if (baseline == null) baseline = now()
    state.lastRxTime = baseline
    state.lastSeen = FormattedDateTimeFromUnix(baseline)
    return baseline
}

// called from deviceHealthCheck()
def checkIfNotPresent() {
    long silentMs = now() - lastHeardMillis()
    Integer silentHours = (int) (silentMs / MILLIS_PER_HOUR)
    state.notPresentCounter = silentHours
    Integer limit = offlineHoursSetting()
    logDebug "last heard ${state.lastSeen}; silent ${silentHours} h; offline after ${limit} h"
    if (silentMs >= limit * MILLIS_PER_HOUR) {
        if ((device.currentValue("healthStatus", true) ?: "") != "offline") {
            sendHealthStatusEvent("offline")
            logWarn "is not present! Nothing heard since ${state.lastSeen}"
        }
        if (device.currentValue('water', true) != 'unknown') {
            processWaterEvent( 'unknown',  isDigital=true )
        }
    }
}

// Runs every hour from the cron schedule set by scheduleHealthCheck(). It does not reschedule
// itself: the hub repeats a cron schedule on its own, across reboots.
def deviceHealthCheck() {
    logDebug "deviceHealthCheck()..."
    try {
        checkIfNotPresent()
    }
    catch (e) {
        log.error "${device.displayName} deviceHealthCheck failed: ${e}"
    }
}

// Books the hourly health check. Safe to call any number of times: it clears any leftover
// one-shot runIn() job of the original driver and replaces the existing schedule.
// A random minute and second spread many devices across the hour.
def scheduleHealthCheck() {
    lastHeardMillis()               // just after a driver switch, fix the baseline before any event can move Last Activity
    unschedule('deviceHealthCheck')
    Random rnd = new Random()
    schedule("${rnd.nextInt(60)} ${rnd.nextInt(60)} * ? * * *", 'deviceHealthCheck')
    logDebug "hourly health check scheduled"
}

def sendHealthStatusEvent(value) {
    //log.trace "healthStatus ${value}"
    def descriptionText = "healthStatus set to ${value}"
    logInfo "${descriptionText}"
    sendEvent(name: "healthStatus", value: value, descriptionText: descriptionText)
}

def pollPresence() {
    deviceHealthCheck()
}

def configurePollPresence() {
    scheduleHealthCheck()
}

def configureLogsOff() {
    if (settings?.logEnable == true) {
        runIn(86400, logsOff, [overwrite: true, misfire: "ignore"])    // turn off debug logging after 24 hours
        logInfo "Debug logging will be turned off after 24 hours"
    }
    else {
        unschedule(logsOff)
    }
}

Integer safeToInt(val, Integer defaultVal=0) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

Double safeToDouble(val, Double defaultVal=0.0) {
	return "${val}"?.isDouble() ? "${val}".toDouble() : defaultVal
}

def logDebug(msg) {
    if (settings?.logEnable == null || settings?.logEnable == true) {
        log.debug "${device.displayName} " + msg
    }
}

def logInfo(msg) {
    if (settings?.txtEnable == null || settings?.txtEnable == true) {
        log.info "${device.displayName} " + msg
    }
}

def logWarn(msg) {
    if (settings?.logEnable == null || settings?.logEnable == true) {
        log.warn "${device.displayName} " + msg
    }
}

@Field static final String dateFormat = 'yyyy-MM-dd HH:mm:ss.SSS'

def unixFromFormattedDateTime( formattedDateTime ) {
    def unixDateTime = Date.parse(dateFormat, formattedDateTime).time
    return unixDateTime
}

def FormattedDateTimeFromUnix( unixDateTime ) {
    def formattedDateTime = new Date(unixDateTime).format(dateFormat, location.timeZone) 
    return formattedDateTime
}

def driverVersionAndTimeStamp() {version()+' '+timeStamp()}

def checkDriverVersion() {
    if (state.driverVersion == null || driverVersionAndTimeStamp() != state.driverVersion) {
        logInfo "updating the settings from the current driver version ${state.driverVersion} to the new version ${driverVersionAndTimeStamp()}"
        initializeVars( fullInit = false ) 
        configurePollPresence()
        configureLogsOff()
        state.driverVersion = driverVersionAndTimeStamp()
    }
}

def logInitializeRezults() {
    logInfo "manufacturer  = ${device.getDataValue("manufacturer")}"
    logInfo "Initialization finished\r                          version=${version()} (Timestamp: ${timeStamp()})"
}

// called by configure(fullInit) button 
void initializeVars( boolean fullInit = true ) {
    logDebug "InitializeVars()... fullInit = ${fullInit}"
    if (fullInit == true) {
        state.clear()
        state.driverVersion = driverVersionAndTimeStamp()
    }
    if (fullInit == true || state.packetID == null) state.notPresentCounter = 0
    if (fullInit == true || state.rxCounter == null) state.rxCounter = 0
    if (fullInit == true || state.txCounter == null) state.txCounter = 0
    if (fullInit == true || state.rejoinCounter == null) state.rejoinCounter = 0
    if (fullInit == true || state.isTesting == null) state.isTesting = false
    if (state.lastBattery == null) state.lastBattery = "0"
    if (state.lastWaterWet == null) state.lastWaterWet = "unknown"    //FormattedDateTimeFromUnix( now() )
    if (fullInit == true || state.notPresentCounter == null) state.notPresentCounter = 0
    if (device.currentValue('powerSource', true) == null) sendEvent(name : "powerSource", descriptionText: "device just installed",	value : "?", isStateChange : true)
    if (device.currentValue('water', true) == null) sendEvent(name : "water",	value : "unknown", descriptionText: "device just installed", isStateChange : true)
    if (device.currentValue('healthStatus', true) == null) sendEvent(name: "healthStatus", value: "unknown", descriptionText: "device just installed", type:  'digital' , isStateChange: true )
    
    if (fullInit == true || settings?.logEnable == null) device.updateSetting("logEnable", [value:debugLogsDefault, type:"bool"])
    if (fullInit == true || settings?.txtEnable == null) device.updateSetting("txtEnable", [value: true, type:"bool"])
    if (fullInit == true || settings?.testingDelay == null) device.updateSetting("testingDelay", [value: false, type:"bool"])
    if (fullInit == true || settings?.minimumWetTime == null) device.updateSetting("minimumWetTime", [value:3, type:"number"])
    
}

def tuyaBlackMagic() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], delay=200)
    cmds += zigbee.writeAttribute(0x0000, 0xffde, 0x20, 0x13, [:], delay=200)
    return  cmds
}

// called when used with capability "Configuration" is called when the configure button is pressed on the device page. 
// Runs when driver is installed, after installed() is run. if capability Configuration exists, a Configure command is added to the ui
// It is also called on initial install after discovery.
def configure() {
    List<String> cmds = []
    logInfo "configure().."
    Long lastHeard = lastHeardMillis()      // read before the reset; Configure must not restart the offline clock
    unschedule()
    initializeVars(fullInit = true)
    state.lastRxTime = lastHeard
    state.lastSeen = FormattedDateTimeFromUnix(lastHeard)
    configurePollPresence()
    configureLogsOff()
    cmds += tuyaBlackMagic()    
    cmds += zigbee.enrollResponse() + zigbee.readAttribute(0x0500, 0x0000)    
    sendZigbeeCommands(cmds)    
}

// Capability "Initialize": the hub calls this at every start-up, and it appears as an Initialize
// button. In the original it ran configure(), which wipes the device state; it is now only a
// safety net that restores the schedules. Use Configure for a full reset.
def initialize() {
    logInfo "initialize()... restoring health check schedule"
    checkDriverVersion()
    scheduleHealthCheck()
    configureLogsOff()
}

private sendTuyaCommand(dp, dp_type, fncmd) {
    ArrayList<String> cmds = []
    cmds += zigbee.command(CLUSTER_TUYA, SETDATA, PACKET_ID + dp + dp_type + zigbee.convertToHexString((int)(fncmd.length()/2), 4) + fncmd )
    logDebug "sendTuyaCommand = ${cmds}"
    state.txCounter = (state.txCounter ?: 0) + 1
    return cmds
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    logDebug "sendZigbeeCommands (cmd=${cmd})"
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
        allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
        state.txCounter = (state.txCounter ?: 0) + 1
    }
    sendHubCommand(allActions)
}

private getPACKET_ID() {
    state.packetID = ((state.packetID ?: 0) + 1 ) % 65536
    return zigbee.convertToHexString(state.packetID, 4)
}

private getDescriptionText(msg) {
	def descriptionText = "${device.displayName} ${msg}"
	logInfo "${descriptionText}"
	return descriptionText
}

def logsOff(){
    log.info "${device.displayName} debug logging disabled..."
    device.updateSetting("logEnable",[value: false, type:"bool"])
}

def getBatteryPercentageResult(rawValue, isDigital=false) {
    logDebug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.isStateChange = true
        result.unit  = '%'
        result.type = isDigital == true ? "digital" : "physical"
        result.descriptionText = "${device.displayName} battery is ${result.value}% ($result.type)"
        state.lastBattery = (result.value).toString()
        sendEvent(result)
        logInfo "${result.descriptionText}, water:${device.currentState('water').value}"
    }
    else {
        logWarn "${device.displayName} ignoring BatteryPercentageResult(${rawValue})"
    }
}

private Map getBatteryResult(rawValue) {
    logDebug "${device.displayName} batteryVoltage = ${(double)rawValue / 10.0} V"
    def result = [:]
    def volts = rawValue / 10
    if (!(rawValue == 0 || rawValue == 255)) {
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0)
        roundedPct = 1
        result.value = Math.min(100, roundedPct)
        result.descriptionText = "${device.displayName} battery is ${result.value}% (${volts} V)"
        result.name = 'battery'
        result.unit = '%'
        result.type = "physical"
        result.isStateChange = true
        logInfo "${result.descriptionText}, water:${device.currentState('water').value}"
        state.lastBattery = roundedPct.toString()
        sendEvent(result)
    }
    else {
        logWarn "${device.displayName} ignoring BatteryResult(${rawValue})"
    }    
}

def sendBatteryEvent( roundedPct, isDigital=false ) {
    sendEvent(name: 'battery', value: roundedPct, unit: "%", type:  isDigital == true ? "digital" : "physical", isStateChange: true )    
}

def test () {
    List<String> cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
        cmds += zigbee.readAttribute(0x0001, 0x0020, [:], delay=200)         // battery voltage
        cmds += zigbee.readAttribute(0x0001, 0x0021, [:], delay=200)         // battery percentage 
    
    if (cmds != null && cmds != [] ) {
        sendZigbeeCommands(cmds)
    }    
}
