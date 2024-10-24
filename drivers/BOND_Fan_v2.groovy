/**
 *  BOND Fan v2
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
 *
 *  VERSION 202410241335
 */

metadata {
    definition (
        name:      "BOND Fan v2", 
        namespace: "bond", 
        author:    "gatewoodgreen@gmail.com",
        importUrl: "https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/refs/heads/master/drivers/BOND_Fan_v2.groovy"
    ) {
        capability "Switch"
        capability "FanControl"
        capability "Configuration"

        attribute "direction", "enum", ["forward", "reverse"]
        attribute "bondFanMaxSpeed", "integer"
        attribute "bondBreezeMode", "integer"
        attribute "bondBreezeAverage", "integer"
        attribute "bondBreezeSupport", "integer"
        attribute "bondBreezeVariability", "integer"
        attribute "bondDirectionSupport", "integer"

        command "configure"
        /*
        command "fixPower",     [ [ name:"Power*", type: "ENUM", description: "Power", constraints: [ "off","on" ] ] ]
        command "fixSpeed",     [ [ name:"Speed*", type: "ENUM", description: "Speed", constraints: [ "off","low", "medium-low", "medium", "medium-high", "high", "on" ] ] ]
        command "fixDirection", [ [ name:"Direction*", type: "ENUM", description: "Direction", constraints: [ "forward","reverse" ] ] ]
        */
        command "setDirection", [ [ name:"Direction",  type: "ENUM", description: "Direction", constraints: [ "forward","reverse" ] ] ]
        command "updateBondState"
        command "rebootBond"
        command "toggle"
        command "queryDevice"
        command "wipeStateData"
        command "toggleBreeze"
        command "runAction",          [ [ name:"BondAction*",  type:"STRING", description:"Bond Device Action" ],
                                        [ name:"Arguments",    type:"STRING", description:"Action Arguments" ] ]
        command "setBreezeParameters",[ [ name:"AverageSpeed*",type:"NUMBER", description:"Average Speed" ],
                                        [ name:"Variability*", type:"NUMBER", description:"Speed Variability" ] ]
    }

    preferences {
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

void logDebug(String msg) {
    if (debugEnable) {
        log.debug( "${device.label?device.label:device.name}: ${msg}" )
    }
}

def getHttpData( Map params ) {
    def result = null
    params.uri         = "http://${parent.hubIp}"
    params.contentType = "application/json"
    params.headers     = [ 'BOND-Token': parent.hubToken ]
    try
    {
        httpGet(params) { resp ->
            if (parent.checkHttpResponse("getHttpResult", resp))
            {
                result = resp.data
            }
        }
    }
    catch (e)
    {
        parent.checkHttpResponse("getHttpResult", e.getResponse())
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
        log.error "${device.displayName}: queryBondAPI(): Failed to get Bond version data"
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
        log.error "${device.displayName}: bondReboot(): Failed to reboot Bond. Command may not be implemented on controller."
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
        log.error "${device.displayName}: getBondDeviceData(): Failed to get Bond device data"
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
        log.error "${device.displayName}: getBondDeviceProperties(): Failed to get Bond properties data"
    }
    return bondProperties
}

def getBondDeviceState() {
    def myId = getMyBondId()
    def bondState = parent.getState( myId )
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
                    def curSpeedS = translateBondFanSpeedToHE(fan, state.fanProperties[fan].max_speed ?: 3, bondState.speed)
                    device.sendEvent( name: "speed",  value: curSpeedS, descriptionText: "Bond fan speed state update '${curSpeedS}'" )
                }
            } else {
                if ( device.currentValue( "switch" ) == "on" ) {
                    sendEvent( name: "switch", value: "off", type: "physical", descriptionText: "Bond fan state update 'off'" )
                    sendEvent( name: "speed",  value: "off", descriptionText: "Bond fan speed state update 'off'"  )
                }
            }
        }
    } else {
        device.updateDataValue( "bondState", null )
        log.error "${device.displayName}: getBondDeviceState(): Failed to get Bond state data"
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
        log.error "${device.displayName}: getBondDeviceActions(): Failed to get Bond actions data"
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
        log.error "${device.displayName}: getBondDevicePowerCycleState(): Failed to get Bond power cycle state data"
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
        log.error "${device.displayName}: getBondDeviceRemoteAddressAndLearn(): Failed to get Bond remote address and learn window data"
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
        log.warn "${device.displayName}: getBondDeviceCommands(): Failed to get Bond commands data (this is expected as is the preceding 404 error)"
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
        log.info "${device.displayName}: queryBondAPI(): ${message}"
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
        log.info "${device.displayName}: queryBondAPI(): ${message}"
        device.updateDataValue( "bondCommands", message )
    } else {
        def bondCommands = getBondDeviceCommands()
    }

    sendEvent(name:"queryStatus", value:"Step 8/10 Getting Bond power cycle state...")
    if ( bondDevice.power_cycle_state == null ) {
        message = "Bond controller does not implement the power_cycle_state endpoint"
        log.info "${device.displayName}: queryBondAPI(): ${message}"
        device.updateDataValue( "bondPowerCycleState", message )
    } else {
        def bondPowerCycleState = getBondDevicePowerCycleState()
    }

    sendEvent(name:"queryStatus", value:"Step 9/10 Getting Bond remote address and learn data...")
    if ( bondDevice.addr == null ) {
        message = "Bond controller does not implement the addr endpoint"
        log.info "${device.displayName}: queryBondAPI(): ${message}"
        device.updateDataValue( "bondAddr", message )
    } else {
        def bondAddr = getBondDeviceRemoteAddressAndLearn()
    }

    sendEvent(name:"queryStatus", value:"Step 10/10 Query complete -<br>REFRESH the page.")
    device.updateDataValue( "lastBondApiQuery", new Date().format("MM/dd/yyyy HH:mm:ss '('ZZZZZ/zzz')'") )
}

String getMyBondId() {
    return parent.getBondIdFromDevice( device )
}

void configure() {
    def myId = getMyBondId()
    def max = getMaxSpeed( myId )
    if ( max != null ) {
        sendEvent( name: 'bondFanMaxSpeed', value: max )
    } else {
        log.error "${device.displayName}: configure() failed to get fan max speed"
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
    
    def dataValues = device.getData()
    String[] dvalues = []
    dataValues.each { key, val ->
        dvalues = dvalues + key
    }
    dvalues.each { val -> device.removeDataValue( val ) }
    log.info "${device.displayName}: Cleared state and device data"
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
            logDebug "getMaxSpeed(): got max_speed from fanProperties"
        } catch ( err ) {
            log.warn "${device.displayName}: getMaxSpeed(): parent.state.fanProperties get max_speed failed (${err}), getting fan properties directly..."
            fallBack = 1
        }
    } else {
        log.warn "${device.displayName}: getMaxSpeed(): parent.state.fanProperties is null, getting fan properties directly..."
        fallBack = 1
    }
    
    if ( fallBack ) {
        def myId = getMyBondId()
        def bondProperties = getBondDeviceProperties( myId )
        if ( bondProperties == null ) {
            log.warn "${device.displayName}: getMaxSpeed(): getBondDeviceProperties failed, guessing max_speed is 3"
        } else {
            if ( bondProperties.max_speed == null ) {
                log.warn "${device.displayName}: getMaxSpeed(): bondProperties.max_speed is null, guessing max_speed is 3"
            } else {
                maxSpeedN = bondProperties.max_speed
                logDebug "getMaxSpeed(): got max_speed from bondProperties"
            }
        }
    }
    
    if ( maxSpeedN == null || maxSpeedN == 0 ) {
        log.warn "${device.displayName}: getMaxSpeed(): Failed to get max_speed from all sources, guessing 3"
        maxSpeedN = 3 /* just guess */
    }
    
    logDebug "getMaxSpeed(): Fan Max Speed = ${maxSpeedN}"
    return maxSpeedN
}

void loadSupportedFanSpeeds( int maxSpeedN ) {
    int curSpeedN = 0
    String[] fanSpeeds = []
    
    while ( curSpeedN < maxSpeedN ) {
        curSpeedN += 1
        newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, curSpeedN )
        logDebug "loadSupportedFanSpeeds() Found new speed: ${newSpeedS}"
        fanSpeeds = fanSpeeds + [ newSpeedS ]
    }
    if ( device.currentValue( "bondBreezeMode" ) != null )
        fanSpeeds =  fanSpeeds.reverse() + [ "auto" ]
    speedList = fanSpeeds.join( "," )
    logDebug "loadSupportedFanSpeeds() fanSpeeds = [${speedList},off,on]"
    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeeds + ["off", "on"]))
}

void queryDevice() {
    def bondDeviceId = getMyBondId()
    if ( bondDeviceId == null ) {
        log.warn "${device.displayName}: queryDevice(): ID Bond missing, running configure(). Try again"
        chkConfigure()
        return
    }
    def devState = parent.getState( bondDeviceId )
    if ( devState == null ) {
        log.warn "${device.displayName}: queryDevice(): parent.getstate( ${bondDeviceId} ) failed"
        return
    } else {
        strState = devState.toMapString()
        log.info "${device.displayName}: queryDevice(): Full Device State: ${strState}"
        devSpeed = devState.get( "speed" )
        devPower = devState.get( "power" )
        drvPower = device.currentValue("switch")
        drvSpeed = device.currentValue("speed")
        log.info "${device.displayName}: queryDevice(): Device Power: ${devPower}, Device Speed: ${devSpeed} Driver Power: ${drvPower}, Driver Speed: ${drvSpeed}"
    }
    queryBondAPI()
}

def setBreezeParameters( mean=50, var=50 ) {
    log.info "${device.displayName}: setBreezeParameters() Called, aveSpeed=${mean}, variability=${var}"
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
    log.info "${device.displayName}: setBreezeParameters() Setting Breeze Parameters (mode=${mode}, aveSpeed=${mean}, variability=${var})"
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
        log.warn "toggleBreeze(): bondBreezeSupport is null, ran configure(), try again."
        return
    }
    int bondBreezeSupport = device.currentValue( "bondBreezeSupport" ).toInteger()
    if ( bondBreezeSupport < 1 ) {
        log.warn "${device.displayName}: toggleBreeze(): Device does not support Breeze mode"
        return
    }
    def myId = getMyBondId()
    def curSpeed = device.currentValue( "speed" )
    def mode = 1
    log.info "${device.displayName}: toggleBreeze(${force})"
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
        sendEvent(name:"bondBreezeMode", value:"1")
    } else {
        off()
    }
}

void runAction( action="", parameters="" ) {
    def myId = getMyBondId()
    if ( action == "" ) {
        log.error "${device.displayName}: runAction(): No action provided"
        return
    }
    if ( parameters == "" )
        result = parent.executeAction( myId, action )
    else
        result = parent.executeAction( myId, action, parameters )
    log.info "${device.displayName}: runAction(): action: (${action}) parameters: (${parameters}) result: ${result}"
}

void on() {
    chkConfigure()
    parent.handleOn( device )
    if ( state.lastSpeed != null ) {
        parent.handleFanSpeed( device, state.lastSpeed )
    }
    devSpeedS = getDeviceSpeed()
    sendEvent( name:"speed", value:devSpeedS )
    log.info "${device.displayName}: Turned on"
}

void off() {
    parent.handleOff(device)
    parent.executeAction( getMyBondId(), "BreezeOff" )
    sendEvent(name:"bondBreezeMode", value:"0")
    log.info "${device.displayName}: Turned off"
}

void toggle() {
    if (device.currentValue("switch") == "on")
        off()
    else
        on()
}

void setSpeed( String speed ) {
    log.info "${device.displayName}: Setting speed to ${speed}"
    
    if (speed != "off" && speed != "on" && speed != "auto" )
        state.lastSpeed = speed
    
    if ( speed == "auto" ) {
        toggleBreeze( "on" )
        return
    } else if ( speed == "off" ) {
        off()
        return
    }
    if ( device.currentValue( "bondBreezeSupport" ) > 0 )
        parent.executeAction( getMyBondId(), "BreezeOff" )
    sendEvent(name:"bondBreezeMode", value:"0")
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
        log.warn "${device.displayName}: cycleSpeed() ID Bond missing, running Configure. Try again"
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
    logDebug "cycleSpeed(): Current: curSpeedN: ${curSpeedN}, newSpeedN: ${newSpeedN}, maxSpeedN: ${maxSpeedN}"
    newSpeedN = ( curSpeedN + 1 ) % maxSpeedN
    if ( newSpeedN == 0 ) {
        newSpeedN = maxSpeedN
    }
    newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, newSpeedN )
    logDebug "cycleSpeed(): New: curSpeedN: ${curSpeedN}, newSpeedN: ${newSpeedN}, maxSpeedN: ${maxSpeedN}, newSpeedS: ${newSpeedS}"
    setSpeed( newSpeedS )
}

void setDirection( direction ) {
    chkConfigure()
    if ( device.currentValue( "bondDirectionSupport" ) == null ) {
        log.warn "setDirection(): bondDirectionSupport is null, ran configure(), try again."
        return
    }
    int bondDirectionSupport = device.currentValue( "bondDirectionSupport" ).toInteger()
    logDebug "setDirection(): Bond Direction Support: ${bondDirectionSupport}"
    if ( bondDirectionSupport > 0 ) {
        logDebug "setDirection(): Calling handleDirection( ${device}, ${direction} )"
        parent.handleDirection( device, direction )
    } else {
        log.warn "setDirection(): Device does not seem to support direction through the Bond API"
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