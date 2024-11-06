/**
 *  BOND Fan With Direction v2
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Additonal Copyright 2024 Gatewood Green
 *  Oct 12, 2024 - Implemented supportedFanSpeeds & cycleSpeed(), fixing Google Home integration breakage with Hubitat 2.3.9.192
 *  Oct 13, 2024 - Implmented auto configuration
 *  Oct 18, 2024 - Implmented breeze functionality and data/device debug support, aka queryDevice()
 *  Oct 23, 2024 - Fixed issue for fans without Breeze support and added runAction()
 *  Oct 23, 2024 - Merge BOND_Fan_With_Direction_v2 with BOND_Fan_v2
 *  Oct 23, 2024 - Clean up queryDevice()/queryBondAPI(). Add updateBondState command.
 *  Oct 23, 2024 - made getMaxSpeed() more resilient
 *  Oct 23, 2024 - made getMyBondId() more resilient
 *  Oct 23, 2024 - minor error reporting improvements
 *  Nov 04, 2024 - logging improvements, fix Hubitat/groovy data type issues detecting Breeze support
 *  Nov 04, 2024 - log at the highest level of detail requested between the driver and parent Bond Home Integration app
 *  Nov 05, 2024 - fix getBondDeviceState() bug, Update sendEvent() calls for type of event when changing or detecting device state, fix toggleBreeze() not updating driver state
 *
 *  VERSION 202411051815
 */

metadata {
    definition (
        name:      "BOND Fan With Direction v2", 
        namespace: "bond", 
        author:    "gatewoodgreen@gmail.com",
        importUrl: "https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/refs/heads/master/drivers/BOND_Fan_With_Direction_v2.groovy"
    ) {
        capability "Switch"
        capability "FanControl"
        capability "Configuration"

        attribute "direction",             "enum", ["forward", "reverse"]
        attribute "bondFanMaxSpeed",       "number"
        attribute "bondBreezeMode",        "number"
        attribute "bondBreezeAverage",     "number"
        attribute "bondBreezeSupport",     "number"
        attribute "bondBreezeVariability", "number"
        attribute "bondDirectionSupport",  "number"

        command "configure",       [ [ name:"Detect and configure Bond fan device parameters and update driver settings" ] ]
        command "cycleSpeed",      [ [ name:"Increase the fan's speed one setting. If the fan is off, Cycle Speed will turn the fan on and set it to Low. If the fan is on High, return to the Low speed setting" ] ]
        command "fixPower",        [ [ name:"Power*", type: "ENUM", description: "Update Hubitat and Bond Bridge's belief of the fan's current Power State", constraints: [ "off","on" ] ] ]
        command "fixSpeed",        [ [ name:"Speed*", type: "ENUM", description: "Update Hubitat and Bond Bridge's belief of the fan's current Speed", constraints: [ "off","low", "medium-low", "medium", "medium-high", "high", "on" ] ] ]
        command "fixDirection",    [ [ name:"Direction*", type: "ENUM", description: "Update Hubitat and Bond Bridge's belief of the fan's current Direction", constraints: [ "forward","reverse" ] ] ]
        command "queryDevice",     [ [ name:"Query Bond controller for useful debug information and store it in the Device Details, Data field below" ] ]
        command "rebootBond",      [ [ name:"Reboot Bond Controller. Reboot command may not work on all Smart By Bond controllers" ] ]
        command "setDirection",    [ [ name:"Direction*",  type: "ENUM", description: "Change the Fan's Direction. Note: Not all fans support directon change through Hubitat/Bond", constraints: [ "forward","reverse" ] ] ]
        command "toggle",          [ [ name:"Toggle the fan on or off" ] ]
        command "toggleBreeze",    [ [ name:"Toggle the fan's built-in Breeze feature on or off. If the fan is off, Toggle Breeze will turn on fan and enable the Breeze feature. Not all Bond controlled fans have built-in Breeze support" ] ]
        command "updateBondState", [ [ name:"Force Hubitat to get current Bond device state and update driver state values" ] ]
        command "wipeStateData",   [ [ name:"Clear the Hubitat driver's understanding of current parameters and state along with any stored data" ] ]

        command "runAction",          [ [ name:"BondAction*",  type:"string", description:"Bond Device Action" ],
                                        [ name:"Arguments",    type:"string", description:"Action Arguments" ] ]
        command "setBreezeParameters",[ [ name:"AverageSpeed*",type:"number", description:"Average Speed" ],
                                        [ name:"Variability*", type:"number", description:"Speed Variability" ] ]
    }

    preferences {
        input name: "logLevel", type: "enum", title: "Logging Level", defaultValue: 3, options: [3: "info", 2:"debug", 1:"trace"], required: true
    }
}

void logEvent ( message="", level="info" ) {    
    Map     logLevels = [ error: 5, warn: 4, info: 3, debug: 2, trace: 1 ]
    Integer msgLevelN = logLevels[ level ].toInteger()
    String  name      = device.displayName.toString()
    Integer logLevelN = 0
    Integer appLevelN = 6 /* impossibly high for later test */

    /*
     *  Will generate error in logs in App is an older version of code without childGetSettings().
     *  Can't seem to trap MissingMethodExceptionNoStack exceptions
     */
    String appLogLevel = stuff = parent.childGetSettings( "logLevel" )
    if ( appLogLevel == null ) {
        log.error "${name}: logEvent(): Unable to get Bond app log setting, please update Bond Home Integration app code. This error goes with the previous MissingMethodExceptionNoStack error"
    } else {
        appLevelN = logLevels[ appLogLevel ].toInteger()
        if ( appLevelN == null || appLevelN < 0 || appLevelN > 5 ) {
            appLevelN = 6 /* impossibly high for later test */
        }
    }

    if ( device.getSetting( "logLevel" ) == null ) {
        device.updateSetting( "logLevel", "3" ) /* Send string to imitate what the preference dialog will do for enum numeric keys */
        log.info "${name}: logEvent(): set default log level to 3 (info)"
        logLevelN = 3
    } else {
        logLevelN = device.getSetting( "logLevel" ).toInteger()
        if ( logLevelN == null || logLevelN < 1 || logLevelN > 5 )
            logLevelN = 3 /* default to info on unexpected value */
    }
    if ( msgLevelN == null || msgLevelN < 1 || msgLevelN > 5 ) {
        msgLevelN = 3
        level     = "info"
    }
    /* We log at the highest level of detail (lower level number) between the app and driver */
    if ( msgLevelN >= logLevelN || msgLevelN >= appLevelN )
        log."${level}" "${name}: ${message}"
}

boolean checkHttpResponse( action, resp ) {
    if ( resp.status == 200 || resp.status == 201 || resp.status == 204 ) {
        logEvent( "getHttpData(): status: ${resp.status}, data: ${resp.data}", "trace" )
        return true
    } else if ( resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500 ) {
        logEvent( "checkHttpResponse(): ${action}: Bond error response: ${resp.status} - ${resp.getData()}", "warn" )
        return false
    } else {
        if ( resp.getData() == null )
            logEvent( "checkHttpResponse(): ${action}: Unexpected Bond error response: ${resp.status} - (Bond returned no response data)", "error" )
        else
            logEvent( "checkHttpResponse(): ${action}: Unexpected Bond error response: ${resp.status} - ${resp.getData()}", "error" )
        return false
    }
}

def getHttpData( Map params ) {
    def result         = null
    params.uri         = "http://${parent.hubIp}"
    params.contentType = "application/json"
    params.headers     = [ 'BOND-Token': parent.hubToken ]
    try {
        httpGet(params) { resp ->
            if (checkHttpResponse( "getHttpData", resp ) ) {
                result = resp.data
            }
        }
    } catch (err) {
        checkHttpResponse( "getHttpData", err.getResponse() )
    }
    return result
}

def getBondVersion() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/sys/version"
    ]
    def bondVersion = getHttpData( params )
    if ( bondVersion != null ) {
        device.updateDataValue( "bondVersion", bondVersion.toMapString() )
    } else {
        device.updateDataValue( "bondVersion", null )
        logEvent( "queryBondAPI(): Failed to get Bond version data", "error" )
    }
    return bondVersion
}

def rebootBond() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/sys/reboot"
    ]
    def bondReboot = getHttpData( params )
    if ( bondReboot != null ) {
        device.updateDataValue( "bondReboot", bondReboot.toMapString() )
    } else {
        device.updateDataValue( "bondReboot", null )
        logEvent( "bondReboot(): Failed to reboot Bond. Command may not be implemented on controller.", "error" )
    }
    return bondReboot
}

def getBondDeviceData() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}"
    ]
    def bondDevice = getHttpData( params )
    if ( bondDevice != null ) {
        device.updateDataValue( "bondDevice", bondDevice.toMapString() )
    } else {
        device.updateDataValue( "bondDevice", null )
        logEvent( "getBondDeviceData(): Failed to get Bond device data for fan ${myId}", "error" )
    }
    return bondDevice
}

def getBondDeviceProperties() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}/properties"
    ]
    def bondProperties = getHttpData( params )
    if ( bondProperties != null ) {
        device.updateDataValue( "bondProperties", bondProperties.toMapString() )
    } else {
        device.updateDataValue( "bondProperties", null )
        logEvent( "getBondDeviceProperties(): Failed to get Bond properties data for fan ${myId}", "error" )
    }
    return bondProperties
}

def getBondDeviceState() {
    def myId      = getMyBondId()
    def maxSpeedN = getMaxSpeed( myId )
    def params    = [
        path: "/v2/devices/${myId}/state"
    ]
    def bondState = getHttpData( params )
    if ( bondState != null ) {
        device.updateDataValue( "bondState", bondState.toMapString() )
        if ( bondState.breeze != null ) {
            sendEvent( name:"bondBreezeMode", value:"${bondState.breeze[0]}" )
            sendEvent( name:"bondBreezeAverage", value:"${bondState.breeze[1]}" )
            sendEvent( name:"bondBreezeSupport", value:1 )
            sendEvent( name:"bondBreezeVariability", value:"${bondState.breeze[2]}" )
        } else {
            sendEvent( name:"bondBreezeSupport", value:0 )
        }
        if ( bondState.direction != null )
            sendEvent( name:"bondDirectionSupport", value:1 )
        else
            sendEvent( name:"bondDirectionSupport", value:0 )
        if ( bondState.power != null ) {
            if ( bondState.power ) {
                if ( device.currentValue( "switch" ) == "off" ) {
                    sendEvent( name: "switch", value: "on",  type: "physical", descriptionText: "Bond fan state update 'on'"  )
                    def curSpeedS = parent.translateBondFanSpeedToHE( myId, maxSpeedN, bondState.speed )
                    device.sendEvent( name: "speed",  value: curSpeedS, type: "physical", descriptionText: "Bond fan speed state update '${curSpeedS}'" )
                }
            } else {
                if ( device.currentValue( "switch" ) == "on" ) {
                    sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan state update 'off'" )
                    sendEvent( name: "speed",  value: "off", type: "physical", descriptionText: "Bond fan speed state update 'off'"  )
                }
            }
        }
    } else {
        device.updateDataValue( "bondState", null )
        logEvent( "getBondDeviceState(): Failed to get Bond state data for fan ${myId}", "error" )
    }
    return bondState
}

def getBondDeviceActions() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}/actions"
    ]
    def bondActions = getHttpData( params )
    if ( bondActions != null ) {
        device.updateDataValue( "bondActions", bondActions.toMapString() )
    } else {
        device.updateDataValue( "bondActions", null )
        logEvent( "getBondDeviceActions(): Failed to get Bond actions data for fan ${myId}", "error" )
    }
    return bondActions
}

def getBondDevicePowerCycleState() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}/power_cycle_state"
    ]
    def bondPowerCycleState = getHttpData( params )
    if ( bondPowerCycleState != null ) {
        device.updateDataValue( "bondPowerCycleState", bondPowerCycleState.toMapString() )
    } else {
        device.updateDataValue( "bondPowerCycleState", null )
        logEvent( "getBondDevicePowerCycleState(): Failed to get Bond power cycle state data for fan ${myId}", "error" )
    }
    return bondPowerCycleState
}

def getBondDeviceRemoteAddressAndLearn() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}/addr"
    ]
    params.path = "/v2/devices/${myId}/addr"
    def bondAddr = getHttpData( params )
    if ( bondAddr != null ) {
        device.updateDataValue( "bondAddr", bondAddr.toMapString() )
    } else {
        device.updateDataValue( "bondAddr", null )
        logEvent( "getBondDeviceRemoteAddressAndLearn(): Failed to get Bond remote address and learn window data for fan ${myId}", "error" )
    }
    return bondAddr
}

def getBondDeviceCommands() {
    def myId = getMyBondId()
    def params = [
        path: "/v2/devices/${myId}/commands"
    ]
    def bondCommands = getHttpData( params )
    if ( bondCommands != null ) {
        device.updateDataValue( "bondCommands", bondCommands.toMapString() )
    } else {
        device.updateDataValue( "bondCommands", null )
        logEvent( "getBondDeviceCommands(): Failed to get Bond commands data for fan ${myId} (this is expected as is the preceding 404 error)", "warn" )
    }
    return bondCommands
}

def queryBondAPI() {
    String message = ""

    sendEvent(name:"queryStatus", value:"Step 1/10 Running configure()...")
    chkConfigure()

    sendEvent(name:"queryStatus", value:"Step 2/10 Getting Bond version data...")
    getBondVersion()

    sendEvent(name:"queryStatus", value:"Step 3/10 Getting Bond device data...")
    def bondDevice = getBondDeviceData()

    sendEvent(name:"queryStatus", value:"Step 4/10 Getting Bond device properties...")
    if ( bondDevice.properties == null ) {
        message = "Bond controller does not implement the properties endpoint"
        logEvent( "queryBondAPI(): ${message}", "info" )
        device.updateDataValue( "bondProperties", message )
    } else {
        def bondProperties = getBondDeviceProperties()
    }

    sendEvent(name:"queryStatus", value:"Step 5/10 Getting Bond device state...")
    def bondState = getBondDeviceState()

    sendEvent(name:"queryStatus", value:"Step 6/10 Getting Bond device actions...")
    def bondActions = getBondDeviceActions()

    sendEvent(name:"queryStatus", value:"Step 7/10 Getting Bond device commands...")
    if ( bondDevice.commands == null ) {
        message = "Bond controller does not implement the commands endpoint"
        logEvent( "queryBondAPI(): ${message}", "info" )
        device.updateDataValue( "bondCommands", message )
    } else {
        def bondCommands = getBondDeviceCommands()
    }

    sendEvent(name:"queryStatus", value:"Step 8/10 Getting Bond power cycle state...")
    if ( bondDevice.power_cycle_state == null ) {
        message = "Bond controller does not implement the power_cycle_state endpoint"
        logEvent( "queryBondAPI(): ${message}", "info" )
        device.updateDataValue( "bondPowerCycleState", message )
    } else {
        def bondPowerCycleState = getBondDevicePowerCycleState()
    }

    sendEvent( name:"queryStatus", value:"Step 9/10 Getting Bond remote address and learn data..." )
    if ( bondDevice.addr == null ) {
        message = "Bond controller does not implement the addr endpoint"
        logEvent( "queryBondAPI(): ${message}", "info" )
        device.updateDataValue( "bondAddr", message )
    } else {
        def bondAddr = getBondDeviceRemoteAddressAndLearn()
    }

    sendEvent( name:"queryStatus", value:"Step 10/10 Query complete -<br>REFRESH the page." )
    device.updateDataValue( "lastBondApiQuery", new Date().format("MM/dd/yyyy HH:mm:ss '('ZZZZZ/zzz')'") )
}

String getMyBondId() {
    def bondDeviceId = ""
    bondDeviceId = parent.getBondIdFromDevice( device )
    if ( bondDeviceId == null || bondDeviceId == "" ) {
        logEvent( "getMyBondId(): failed to get device Id from Bond app", "debug" )
        List<String> networkId = device.getDeviceNetworkId().split( ":" )
        if ( networkId[2] != null ) {
            bondDeviceId = networkId[2]
            logEvent( "getMyBondId(): pulled ${bondDeviceId} from ${device.getDeviceNetworkId()}", "debug" )
        } else {
            logEvent( "getMyBondId(): failed to get device Id from getDeviceNetworkId(): ${device.getDeviceNetworkId()}. Trying '1'", "error" )
            bondDeviceId = 1 /* guess */
        }
    }
    return bondDeviceId
}

void configure() {
    def myId = getMyBondId()
    def max = getMaxSpeed( myId )
    if ( max != null ) {
        sendEvent( name: 'bondFanMaxSpeed', value: max )
    } else {
        logEvent( "configure() failed to get fan max speed", "error" )
    }
    getBondDeviceState()
    loadSupportedFanSpeeds( max )
    if ( state.maxSpeed != null ) {
        wipeStateData( 1 )
    }
}

void chkConfigure() {
    if ( device.currentValue('bondFanMaxSpeed') == null ) {
        configure()
    }
}

void updateBondState () {
    getBondDeviceState()
}

void wipeStateData( int silent=0 ) {
    state.clear()
    
    device.deleteCurrentState( "bondBreezeMode" )
    device.deleteCurrentState( "bondFanMaxSpeed" )
    device.deleteCurrentState( "bondBreezeAverage" )
    device.deleteCurrentState( "bondBreezeSupport" )
    device.deleteCurrentState( "bondBreezeVariability" )
    device.deleteCurrentState( "bondDirectionSupport" )
    device.deleteCurrentState( "supportedFanSpeeds" )

    device.removeSetting( "debugEnable" )
    device.removeSetting( "logLevel" )

    def dataValues = device.getData()
    String[] dvalues = []
    dataValues.each { key, val ->
        dvalues = dvalues + key
    }
    dvalues.each { val -> device.removeDataValue( val ) }
    logEvent( "wipeStateData(): Cleared state and device data", "info" )
    if ( silent < 1 ) {
        sendEvent(name:"queryStatus", value:"State and data wipe complete -<br>REFRESH the page.")
    }
}

int getMaxSpeed( devId ) {
    int maxSpeedN = 0
    int fallBack  = 0
    
    /* All of this due to dirty, unreliable data structures */
    if ( parent.state.fanProperties != null ) {
        device.updateDataValue( "bondAppFanProperties", parent.state.fanProperties.toMapString() )
        try {
            maxSpeedN = parent.state.fanProperties.get( devId ).get( "max_speed" )
            logEvent( "getMaxSpeed(): got max_speed from fanProperties", "debug" )
        } catch ( err ) {
            logEvent( "getMaxSpeed(): parent.state.fanProperties get max_speed failed (${err.message}), getting fan properties directly...", "warn" )
            fallBack = 1
        }
    } else {
        logEvent( "getMaxSpeed(): parent.state.fanProperties is null, getting fan properties directly...", "warn" )
        fallBack = 1
    }
    
    if ( fallBack ) {
        def bondProperties = getBondDeviceProperties()
        if ( bondProperties == null ) {
            logEvent( "getMaxSpeed(): getBondDeviceProperties failed, guessing max_speed is 3", "warn" )
        } else {
            if ( bondProperties.max_speed == null ) {
                logEvent( "getMaxSpeed(): bondProperties.max_speed is null, guessing max_speed is 3", "warn" )
            } else {
                maxSpeedN = bondProperties.max_speed
                logEvent( "getMaxSpeed(): got max_speed from bondProperties", "debug" )
            }
        }
    }
    
    if ( maxSpeedN == null || maxSpeedN == 0 ) {
        logEvent( "getMaxSpeed(): Failed to get max_speed from all sources, guessing 3", "warn" )
        maxSpeedN = 3 /* just guess */
    }
    
    logEvent( "getMaxSpeed(): Fan Max Speed = ${maxSpeedN}", "debug" )
    return maxSpeedN
}

void loadSupportedFanSpeeds( int maxSpeedN ) {
    int curSpeedN = 0
    String[] fanSpeeds = []
    
    while ( curSpeedN < maxSpeedN ) {
        curSpeedN += 1
        newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, curSpeedN )
        logEvent( "loadSupportedFanSpeeds() Found new speed: ${newSpeedS}", "debug" )
        fanSpeeds = fanSpeeds + [ newSpeedS ]
    }
    if ( device.currentValue( "bondBreezeMode" ) != null )
        fanSpeeds =  fanSpeeds.reverse() + [ "auto" ]
    speedList = fanSpeeds.join( "," )
    logEvent( "loadSupportedFanSpeeds() fanSpeeds = [${speedList},off,on]", "debug" )
    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeeds + ["off", "on"]))
}

void queryDevice() {
    def bondDeviceId = getMyBondId()
    if ( bondDeviceId == null ) {
        logEvent( "queryDevice(): Bond device ID missing, running configure(). Try again", "warn" )
        chkConfigure()
        return
    }
    def devState = parent.getState( bondDeviceId )
    if ( devState == null ) {
        logEvent( "queryDevice(): parent.getstate( ${bondDeviceId} ) failed", "warn" )
        return
    } else {
        strState = devState.toMapString()
        logEvent( "queryDevice(): Full Device State: ${strState}", "info" )
        devSpeed = devState.get( "speed" )
        devPower = devState.get( "power" )
        drvPower = device.currentValue("switch")
        drvSpeed = device.currentValue("speed")
        logEvent( "queryDevice(): Device Power: ${devPower}, Device Speed: ${devSpeed} Driver Power: ${drvPower}, Driver Speed: ${drvSpeed}", "info" )
    }
    queryBondAPI()
}

def setBreezeParameters( mean=50, var=50 ) {
    logEvent( "setBreezeParameters() Called, aveSpeed=${mean}, variability=${var}", "info" )
    if ( mode > 100 )
        mode = 100
    if ( $mode < 0 )
        mode = 0
    if ( var > 100 )
        var = 100
    if ( var < 0 )
        var = 0
    def myId = getMyBondId()
    def curSpeed = device.currentValue( "speed" )
    def mode = 0
    if ( curSpeed == "auto" ) {
        mode = 1
    }
    logEvent( "setBreezeParameters() Setting Breeze Parameters (mode=${mode}, aveSpeed=${mean}, variability=${var})", "info" )
    parent.executeAction( myId, "SetBreeze", "[${mode}, ${mean}, ${var}]" )
    getBreezeState()
}

def getBreezeState() {
    def bondState = getBondDeviceState()
    if ( bondState.breeze == null )
        return null
    else
        return bondState.breeze[0]
}

def getDeviceSpeed() {
    def myId      = getMyBondId()
    def maxSpeedN = getMaxSpeed( myId )
    def bondState = getBondDeviceState()
    def devSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, bondState.speed )
    return devSpeedS
}

def toggleBreeze( force="" ) {
    chkConfigure()
    if ( device.currentValue( "bondBreezeSupport" ) == null ) {
        logEvent( "toggleBreeze(): bondBreezeSupport is null, ran configure(), try again.", "warn" )
        return
    }
    int bondBreezeSupport = device.currentValue( "bondBreezeSupport" ).toInteger()
    if ( bondBreezeSupport < 1 ) {
        logEvent( "toggleBreeze(): Device does not support Breeze mode", "error" )
        return
    }
    def myId = getMyBondId()
    def curSpeed = device.currentValue( "speed" )
    def mode = 1
    logEvent( "toggleBreeze(${force}) called", "info" )
    if ( curSpeed == "auto" ) {
        return
    }
    def breezeState = getBreezeState()
    def targetState = "BreezeOn"
    if ( breezeState ) {
        targetState = "BreezeOff"
    }
    if ( force == "off") {
        targetState = "BreezeOff"
    } else if ( force == "on" ) {
        targetState = "BreezeOn"
    }

    if ( targetState == "BreezeOn" ) {
        parent.executeAction( myId, targetState )
        sendEvent( name:"bondBreezeMode", value:1 )
        if ( device.currentValue( "speed" ) == "off" )
            sendEvent( name:"switch", value:"on", type:"digital" )
        sendEvent( name:"speed", value:getDeviceSpeed(), type:"digital" )
    } else {
        off()
    }
}

void runAction( action="", parameters="" ) {
    def myId = getMyBondId()
    if ( action == "" ) {
        logEvent( "runAction(): No action provided", "error" )
        return
    }
    if ( parameters == "" )
        result = parent.executeAction( myId, action )
    else
        result = parent.executeAction( myId, action, parameters )
    logEvent( "runAction(): action: (${action}) parameters: (${parameters}) result: ${result}", "info" )
}

void on() {
    chkConfigure()
    parent.handleOn( device )
    if ( state.lastSpeed != null )
        parent.handleFanSpeed( device, state.lastSpeed )
    devSpeedS = getDeviceSpeed()
    sendEvent( name:"speed", value:devSpeedS, type:"digital" )
    logEvent( "on(): Turned fan on", "info" )
}

void off() {
    parent.handleOff(device)
    logEvent( "off(): Breeze Support (${device.currentValue( "bondBreezeSupport" ).toInteger()})", "trace" )
    if ( device.currentValue( "bondBreezeSupport" ).toInteger() > 0 ) {
        parent.executeAction( getMyBondId(), "BreezeOff" )
        sendEvent( name:"bondBreezeMode", value:"0" )
    }
    logEvent( "off(): Turned fan off", "info" )
}

void toggle() {
    if (device.currentValue("switch") == "on") {
        logEvent( "toggle(): Toggling fan off", "trace" )
        off()
    } else {
        logEvent( "toggle(): Toggling fan on", "trace" )
        on()
    }
}

void setSpeed( String speed ) {
    logEvent( "setSpeed(): Setting speed to ${speed}", "info" )
    logEvent( "setSpeed(): Breeze Support (${device.currentValue( "bondBreezeSupport" ).toInteger()})", "trace" )
    
    if (speed != "off" && speed != "on" && speed != "auto" )
        state.lastSpeed = speed
    
    if ( speed == "auto" ) {
        if ( device.currentValue( "bondBreezeSupport" ).toInteger() > 0 ) {
            toggleBreeze( "on" )
        }
        return
    } else if ( speed == "off" ) {
        off()
        return
    }
    if ( device.currentValue( "bondBreezeSupport" ).toInteger() > 0 ) {
        parent.executeAction( getMyBondId(), "BreezeOff" )
        sendEvent(name:"bondBreezeMode", value:"0")
    }
    parent.handleFanSpeed(device, speed)
}

void cycleSpeed() {
    int    maxSpeedN = 0
    int    curSpeedN = 0
    int    newSpeedN = 0
    String curSpeedS = ""
    String newSpeedS = "low"

    if ( device.currentValue('bondFanMaxSpeed') != null ) {
        maxSpeedN = device.currentValue('bondFanMaxSpeed')
    } else {
        logEvent( "cycleSpeed() ID Bond missing, running Configure. Try again", "warn" )
        chkConfigure()
        return
    }
    
    curSpeedS = device.currentValue( "speed" )
    if ( curSpeedS == "off" ) {
        parent.handleOn( device )
        setSpeed( newSpeedS )
        return
    }
    
    curSpeedN = parent.translateHEFanSpeedToBond( device, maxSpeedN, curSpeedS )
    logEvent( "cycleSpeed(): Current: curSpeedN: ${curSpeedN}, newSpeedN: ${newSpeedN}, maxSpeedN: ${maxSpeedN}", "debug" )
    newSpeedN = ( curSpeedN + 1 ) % maxSpeedN
    if ( newSpeedN == 0 ) {
        newSpeedN = maxSpeedN
    }
    newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, newSpeedN )
    logEvent( "cycleSpeed(): New: curSpeedN: ${curSpeedN}, newSpeedN: ${newSpeedN}, maxSpeedN: ${maxSpeedN}, newSpeedS: ${newSpeedS}", "debug" )
    setSpeed( newSpeedS )
}

void setDirection( direction ) {
    chkConfigure()
    if ( device.currentValue( "bondDirectionSupport" ) == null ) {
        logEvent( "setDirection(): bondDirectionSupport is null, ran configure(), try again.", "warn" )
        return
    }
    int bondDirectionSupport = device.currentValue( "bondDirectionSupport" ).toInteger()
    logEvent( "setDirection(): Bond Direction Support: ${bondDirectionSupport}", "debug" )
    if ( bondDirectionSupport > 0 ) {
        logEvent( "setDirection(): Calling handleDirection( ${device}, ${direction} )", "debug" )
        parent.handleDirection( device, direction )
    } else {
        logEvent( "setDirection(): Device does not seem to support direction through the Bond API", "warn" )
    }
}

void fixPower( power ) {
    parent.fixPowerState( device, power )
}

void fixSpeed( speed ) {
    parent.fixFanSpeed( device, speed )
}

void fixDirection( direction ) {
    parent.fixDirection( device, direction )
}

/* Child (light) device support */

void handleLightOn( device ) {
    parent.handleLightOn( device )
}

void handleLightOff( device ) {
    parent.handleLightOff( device )
}

void handleLightLevel( device, level ) {
    parent.handleLightLevel( device, level )
}

void handleDim( device, duration ) {
    parent.handleDim( device, duration )
}

void handleStartDimming( device ) {
    parent.handleStartDimming( device )
}

void handleStopDimming( device ) {
    parent.handleStopDimming( device )
}

void fixLightPower( device, power ) {
    parent.fixLightPower( device, power )
}

void fixLightLevel( device, level ) {
    parent.fixLightLevel( device, level )
}