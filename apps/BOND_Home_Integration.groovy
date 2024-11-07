/**
 *  https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/refs/heads/master/apps/BOND_Home_Integration.groovy
 *
 *  BOND Home Integration
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Additional copyright 2024 Gatewood Green
 *  Additional copyright 2024 @terminal3
 *
 * Revision History
 * 2020.01.18 - Added setPosition support for motorized shades, mapping a special value of 50 to the Preset command
 * 2019.12.01 - Fixed an issue where dimmers wouldn't work with fans that support direction controls, fixed an issue setting flame height
 * 2019.11.24 - Added support for timer based fan light dimmers and flame height adjustment for fireplaces
 * 2019.12.14 - Added support for Switch capability to the motorized shades for compatibility
 * 2020.01.02 - Fixed an issue where fan speed wouldn't be set properly (thanks jchurch for the troubleshooting!)
 * 2020.02.01 - Fixed an issue where looking for devices was incorrect which broke Smart By BOND devices (thanks mcneillk for the fix!)
 * 2020.03.23 - Added the ability to fix device state when it's out of sync (thanks stephen_nutt for the suggestion)
 * 2020.04.13 - Added a stop command to motorized shades to stop an open/close at the current position (suggested by jchurch)
 * 2020.04.21 - Added better logging for connection issues to the hub
 * 2020.05.04 - Error logging improvements
 * 2020.06.28 - Added toggle command to all devices (suggested by jchurch) and support for having multiple Smart by BOND devices (discovered by jhciotti)
 * 2024.10.20 - Fixed component light state updates to Hubitat device
 * 2024.10.21 - Device power/switch local state changes now report as type: "physical", light local state brightness changes as unit: "%"
 * 2024.10.23 - Fixed potential fan speed mapping issues, replaced the binary Debug log option switch in the app to support levels of logging
 * 2024.10.24 - Improved API error reporting, incorporate @terminal3's Additional Motorized Shaed commands (openNext, closeNext)
 * 2024.11.04 - More debug logging, add function for child devices to get parent app settings (for matching logLevel)
 * 2024.11.05 - Update sendEvent() calls for type of event when changing or detecting device state, logging cleanup
 *
 */

import groovy.transform.Field
@Field static final String VERSION   = "202411071150"
@Field static final String NAME      = "Bond Home Integration"
@Field static final String COMM_LINK = "https://github.com/sonoranwanderer/hubitat-bond"

definition(
    name: "BOND Home Integration",
    namespace: "dcm.bond",
    author: "Gatewood Green",
    description: "Connects to BOND Home hub",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    documentationLink: "https://github.com/sonoranwanderer/hubitat-bond/blob/master/README.md"
)

preferences {
    page(name: "prefHub", title: "BOND")
    page(name: "prefListDevices", title: "BOND")
    page(name: "prefPowerSensors", title: "BOND")
}

def getVersion() {
    return VERSION
}

def getName() {
    return NAME
}

def prefHub() {
    return dynamicPage(name: "prefHub", title: "Connect to BOND", nextPage:"prefListDevices", uninstall:false, install: false) {
        section("Hub Information"){
            input("hubIp", "text", title: "BOND Hub IP", description: "BOND Hub IP Address", required: true)
            input("hubToken", "text", title: "BOND Hub Token", description: "BOND Hub Token", required: true)
            input("refreshInterval", "number", title: "Poll BOND Home every N seconds", required: true, defaultValue: 30)
            input( "logLevel", "enum", title: "Log Level", options: [ "info", "debug", "trace" ],  defaultValue: "info", required: true )
        }
        displayFooter()
    }
}

def prefListDevices() {
    if (!getDevices())
    {
        return dynamicPage(name: "prefListDevices", title: "Connection Error", install: false, uninstall: false) {
            section("Error") {
                paragraph "Unable to retrieve devices. Please verify your BOND Hub ID and Token"
            }
            displayFooter()
        }
    }
    else
    {
        return dynamicPage(name: "prefListDevices", title: "Devices", nextPage: "prefPowerSensors", install: false, uninstall: false) {
            section("Devices") {
                if (state.fireplaceList.size() > 0)
                    input(name: "fireplaces", type: "enum", title: "Fireplaces", required:false, multiple:true, options:state.fireplaceList, hideWhenEmpty: true)
                if (state.fanList.size() > 0)
                    input(name: "fans", type: "enum", title: "Fans", required:false, multiple:true, options:state.fanList, hideWhenEmpty: true)
                if (state.shadeList.size() > 0)
                    input(name: "shades", type: "enum", title: "Shades", required:false, multiple:true, options:state.shadeList, hideWhenEmpty: true)
                if (state.genericList.size() > 0)
                    input(name: "genericDevices", type: "enum", title: "Generic Devices", required:false, multiple:true, options:state.genericList, hideWhenEmpty: true)
            }
            displayFooter()
        }
    }
}

def prefPowerSensors() {
    return dynamicPage(name: "prefPowerSensors", title: "Fireplace Power Meters", install: true, uninstall: true, hideWhenEmpty: true) {
        section("Fireplace Power Meters") {
            paragraph "For each fireplace device you can associate a power meter to more accurately tell when it is powered on"
            if (fireplaces != null) {
                for (def i = 0; i < fireplaces.size(); i++) {
                    input(name: "fireplaceSensor${i}", type: "capability.powerMeter", title: "Sensor for ${state.fireplaceList[fireplaces[i]]}", required: false, submitOnChange: true)
                }
                for (def i = 0; i < fireplaces.size(); i++) {
                    if (this.getProperty("fireplaceSensor${i}") != null)
                    input(name: "fireplaceSensorThreshold${i}", type: "number", title: "Sensor threshold for ${state.fireplaceList[fireplaces[i]]}", required: false)
                }
            }
        }
        displayFooter()
    }
}

def installed() {
    logAppEvent( "Installed with settings: ${settings}", "debug" )

    initialize()
}

def updated() {
    logAppEvent( "Updated with settings: ${settings}", "debug" )
    unschedule()
    unsubscribe()
    initialize()
}

def uninstalled() {
    logAppEvent( "Uninstalled app", "debug" )

    for (device in getChildDevices())
    {
        deleteChildDevice(device.deviceNetworkId)
    }    
}

def initialize() {
    logAppEvent( "initializing", "debug" )

    cleanupChildDevices()
    createChildDevices()
    subscribeSensorEvents()    
    
    def refreshEvery = refreshInterval ?: 30
    schedule("0/${refreshEvery} * * * * ? *", updateDevices)
}

def childGetSettings( setting ) {
    def data = settings?."${setting}"
    return data
}

void logAppEvent ( message="", level="info" ) {
    if ( level == "trace" ) {
        if ( settings?.logLevel != "trace" )
            return
    } else if ( level == "debug" ) {
        if ( settings?.logLevel != "trace" && settings?.logLevel != "debug" )
            return
    }
    log."${level}" "${app.name}: ${message}"
}

def getHubId() {
    if (state.hubId)
        return state.hubId
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/sys/version",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    try
    {
        httpGet(params) { resp ->
            if (checkHttpResponse("getHubId", resp))
            {
                state.hubId = resp.data.bondid
            }
        }
        return state.hubId
    }
    catch (e)
    {
        checkHttpResponse("getHubId", e.getResponse())
        return null
    }
}

def getDevices() {
    state.fireplaceList = [:]
    state.fireplaceDetails = [:]
    state.fireplaceProperties = [:]
    state.fanList = [:]
    state.fanDetails = [:]
    state.fanProperties = [:]
    state.shadeList = [:]
    state.shadeDetails = [:]
    state.shadeProperties = [:]
    state.genericList = [:]
    state.genericDetails = [:]
    state.deviceList = [:]
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    try
    {
        def result = false
        httpGet(params) { resp ->
            if (checkHttpResponse("getDevices", resp))
            {
                for (deviceid in resp.data) {
                    if (deviceid.key == "_")
                        continue
                    getDeviceById(deviceid);
                }
                result = true
            }
        }
        return result
    }
    catch (e)
    {
        checkHttpResponse("getDevices", e.getResponse())
        return false
    }
}

def getDeviceById(id) {
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${id.key}",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    try
    {
        httpGet(params) { resp ->
            if (checkHttpResponse("getDeviceById", resp))
            {
                if (resp.data.type == "FP")
                {
                    state.fireplaceList[id.key] = resp.data.name
                    state.fireplaceDetails[id.key] = resp.data.actions
                    state.fireplaceProperties[id.key] = getDeviceProperties(id)
                }
                else if (resp.data.type == "CF")
                {
                    state.fanList[id.key] = resp.data.name
                    state.fanDetails[id.key] = resp.data.actions
                    state.fanProperties[id.key] = getDeviceProperties(id)
                }
                else if (resp.data.type == "MS")
                {
                    state.shadeList[id.key] = resp.data.name
                    state.shadeDetails[id.key] = resp.data.actions
                    state.shadeProperties[id.key] = getDeviceProperties(id)
                }
                else if (resp.data.type == "GX")
                {
                    state.genericList[id.key] = resp.data.name
                    state.genericDetails[id.key] = resp.data.actions
                }
            }
        }
    }
    catch (e)
    {
        checkHttpResponse("getDeviceById", e.getResponse())
    }
}

def getDeviceProperties(id) {
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${id.key}/properties",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    def result = null
    try
    {
        httpGet(params) { resp ->
            if (checkHttpResponse("getDeviceProperties", resp))
            {
                result = resp.data
            }
        }
    }
    catch (e)
    {
        checkHttpResponse("getDeviceProperties", e.getResponse())
    }
    return result
}

def findChildDevice(deviceId) {
    def hubId = getHubId()
    def dev = getChildDevice("bond:" + deviceId)
    if (dev != null)
        return dev

    return getChildDevice(hubId + ":bond:" + deviceId)
}

def findComponentDevice(dev, deviceId) {
    def hubId = getHubId()
    def component = dev.getChildDevice("bond:" + deviceId)
    if (component != null)
        return component

    return dev.getChildDevice(hubId + ":bond:" + deviceId) /* Woody Fix */
    /* return component?.getChildDevice(hubId + ":bond:" + deviceId) ?: null */
}

def getBondIdFromDevice(device) {
    if (device?.deviceNetworkId.startsWith("bond:"))
        return device?.deviceNetworkId.split(":")[1]
    else
        return device?.deviceNetworkId.split(":")[2]
}

def deleteComponentDevice(dev, deviceId) {
    def hubId = getHubId()
    if (dev.getChildDevice("bond:" + deviceId + ":fan"))
        dev.deleteChildDevice("bond:" + deviceId + ":fan")
    if (dev.getChildDevice(hubId + ":bond:" + deviceId + ":fan"))
        dev.deleteChildDevice(hubId + ":bond:" + deviceId + ":fan")
}

def createChildDevices() {
    def hubId = getHubId()
    if (fireplaces != null) 
    {
        for (fireplace in fireplaces)
        {
            def fpDevice = findChildDevice(fireplace)
            if (!fpDevice)
            {
                fpDevice = addChildDevice("bond", "BOND Fireplace", hubId + ":bond:" + fireplace, 1234, ["name": state.fireplaceList[fireplace], isComponent: false])\
            }
            if (state.fireplaceDetails[fireplace].contains("TurnFpFanOn"))
            {
                if (!findComponentDevice(fpDevice, fireplace + ":fan"))
                    fpDevice.addChildDevice("bond", "BOND Fireplace Fan", hubId + ":bond:" + fireplace + ":fan", ["name": state.fireplaceList[fireplace] + " Fan", isComponent: true])
            }
            if (state.fireplaceDetails[fireplace].contains("TurnLightOn"))
            {
                if (!findComponentDevice(fpDevice, fireplace + ":light"))
                    fpDevice.addChildDevice("bond", "BOND Fireplace Light", hubId + ":bond:" + fireplace + ":light", ["name": state.fireplaceList[fireplace] + " Light", isComponent: true])
            }
        }
    }
    
    if (fans != null) 
    {
        for (fan in fans)
        {
            def fanDevice = findChildDevice(fan)
            if (!fanDevice)
            {
                if (state.fanDetails[fan].contains("SetDirection"))
                    fanDevice = addChildDevice("bond", "BOND Fan With Direction", hubId + ":bond:" + fan, 1234, ["name": state.fanList[fan], isComponent: false])
                else
                    fanDevice = addChildDevice("bond", "BOND Fan", hubId + ":bond:" + fan, 1234, ["name": state.fanList[fan], isComponent: false])
            }
            if (state.fanDetails[fan].contains("TurnUpLightOn") && state.fanDetails[fan].contains("TurnDownLightOn"))
            {
                if (state.fanDetails[fan].contains("SetUpLightBrightness") && state.fanDetails[fan].contains("SetDownLightBrightness"))
                {
                    if (!findComponentDevice(fanDevice, fan + ":uplight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Dimmable Light", hubId + ":bond:" + fan + ":uplight", ["name": state.fanList[fan] + " Up Light", isComponent: true])
                    if (!findComponentDevice(fanDevice, fan + ":downlight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Dimmable Light", hubId + ":bond:" + fan + ":downlight", ["name": state.fanList[fan] + " Down Light", isComponent: true])

                }
                else if (state.fanDetails[fan].contains("StartUpLightDimmer") && state.fanDetails[fan].contains("StartDownLightDimmer"))
                {
                    if (!findComponentDevice(fanDevice, fan + ":uplight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Timer Light", hubId + ":bond:" + fan + ":uplight", ["name": state.fanList[fan] + " Up Light", isComponent: true])
                    if (!findComponentDevice(fanDevice, fan + ":downlight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Timer Light", hubId + ":bond:" + fan + ":downlight", ["name": state.fanList[fan] + " Down Light", isComponent: true])
                }
                else
                {
                    if (!findComponentDevice(fanDevice, fan + ":uplight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Light", hubId + ":bond:" + fan + ":uplight", ["name": state.fanList[fan] + " Up Light", isComponent: true])
                    if (!findComponentDevice(fanDevice, fan + ":downlight"))
                        fanDevice.addChildDevice("bond", "BOND Fan Light", hubId + ":bond:" + fan + ":downlight", ["name": state.fanList[fan] + " Down Light", isComponent: true])
                }
            }
            else if (state.fanDetails[fan].contains("TurnLightOn"))
            {
                if (!findComponentDevice(fanDevice, fan + ":light"))
                {
                    if (state.fanDetails[fan].contains("SetBrightness"))
                    {
                        fanDevice.addChildDevice("bond", "BOND Fan Dimmable Light", hubId + ":bond:" + fan + ":light", ["name": state.fanList[fan] + " Light", isComponent: true])
                    }
                    else if (state.fanDetails[fan].contains("StartDimmer"))
                    {
                        fanDevice.addChildDevice("bond", "BOND Fan Timer Light", hubId + ":bond:" + fan + ":light", ["name": state.fanList[fan] + " Light", isComponent: true])
                    }
                    else
                        fanDevice.addChildDevice("bond", "BOND Fan Light", hubId + ":bond:" + fan + ":light", ["name": state.fanList[fan] + " Light", isComponent: true])
                }
            }
        }
    }
    
    if (shades != null)
    {
        for (shade in shades)
        {
            def shadeDevice = findChildDevice(shade)
            if (!shadeDevice)
            {
                shadeDevice = addChildDevice("bond", "BOND Motorized Shade", hubId + ":bond:" + shade, 1234, ["name": state.shadeList[shade], isComponent: false])
            }
        }
    }
    
    if (genericDevices != null)
    {
        for (generic in genericDevices)
        {
            def genericDevice = findChildDevice(generic)
            if (!genericDevice)
            {
                genericDevice = addChildDevice("bond", "BOND Generic Device", hubId + ":bond:" + generic, 1234, ["name": state.genericList[generic], isComponent: false])
            }
        }
    }
}

def cleanupChildDevices()
{
    for (device in getChildDevices())
    {
        def deviceId = device.deviceNetworkId.replace("bond:","")
        if (deviceId.contains(":"))
            deviceId = deviceId.split(":")[1]
        
        def deviceFound = false
        for (fireplace in fireplaces)
        {
            if (fireplace == deviceId)
            {
                deviceFound = true
                cleanupFPComponents(device, fireplace)
                break
            }
        }
        
        if (deviceFound == true)
            continue
        
        for (fan in fans)
        {
            if (fan == deviceId)
            {
                deviceFound = true
                cleanupFanComponents(device, fan)
                break
            }
        }
        if (deviceFound == true)
            continue
            
        for (shade in shades)
        {
            if (shade == deviceId)
            {
                deviceFound = true
                break
            }
        }
        if (deviceFound == true)
            continue
            
        for (generic in genericDevices)
        {
            if (generic == deviceId)
            {
                deviceFound = true
                break
            }
        }
        if (deviceFound == true)
            continue
        
        deleteChildDevice(device.deviceNetworkId)
    }
}

def cleanupFPComponents(device, fireplace)
{
    if (!state.fireplaceDetails[fireplace].contains("TurnFpFanOn"))
    {
        deleteComponentDevice(device, fireplace + ":fan")
    }
    if (!state.fireplaceDetails[fireplace].contains("TurnLightOn"))
    {
        deleteComponentDevice(device, fireplace + ":light")
    }
}

def cleanupFanComponents(device, fan)
{
    if (!state.fanDetails[fan].contains("TurnUpLightOn") || !state.fanDetails[fan].contains("TurnDownLightOn"))
    {
        deleteComponentDevice(device, fan + ":uplight")
        deleteComponentDevice(device, fan + ":downlight")
    }
    if (!state.fanDetails[fan].contains("TurnLightOn") || (state.fanDetails[fan].contains("TurnUpLightOn") && state.fanDetails[fan].contains("TurnDownLightOn")))
    {
        deleteComponentDevice(device, fan + ":light")
    }
}

def subscribeSensorEvents() {
    if (fireplaces != null)
    {
        for (def i = 0; i < fireplaces.size(); i++)
        {
            def sensorDevice = this.getProperty("fireplaceSensor${i}")
            if (sensorDevice != null)
            {
                logAppEvent( "subscribing to power event for ${sensorDevice}", "debug" )
                subscribe(sensorDevice, "power", powerMeterEventHandler)
            }
        }
    }
}
                  
def powerMeterEventHandler(evt) {
    logAppEvent( "Received power meter event ${evt}", "debug" )
    for (def i = 0; i < fireplaces.size(); i++)
    {
        def sensorDevice = this.getProperty("fireplaceSensor${i}")
        if (evt.device.id == sensorDevice.id)
        {
            def fireplace = fireplaces[i];
            def fireplaceDevice = getChildDevice("bond:" + fireplace)
            def threshold = 10
            def value = "on"
            if (evt.integerValue < threshold)
                value = "off"
            if (value != fireplaceDevice.currentValue("switch"))
            {
                logAppEvent( "current state ${fireplaceDevice.currentValue("switch")} changing to ${value}", "debug" )
                fireplaceDevice.sendEvent(name: "switch", value: value)
            }
            if (value == "off")
            {
                def fanDevice = fireplaceDevice.getChildDevice("bond:" + fireplace + ":fan")
                if (fanDevice)
                    fanDevice.sendEvent(name: "speed", value: "off")
            }
            break;
        }
    }
}

def updateDevices() {
    for (fan in fans) {
        def deviceState = getState(fan)
        if (deviceState == null)
            continue
        def device = findChildDevice(fan)
        
        def deviceLight = findComponentDevice(device, fan + ":light")
        def deviceUpLight = findComponentDevice(device, fan + ":uplight")
        def deviceDownLight = findComponentDevice(device, fan + ":downlight")
        if (deviceState.power > 0)
        {
            device.sendEvent( name: "switch", value: "on", type: "physical", descriptionText: "Bond fan state update 'on'" )
            def curSpeedS = translateBondFanSpeedToHE(fan, state.fanProperties[fan].max_speed ?: 3, deviceState.speed)
            device.sendEvent( name: "speed",  value: curSpeedS, type: "physical", descriptionText: "Bond fan speed state update '${curSpeedS}'" )
        }
        else
        {
            device.sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan state update 'off'" )
            device.sendEvent( name: "speed",  value: "off", type: "physical", descriptionText: "Bond fan speed state update 'off'"  )
        }
        if ( deviceLight ) {
            if ( deviceState.brightness != null )
                deviceLight.sendEvent( name: "level", value: deviceState.brightness, unit: "%", descriptionText: "Bond fan light brightness state update to '${deviceState.brightness}%'" )

            if ( deviceState.light == 0 )
                deviceLight.sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan light state update 'off'" )
            else
                deviceLight.sendEvent( name: "switch", value: "on",  type: "physical", descriptionText: "Bond fan light state update 'on'" )
        }
        if ( deviceUpLight ) {
            if ( deviceState.up_light_brightness != null )
                deviceUpLight.sendEvent( name: "level", value: deviceState.up_light_brightness, unit: "%", descriptionText: "Bond fan up light brightness state update to '${deviceState.up_light_brightness}%'" )

            if ( deviceState.up_light == 0 )
                deviceUpLight.sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan up light state update 'off'" )
            else
                deviceUpLight.sendEvent( name: "switch", value: "on",  type: "physical", descriptionText: "Bond fan up light state update 'on'" )
        }
        if ( deviceDownLight ) {
            if ( deviceState.down_light_brightness != null )
                deviceDownLight.sendEvent( name: "level", value: deviceState.down_light_brightness, unit: "%", descriptionText: "Bond fan down light brightness state update to '${deviceState.down_light_brightness}%'" )

            if ( deviceState.down_light == 0 )
                deviceDownLight.sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan down light state update 'off'" )
            else
                deviceDownLight.sendEvent( name: "switch", value: "on",  type: "physical", descriptionText: "Bond fan down light state update 'on'" )
        }
        if (device.hasAttribute("direction"))
        {
            if (deviceState.direction == 1)
                device.sendEvent(name: "direction", value: "forward", type: "physical", descriptionText: "Bond fan direction state update 'forward'" )
            else if (deviceState.direction == -1)
                device.sendEvent(name: "direction", value: "reverse", type: "physical", descriptionText: "Bond fan direction state update 'reverse'" )
        }
    }
    
    if (fireplaces != null)
    {
        for (def i = 0; i < fireplaces.size(); i++)
        {
            def deviceState = getState(fireplaces[i])
            if (deviceState == null)
                continue
            def device = findChildDevice(fireplaces[i])
            
            def deviceFan = findComponentDevice(device, fireplaces[i] + ":fan")
            def deviceLight = findComponentDevice(device, fireplaces[i] + ":light")
            
            if (deviceState.flame > 0 && deviceState.power > 0)
            {
                if (deviceState.flame <= 25)
                    device.sendEvent(name: "flame", value: "low", type: "physical")
                else if (deviceState.flame <= 50)
                    device.sendEvent(name: "flame", value: "medium", type: "physical")
                else
                    device.sendEvent(name: "flame", value: "high", type: "physical")
            }
            else
            {
                device.sendEvent(name: "flame", value: "off")
            }
            
            if (deviceState.power > 0)
            {
                if (this.getProperty("fireplaceSensor${i}") == null)
                {
                    device.sendEvent(name: "switch", value: "on", type: "physical")
                }
                if (deviceFan)
                {
                    deviceFan.sendEvent(name: "speed", value: translateBondFanSpeedToHE(fireplaces[i], state.fireplaceProperties?.getAt(fireplaces[i])?.max_speed ?: 3, deviceState.fpfan_speed))
                }
                
                if (deviceLight)
                {
                    if (deviceState.light == 1)
                        deviceLight.sendEvent(name: "switch", value: "on", type: "physical")
                    else
                        deviceLight.sendEvent(name: "switch", value: "off", type: "physical")
                }
            }
            else 
            {
                if (this.getProperty("fireplaceSensor${i}") == null)
                {
                    device.sendEvent(name: "switch", value: "off", type: "physical")
                }
                if (deviceFan)
                {
                    deviceFan.sendEvent(name: "speed", value: "off", type: "physical")
                }
                if (deviceLight)
                {
                    deviceLight.sendEvent(name: "switch", value: "off", type: "physical")
                }
            }
            
        }
    }
    
    if (shades != null)
    {
        for (shade in shades)
        {
            def deviceState = getState(shade)
            if (deviceState == null)
                continue
            def device = findChildDevice(shade)
            
            if (deviceState.open == 1)
            {
                device.sendEvent(name: "switch", value: "on", type: "physical")
                device.sendEvent(name: "windowShade", value: "open", type: "physical")
            }
            else
            {
                device.sendEvent(name: "switch", value: "off", type: "physical")
                device.sendEvent(name: "windowShade", value: "closed", type: "physical")
            }
        }
    }
    
    if (genericDevices != null)
    {
        for (generic in genericDevices)
        {
            def deviceState = getState(generic)
            if (deviceState == null)
                continue
            def device = findChildDevice(generic)
            
            if (deviceState.power > 0)
            {
                device.sendEvent(name: "switch", value: "on", type: "physical")
            }
            else
            {
                device.sendEvent(name: "switch", value: "off", type: "physical")
            }
        }
    }
}

def handleOn(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling On event for ${bondId}", "debug" )

    if (executeAction(bondId, "TurnOn") && shouldSendEvent(bondId))
    {
        device.sendEvent(name: "switch", value: "on", type: "digital")
    }
    
}

def handleLightOn(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Light On event for ${bondId}", "debug" )
    if (device.deviceNetworkId.contains("uplight"))
    {
        if (executeAction(bondId, "TurnUpLightOn")) 
        {
            device.sendEvent(name: "switch", value: "on", type: "digital")
        }
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        if (executeAction(bondId, "TurnDownLightOn")) 
        {
            device.sendEvent(name: "switch", value: "on", type: "digital")
        }
    }
    else
    {
        if (executeAction(bondId, "TurnLightOn")) 
        {
            device.sendEvent(name: "switch", value: "on", type: "digital")
        }
    }
}

def handleLightOff(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Light Off event for ${bondId}", "debug" )
    if (device.deviceNetworkId.contains("uplight"))
    {
        if (executeAction(bondId, "TurnUpLightOff")) 
        {
            device.sendEvent(name: "switch", value: "off", type: "digital")
        }
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        if (executeAction(bondId, "TurnDownLightOff")) 
        {
            device.sendEvent(name: "switch", value: "off", type: "digital")
        }
    }    
    else
    {
        if (executeAction(bondId, "TurnLightOff")) 
        {
            device.sendEvent(name: "switch", value: "off", type: "digital")
        }
    }
}

def handleDim(device, duration)
{
    def bondId = getBondIdFromDevice(device)
    if (device.deviceNetworkId.contains("uplight"))
    {
        dimUsingTimer(device, bondId, duration, "StartUpLightDimmer")
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        dimUsingTimer(device, bondId, duration, "StartDownLightDimmer")
    }
    else
    {
        dimUsingTimer(device, bondId, duration, "StartDimmer")
    }
}

def dimUsingTimer(device, duration, command)
{
    def bondId = getBondIdFromDevice(device)
    if (executeAction(bondId, command))
    {
        runInMillis((duration*1000).toInteger(), stopDimmer, [data: [device: device, bondId: bondId]])
    }
}

def stopDimmer(data)
{
    executeAction(data.bondId, "Stop")
}

def handleStartDimming(device)
{
    def bondId = getBondIdFromDevice(device)
    if (device.deviceNetworkId.contains("uplight"))
    {
        executeAction(bondId, "StartUpLightDimmer")
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        executeAction(bondId, "StartDownLightDimmer")
    }
    else
    {
        executeAction(bondId, "StartDimmer")
    }
}

def handleStopDimming(device)
{
    def bondId = getBondIdFromDevice(device)
    executeAction(bondId, "Stop")
}

def handleLightLevel(device, level) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Light Level event for ${bondId}", "debug" )
    if (device.deviceNetworkId.contains("uplight"))
    {
        if (executeAction(bondId, "SetUpLightBrightness", level)) 
        {
            device.sendEvent(name: "level", value: level, unit: "%", type: "digital")
        }
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        if (executeAction(bondId, "SetDownLightBrightness", level)) 
        {
            device.sendEvent(name: "level", value: level, unit: "%", type: "digital")
        }
    }
    else 
    {
        if (executeAction(bondId, "SetBrightness", level)) 
        {
            device.sendEvent(name: "level", value: level, unit: "%", type: "digital")
        }
    }
}

def handleSetFlame(device, height)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Flame event for ${bondId}", "debug" )
    
    if (height == "off")
    {
        if (handleOff(device))
            device.sendEvent(name: "flame", value: "off", type: "digital")
    }
    else 
    {
        def flameHeight = 0
        if (height == "low")
            flameHeight = 1
        else if (height == "medium")
            flameHeight = 50
        else if (height == "high")
            flameHeight = 100
            
        if (executeAction(bondId, "SetFlame", flameHeight))
        {
            device.sendEvent(name: "flame", value: height, type: "digital")
        }
    }
}

def handleOpen(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Open event for ${bondId}", "debug" )
    
    if (executeAction(bondId, "Open")) 
    {
        device.sendEvent(name: "windowShade", value: "open", type: "digital")
    }
}

def handleOpenNext(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling OpenNext event for ${bondId}", "debug" )

    if (executeAction(bondId, "OpenNext"))
    {
        device.sendEvent(name: "windowShade", value: "open", type: "digital")
    }
}

def handleClose(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Close event for ${bondId}", "debug" )

    if (executeAction(bondId, "Close"))
    {
        device.sendEvent(name: "windowShade", value: "closed", type: "digital")
    }
}

def handleCloseNext(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling CloseNext event for ${bondId}", "debug" )

    if (executeAction(bondId, "CloseNext"))
    {
        device.sendEvent(name: "windowShade", value: "closed", type: "digital")
    }
}

def handleStop(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Stop event for ${bondId}", "debug" )
    
    executeAction(bondId, "Hold")
}

def handlePreset(device)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Preset event for ${bondId}", "debug" )
    
    executeAction(bondId, "Preset")
}

def fixPowerState(device, state) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting power state for ${bondId} to ${state}", "debug" )
    
    def power
    if (state == "on")
        power = 1
    else 
        power = 0

    if (executeFixState(bondId, '{"power": ' + power + '}'))
    {
        if (power == 1)
            device.sendEvent(name: "switch", value: "on", type: "digital", descriptionText: "Manual driver state update")
        else
        {
            device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            if (device.hasAttribute("speed"))
                device.sendEvent(name: "speed", value: "off", type: "digital", descriptionText: "Manual driver state update")
            if (device.hasAttribute("flame"))
                device.sendEvent(name: "flame", value: "off", type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixFlameState(device, state) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting flame state for ${bondId} to ${state}", "debug" )
    
    def flameHeight = 0
    if (height == "low")
        flameHeight = 1
    else if (height == "medium")
        flameHeight = 50
    else if (height == "high")
        flameHeight = 100

    if (executeFixState(bondId, '{"flame": ' + flameHeight + '}'))
    {
        device.sendEvent(name: "flame", value: height, type: "digital", descriptionText: "Manual driver state update")
    }
}

def fixFanSpeed(device, fanState) 
{
    def bondId = getBondIdFromDevice(device)
    def speed = translateHEFanSpeedToBond(bondId, state.fanProperties?.getAt(bondId)?.max_speed ?: 3, fanState)
    logAppEvent( "Setting fan speed for ${bondId} to ${fanState}", "debug" )

    if (fanState == "off") 
    {
        if (executeFixState(bondId, '{"power": 0}'))
        {
            device.sendEvent(name: "speed", value: "off", type: "digital", descriptionText: "Manual driver state update")
        }
    }
    else 
    {
        if (executeFixState(bondId, '{"speed": ' + speed + '}'))
        {
            device.sendEvent(name: "speed", value: fanState, type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixShadeState(device, state) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting shade state for ${bondId} to ${state}", "debug" )

    def open
    if (state == "open")
        open = 1
    else
        open = 0
        
    if (executeFixState(bondId, '{"open": ' + open + '}'))
    {
        if (open == 1)
        {
            device.sendEvent(name: "switch", value: "on", type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "windowShade", value: "open", type: "digital", descriptionText: "Manual driver state update")
        }
        else
        {
            device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "windowShade", value: "closed", type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixDirection(device, state) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting direction state for ${bondId} to ${state}", "debug" )

    def direction
    if (state == "forward")
        direction = 1
    else
        direction = -1
        
    if (executeFixState(bondId, '{"direction": ' + direction + '}'))
    {
        if (direction == 1)
        {
            device.sendEvent(name: "direction", value: "forward", type: "digital", descriptionText: "Manual driver state update")
        }
        else
        {
            device.sendEvent(name: "direction", value: "reverse", type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixFPFanPower(device, state) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting FP fan power state for ${bondId} to ${state}", "debug" )

    def fppower
    if (state == "on")
        fppower = 1
    else
        fppower = 0
        
    if (executeFixState(bondId, '{"fpfan_power": ' + fppower + '}'))
    {
        if (fppower == 1)
        {
            device.sendEvent(name: "switch", value: "on", type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "speed", value: "on", type: "digital", descriptionText: "Manual driver state update")
        }
        else
        {
            device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "speed", value: "off", type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixFPFanSpeed(device, fanState) 
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting FP fan speed state for ${bondId} to ${fanState}", "debug" )
    
    def speed = translateHEFanSpeedToBond(bondId, state.fireplaceProperties?.getAt(bondId)?.max_speed ?: 3, fanState)

    if (fanState == "off") 
    {
        if (executeFixState(bondId, '{"fpfan_power": 0}'))
        {
            device.sendEvent(name: "speed", value: "off", type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
        }
    }
    else 
    {
        if (executeFixState(bondId, '{"fpfan_speed": ' + speed + '}'))
        {
            device.sendEvent(name: "speed", value: fanState, type: "digital", descriptionText: "Manual driver state update")
            device.sendEvent(name: "switch", value: "on", type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixLightPower(device, state) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting light state for ${bondId} to ${state}", "debug" )
    
    def power
    if (state == "on")
        power = 1
    else
        power = 0
        
    if (device.deviceNetworkId.contains("uplight"))
    {
        if (executeFixState(bondId, '{"up_light": ' + power + '}'))
        {
            device.sendEvent(name: "switch", value: state, type: "digital", descriptionText: "Manual driver state update")
        }
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        if (executeFixState(bondId, '{"down_light": ' + power + '}'))
        {
            device.sendEvent(name: "switch", value: state, type: "digital", descriptionText: "Manual driver state update")
        }
    }
    else
    {
        if (executeFixState(bondId, '{"light": ' + power + '}'))
        {
            device.sendEvent(name: "switch", value: state, type: "digital", descriptionText: "Manual driver state update")
        }
    }
}

def fixLightLevel(device, state) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Setting light level for ${bondId} to ${state}", "debug" )
    
    if (device.deviceNetworkId.contains("uplight"))
    {
        if (executeFixState(bondId, '{"up_light_brightness": ' + state + '}')) 
        {
            device.sendEvent(name: "level", value: state, unit: "%", type: "digital", descriptionText: "Manual driver state update")
        }
        if (state == 0)
        {
            if (executeFixState(bondId, '{"up_light": 0}')) 
            {
                device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            }
        }
    }
    else if (device.deviceNetworkId.contains("downlight"))
    {
        if (executeFixState(bondId, '{"down_light_brightness": ' + state + '}'))
        {
            device.sendEvent(name: "level", value: state, unit: "%", type: "digital", descriptionText: "Manual driver state update")
        }
        if (state == 0)
        {
            if (executeFixState(bondId, '{"down_light": 0}')) 
            {
                device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            }
        }
    }
    else 
    {
        if (executeFixState(bondId, '{"brightness": ' + state + '}'))
        {
            device.sendEvent(name: "level", value: state, unit: "%", type: "digital", descriptionText: "Manual driver state update")
        }
        if (state == 0)
        {
            if (executeFixState(bondId, '{"light": 0}')) 
            {
                device.sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "Manual driver state update")
            }
        }
    }
}

def translateBondFanSpeedToHE( id, max_speeds, speed ) {
    logAppEvent( "translateBondFanSpeedToHE(): called with id=${id}, max_speeds=${max_speeds}, speed=${speed}", "trace" )
    def speedTranslations = 
    [
        10: [10: "high", 9: "high", 8: "medium-high", 7: "medium-high", 6: "medium", 5: "medium", 4: "medium-low", 3: "medium-low", 2: "low", 1: "low" ],
        9:  [ 9: "high",            8: "medium-high", 7: "medium-high", 6: "medium", 5: "medium", 4: "medium-low", 3: "medium-low", 2: "low", 1: "low" ],
        8:  [ 8: "high",            7: "medium-high", 6: "medium-high", 5: "medium", 4: "medium", 3: "medium-low", 2: "medium-low", 1: "low" ],
        7:  [ 7: "high",            6: "medium-high",                   5: "medium", 4: "medium", 3: "medium-low", 2: "medium-low", 1: "low" ],
        6:  [ 6: "high",            5: "medium-high",                   4: "medium", 3: "medium", 2: "medium-low",                  1: "low" ],
        5:  [ 5: "high",            4: "medium-high",                   3: "medium",              2: "medium-low",                  1: "low" ],
        4:  [ 4: "high",                                                3: "medium",              2: "medium-low",                  1: "low" ],
        3:  [ 3: "high",                                                2: "medium",                                                1: "low" ],
        2:  [ 2: "high",                                                                                                            1: "low" ]
    ]
    
    if ( !speed.toString().isNumber() )
        return speed
        
    if ( max_speeds > 10 || speed > max_speeds )
        return "high"

    logAppEvent( "translateBondFanSpeedToHE(): id=${id} -> Translating ${speed}:${max_speeds} to HE ${speedTranslations[max_speeds][speed]}", "debug" )
    return speedTranslations[max_speeds][speed]
}

def translateHEFanSpeedToBond( id, max_speeds, speed ) {
    logAppEvent( "translateHEFanSpeedToBond(): called with id=${id}, max_speeds=${max_speeds}, speed=${speed}", "trace" )
    if (speed.isNumber())
        return speed.toInteger()

    def speedTranslations =
    [
        10: [ "high": 10, "medium-high": 8, "medium": 5, "medium-low": 3, "low": 1 ],
        9:  [ "high": 9,  "medium-high": 7, "medium": 5, "medium-low": 3, "low": 1 ],
        8:  [ "high": 8,  "medium-high": 6, "medium": 4, "medium-low": 3, "low": 1 ],
        7:  [ "high": 7,  "medium-high": 6, "medium": 4, "medium-low": 3, "low": 1 ],
        6:  [ "high": 6,  "medium-high": 5, "medium": 3, "medium-low": 2, "low": 1 ],
        5:  [ "high": 5,  "medium-high": 4, "medium": 3, "medium-low": 2, "low": 1 ],
        4:  [ "high": 4,  "medium-high": 3, "medium": 3, "medium-low": 2, "low": 1 ],
        3:  [ "high": 3,  "medium-high": 2, "medium": 2, "medium-low": 2, "low": 1 ],
        2:  [ "high": 2,  "medium-high": 2, "medium": 1, "medium-low": 1, "low": 1 ]
    ]

    if (max_speeds > 10)
        return 0

    logAppEvent( "translateHEFanSpeedToBond(): id=${id} -> Translating ${speed}:${max_speeds} to BOND ${speedTranslations[max_speeds][speed]}", "debug" )
    return speedTranslations[max_speeds][speed].toInteger()
}

def handleFanSpeed(device, speed) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "handleFanSpeed(): called for device=${bondId}, speed=${speed}", "debug" )

    if ( settings?.logLevel == "trace" ) {
        logAppEvent( "handleFanSpeed(): checking Bond device state before speed change...", "trace")
        getState(bondId)
    }

    if ( speed == "off" ) {
        if ( handleOff( device ) ) {
            device.sendEvent( name: "speed", value: "off", type: "digital" )
        }
    } else if ( speed == "on" ) {
        handleOn( device )
    } else {
        def max_speed = state.fanProperties?.getAt( bondId )?.max_speed ?: 3
        logAppEvent( "handleFanSpeed(): calling SetSpeed() with bondId=${bondId}, max_speed=${max_speed} speed=${speed}", "debug" )
        if ( executeAction( bondId, "SetSpeed", translateHEFanSpeedToBond( bondId, max_speed, speed ) ) ) {
            device.sendEvent( name: "switch", value: "on", type: "digital" )
            device.sendEvent( name: "speed", value: speed, type: "digital" )
        }
    }
    if ( settings?.logLevel == "trace" ) {
        logAppEvent( "handleFanSpeed(): checking Bond device state after speed change...", "trace")
        getState(bondId)
    }
}

def handleFPFanSpeed(device, speed) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Fireplace Fan Speed event for ${bondId}", "debug" )

    if (speed == "off")    
        handleFPFanOff(device)
    else if (speed == "on")
        handleFPFanOn(device)
    else
    {
        if (executeAction(bondId, "SetSpeed", translateHEFanSpeedToBond(bondId, state.fireplaceProperties?.getAt(bondId)?.max_speed ?: 3, speed))) 
        {
            device.sendEvent(name: "speed", value: speed, type: "digital")
        }
    }
}

def handleFPFanOn(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Fan On event for ${bondId}", "debug" )
    
    if (executeAction(bondId, "TurnFpFanOn")) 
    {
        device.sendEvent(name: "switch", value: "on", type: "digital")
        device.sendEvent(name: "speed", value: "on", type: "digital")
        return true
    }
    
    return false
}

def handleFPFanOff(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Fan Off event for ${bondId}", "debug" )
    
    
    if (executeAction(bondId, "TurnFpFanOff")) 
    {
        device.sendEvent(name: "switch", value: "off", type: "digital")
        device.sendEvent(name: "speed", value: "off", type: "digital")
        return true
    }
    
    return false
}

def handleOff(device) {
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Off event for ${bondId}", "debug" )

    if (executeAction(bondId, "TurnOff") && shouldSendEvent(bondId)) 
    {
        device.sendEvent(name: "switch", value: "off", type: "digital")
        if (device.hasCapability("FanControl"))
        device.sendEvent(name: "speed", value: "off", type: "digital")
        return true
    }
    
    return false
}

def handleDirection(device, direction)
{
    def bondId = getBondIdFromDevice(device)
    logAppEvent( "Handling Direction event for ${bondId}", "debug" )

    def bondDirection = 1
    if (direction == "reverse")
    bondDirection = -1
    if (executeAction(bondId, "SetDirection", bondDirection)) 
    {
        device.sendEvent(name: "direction", value: direction, type: "digital")
    }
    
}

def getState(bondId) {
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${bondId}/state",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    def stateToReturn = null
    try
    {
        httpGet(params) { resp ->
            if (checkHttpResponse("getState", resp))
                stateToReturn = resp.data
        }
    }
    catch (java.net.NoRouteToHostException e) {
        log.error "getState: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (org.apache.http.conn.ConnectTimeoutException e)
    {
        log.error "getState: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (Exception e)
    {
        checkHttpResponse("getState", e.getResponse())
    }
    return stateToReturn
}

def hasAction(bondId, commandType) {
    logAppEvent( "searching for ${commandType} for ${bondId}", "debug" )
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${bondId}/actions",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ]
    ]
    def commandToReturn = false
    try
    {
        httpGet(params) { resp ->
            if (checkHttpResponse("hasAction", resp))
            {
                for (commandId in resp.data) {
                    if (commandId.key == "_")
                        continue
                    if (commandId.key == commandType) {
                        logAppEvent( "found command ${commandId.key} for ${bondId}", "debug" )
                        commandToReturn = true
                        break
                    }
                }
            }
        }
    }
    catch (java.net.NoRouteToHostException e) {
        log.error "executeFixState: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (org.apache.http.conn.ConnectTimeoutException e)
    {
        log.error "hasAction: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (Exception e)
    {
        checkHttpResponse("hasAction", e.getResponse())
    }
    return commandToReturn
}

def executeAction(bondId, action) {
    logAppEvent( "executeAction(): called with bondId='${bondId}', action='${action}'", "debug" )
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${bondId}/actions/${action}",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ],
        body: "{}"
    ]
    logAppEvent( "executeAction(): params.uri='${params.uri}', params.path='${params.path}', params.headers='${params.headers}', params.body='${params.body}'", "trace" )
    def isSuccessful = false
    try
    {
        httpPut(params) { resp ->
            isSuccessful = checkHttpResponse("executeAction", resp)
        }
    }
    catch (java.net.NoRouteToHostException e) {
        log.error "executeAction: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (org.apache.http.conn.ConnectTimeoutException e)
    {
        log.error "executeAction: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (Exception e)
    {
        checkHttpResponse("executeAction", e.getResponse())
    }
    return isSuccessful
}

def executeAction(bondId, action, argument) {
    logAppEvent( "executeAction(): called with bondId='${bondId}', action='${action}', argument='${argument}'", "debug" )
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${bondId}/actions/${action}",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ],
        body: '{"argument": ' + argument +'}'
    ]
    logAppEvent( "executeAction(): params.uri='${params.uri}', params.path='${params.path}', params.headers='${params.headers}', params.body='${params.body}'", "trace" )
    def isSuccessful = false
    try
    {
        httpPut(params) { resp ->
            isSuccessful = checkHttpResponse("executeAction", resp)
        }
    }
    catch (java.net.NoRouteToHostException e) {
        log.error "executeAction: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (org.apache.http.conn.ConnectTimeoutException e)
    {
        log.error "executeAction: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (Exception e) 
    {
        checkHttpResponse("executeAction", e.getResponse())
    }
    return isSuccessful
}

def executeFixState(bondId, body) {
    def params = [
        uri: "http://${hubIp}",
        path: "/v2/devices/${bondId}/state",
        contentType: "application/json",
        headers: [ 'BOND-Token': hubToken ],
        body: body
    ]
    def isSuccessful = false
    logAppEvent( "calling fix state ${params.body}", "debug" )
    try
    {
        httpPatch(params) { resp ->
            isSuccessful = checkHttpResponse("executeFixState", resp)
        }
    }
    catch (java.net.NoRouteToHostException e) {
        log.error "executeFixState: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (org.apache.http.conn.ConnectTimeoutException e)
    {
        log.error "executeFixState: connection to BOND hub appears to be down. Check if the IP is correct."
    }
    catch (Exception e) 
    {
        checkHttpResponse("executeFixState", e.getResponse())
    }
    return isSuccessful
}

def shouldSendEvent(bondId) {
    for (fan in fans) 
    {
        if (fan == bondId)
            return true;
    }
    
    if (fireplaces != null)
    {
        for (def i = 0; i < fireplaces.size(); i++)
        {
            if (fireplaces[i] == bondId)
            {
                if (this.getProperty("fireplaceSensor${i}") != null)
                    return false;
                return true;
            }
        }
    }
    return true;
}

def checkHttpResponse(action, resp) {
    if (resp.status == 200 || resp.status == 201 || resp.status == 204) {
        logAppEvent( "checkHttpResponse(): ${action}: Bond response, status: '${resp.status}', data: '${resp.getData()}'", "trace" )
        return true
    } else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500) {
        logAppEvent( "checkHttpResponse(): ${action}: Bond error response: ${resp.status} - ${resp.getData()}", "error" )
        return false
    } else {
        if ( resp.getData() == null ) {
            logAppEvent( "checkHttpResponse(): ${action}: Unexpected Bond error response: ${resp.status} - (Bond returned no response data)", "error" )
        } else {
            logAppEvent( "checkHttpResponse(): ${action}: Unexpected Bond error response: ${resp.status} - ${resp.getData()}", "error" )
        }
        return false
    }
}

def displayFooter(){
    section() {
        paragraph getFormat("line")
        paragraph "<div style='color:#1A77C9;text-align:center'>BOND Home Integration<br><a href='https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url' target='_blank'><img src='https://www.paypalobjects.com/webstatic/mktg/logo/pp_cc_mark_37x23.jpg' border='0' alt='PayPal Logo'></a><br><br>Please consider donating. This app took a lot of work to make.<br>If you find it valuable, I'd certainly appreciate it!</div>"
    }       
}

def getFormat(type, myText=""){            // Modified from @Stephack Code   
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}