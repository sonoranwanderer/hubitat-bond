/**
 *  BOND Fan
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Additonal Copyright 2024 Gatewood Green
 *  Oct 12, 2024 - Implemented supportedFanSpeeds & cycleSpeed(), fixing Google Home integration breakage with Hubitat 2.3.9.192
 *  Oct 13, 2024 - Implmented auto configuration
 *  Oct 18, 2024 - Implmented breeze functionality and data/device debug support, aka queryDevice()
 *
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
        
        attribute "bondFanMaxSpeed", "integer"
        attribute "bondBreezeMode", "integer"
        attribute "bondBreezeAverage", "integer"
        attribute "bondBreezeVariability", "integer"

        command "configure"
        command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
        command "fixSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off","low", "medium-low", "medium", "medium-high", "high", "on"] ] ]
        command "toggle"
        command "queryDevice"
        command "wipeStateData"
        command "toggleBreeze"
        command "setBreezeParameters",[[name:"AverageSpeed*",type:"NUMBER", description:"Average Speed"],
                                       [name:"Variability*", type:"NUMBER", description:"Speed Variability"]]
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
            sendEvent(name:"bondBreezeMode", value:"${bondState.breeze[0]}")
            sendEvent(name:"bondBreezeAverage", value:"${bondState.breeze[1]}")
            sendEvent(name:"bondBreezeVariability", value:"${bondState.breeze[2]}")
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
        log.warn "${device.displayName}: getBondDeviceCommands(): Failed to get Bond commands data"
    }
    return bondCommands
}

def queryBondAPI() {
    sendEvent(name:"queryStatus", value:"Running configure()...")
    chkConfigure()
    sendEvent(name:"queryStatus", value:"Getting Bond version data...")
    getBondVersion()
    sendEvent(name:"queryStatus", value:"Getting Bond device data...")
    getBondDeviceData()
    sendEvent(name:"queryStatus", value:"Getting Bond device properties...")
    getBondDeviceProperties()
    sendEvent(name:"queryStatus", value:"Getting Bond device state...")
    getBondDeviceState()
    sendEvent(name:"queryStatus", value:"Getting Bond device actions...")
    getBondDeviceActions()
    sendEvent(name:"queryStatus", value:"Getting Bond device commands...")
    getBondDeviceCommands()
    sendEvent(name:"queryStatus", value:"Getting Bond power cycle state...")
    getBondDevicePowerCycleState()
    sendEvent(name:"queryStatus", value:"Getting Bond remote address and learn data...")
    getBondDeviceRemoteAddressAndLearn()
    sendEvent(name:"queryStatus", value:"Query complete -<br>REFRESH the page.")
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

void wipeStateData( int silent=0 ) {
    state.clear()
    
    device.deleteCurrentState( "bondBreezeMode" )
    device.deleteCurrentState( "bondFanMaxSpeed" )
    device.deleteCurrentState( "bondBreezeAverage" )
    device.deleteCurrentState( "bondBreezeVariability" )
    
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
    
    maxSpeedN = parent.state.fanProperties.get( devId ).get( "max_speed" )
    logDebug "getMaxSpeed(): Fan Properties = ${parent.state.fanProperties}"
    
    if ( maxSpeedN == null ) {
        log.warn "${device.displayName}: getMaxSpeed(): Failed to get max_speed from fanProperties"
        return null
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
    speedList = fanSpeeds.join( "," )
    logDebug "loadSupportedFanSpeeds() fanSpeeds = [${speedList},off,on]"
    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeeds.reverse() + ["auto", "off", "on"]))
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

void fixPower( power ) {
    parent.fixPowerState( device, power )
}

void fixSpeed( speed ) {
    parent.fixFanSpeed( device, speed )
}

/* Child (light) device support */

void handleLightOn(device) {
    parent.handleLightOn(device)
}

void handleLightOff(device) {
    parent.handleLightOff(device)
}

void handleLightLevel(device, level)
{
    parent.handleLightLevel(device, level)
}

void handleDim(device, duration) {
    parent.handleDim(device, duration)
}

void handleStartDimming(device) {
    parent.handleStartDimming(device)
}

void handleStopDimming(device) {
    parent.handleStopDimming(device)
}

void fixLightPower(device, power) {
    parent.fixLightPower(device, power)
}

void fixLightLevel(device, level) {
    parent.fixLightLevel(device, level)
}