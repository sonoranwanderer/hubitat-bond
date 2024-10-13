/**
 *  BOND Fan
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Updated and additonal Copyright 2024 Gatewood Green
 *
 */

metadata {
    definition (
		name: "BOND Fan v2", 
		namespace: "bond", 
		author: "gatewoodgreen@gmail.com", 
		importUrl: "https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/refs/heads/master/drivers/BOND_Fan_v2.groovy"
	) {
        capability "Switch"
        capability "FanControl"

		command "configure"
        command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
        command "fixSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off","low", "medium-low", "medium", "medium-high", "high", "on"] ] ]
        command "toggle"
        command "getDeviceState"
        command "wipeStateData"
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

def getDeviceState() {
    logDebug "getDeviceState(): routine started"
    def devState = parent.getState( 1 )
    if ( devState == null ) {
        log.warn "${device.displayName}: getDeviceState(): parent.getstate() failed"
        return false
    } else {
        strState = devState.toMapString()
        log.info "${device.displayName}: getDeviceState(): Full Device State: ${strState}"
        devSpeed = devState.get( "speed" )
        devPower = devState.get( "power" )
        log.info "${device.displayName}: getDeviceState(): Power: ${devPower}, Speed: ${devSpeed}"
    }
}

def getMyBondId() {
    myId = 0
    if (  state.bondDeviceId != null ) {
        logDebug "getMyBondId(): returning existing state.bondDeviceId (${state.bondDeviceId})"
        return state.bondDeviceId
    } else {
        logDebug "getMyBondId(): state.bondDeviceId is null"
    }
    parent.state.fanList.each { key, val ->
        myLabel = device.label?device.label:device.name
        log.info "${device.displayName} getDeviceState(): key = ${key}, val = '${val}', label = '${myLabel}'"
        if ( val == myLabel ) {
            logDebug "getMyBondId(): Found my ID by name = ${key}"
            myId = key
        }
    }
    if ( ! myId ) {
        log.error "${device.displayName}: getMyBondId() Could not find my Bond ID"
        return null
    }
    return myId
}

def configure() {
    logDebug "configure(): Calling getMyBondId()"
    myId = getMyBondId()
    if ( myId != null ) {
        state.bondDeviceId = myId
    } else {
        log.error "${device.displayName}: configure() failed to get Bond device ID"
    }
    logDebug "configure(): Calling getMaxSpeed( ${myId} )"
    max = getMaxSpeed( myId )
    if ( max != null ) {
        state.maxSpeed = max
    } else {
        log.error "${device.displayName}: configure() failed to get fan max speed"
    }
    logDebug "configure(): Calling loadSupportedFanSpeeds( ${max} )"
    loadSupportedFanSpeeds( max )
}

def chkConfigure() {
    if ( state.maxSpeed == null || state.bondDeviceId == null ) {
        configure()
    }
}

def wipeStateData() {
    log.warn "${device.displayName}: Cleared state data"
    /* Need to write loop */
    state.remove( "bondDeviceId" )
    state.remove( "lastSpeed" )
    state.remove( "maxSpeed" )
}

def getMaxSpeed( devId ) {
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

def loadSupportedFanSpeeds( maxSpeedN ) {
    curSpeedN = 0
    fanSpeeds = []
    
    while ( curSpeedN < maxSpeedN ) {
        curSpeedN += 1
        newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, curSpeedN )
        logDebug "loadSupportedFanSpeeds() Found new speed: ${newSpeedS}"
        fanSpeeds = fanSpeeds + [ newSpeedS ]
    }
    speedList = fanSpeeds.toListString()
    logDebug "loadSupportedFanSpeeds() fanSpeeds = ${speedList}"
    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeeds.reverse() + ["off", "on"]))
}

def on() {
    chkConfigure()
    parent.handleOn( device )
    if ( state.lastSpeed != null ) {
        parent.handleFanSpeed( device, state.lastSpeed )
    }
    log.info "${device.displayName}: Turned on"
}

def off() {
	parent.handleOff(device)
    log.info "${device.displayName}: Turned off"
}

def toggle() {
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def setSpeed(speed) {
	if (speed != "off" && speed != "on")
		state.lastSpeed = speed
    parent.handleFanSpeed(device, speed)
    log.info "${device.displayName}: Set speed to ${speed}"
}

def cycleSpeed() {
    int maxSpeedN = 0
    int curSpeedN = 0
    int newSpeedN = 0
    curSpeedS = ""
    newSpeedS = "low"

    /* Cut down on Hubitat load */
    if ( state.maxSpeed != null ) {
        maxSpeedN = state.maxSpeed
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

def fixPower(power) {
	parent.fixPowerState(device, power)
}

def fixSpeed(speed) {
	parent.fixFanSpeed(device, speed)
}

/* Child (light) device support */

def handleLightOn(device) {
    parent.handleLightOn(device)
}

def handleLightOff(device) {
    parent.handleLightOff(device)
}

def handleLightLevel(device, level)
{
	parent.handleLightLevel(device, level)
}

def handleDim(device, duration) {
	parent.handleDim(device, duration)
}

def handleStartDimming(device) {
	parent.handleStartDimming(device)
}

def handleStopDimming(device) {
	parent.handleStopDimming(device)
}

def fixLightPower(device, power) {
	parent.fixLightPower(device, power)
}

def fixLightLevel(device, level) {
	parent.fixLightLevel(device, level)
}