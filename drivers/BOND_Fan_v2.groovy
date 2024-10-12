/**
 *  BOND Fan
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Updated by Gatewood Green (10/2024)
 *
 */

metadata {
    definition (
		name: "BOND Fan v2", 
		namespace: "bond", 
		author: "dmeglio@gmail.com", 
		importUrl: "https://raw.githubusercontent.com/dcmeglio/hubitat-bond/master/drivers/BOND_Fan.groovy"
	) {
		capability "Switch"
        capability "FanControl"
		
		command "configure"
        command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
		command "fixSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off","low", "medium-low", "medium", "medium-high", "high", "on"] ] ]
		command "toggle"
    }
    
    preferences {
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

void logDebug(String msg) {
    if (debugEnable) {
        log.debug("${device.label?device.label:device.name}: ${msg}")
    }
}

def configure() {
    getSupportedFanSpeeds()
    state.remove("speed_num")
}

def getMaxSpeed() {
    int maxSpeedN = 0
    
    if ( parent.state.fanProperties == null ) {
        log.warn "${device.displayName}: getMaxSpeed(): parent.state.fanProperties = null"
        parent.getDevices()
        if ( parent.state.fanProperties != null ) {
            logDebug "getMaxSpeed(): fixed fanProperties"
        } else {
            return false
        }
    }
    logDebug "getMaxSpeed(): Fan Properties = ${parent.state.fanProperties}"
    
    if (( m = parent.state.fanProperties =~ /,\s*max_speed:(\d+)/ )) {
        max_speed_str = m.group(1)
        if (max_speed_str.isInteger()) {
            maxSpeedN = max_speed_str as Integer
        } else {
            log.warn "${device.displayName}: getMaxSpeed(): failed to convert max_speed_str (${max_speed_str}) to int"
            return false
        }
    } else {
        log.warn "${device.displayName}: getMaxSpeed(): Failed to get max_speed from fanProperties"
        return false
    }
    
    logDebug "getMaxSpeed(): Fan Max Speed = ${maxSpeedN}"
    state.maxSpeed = maxSpeedN
    return maxSpeedN
}

def getSupportedFanSpeeds() {
    int maxSpeedN = getMaxSpeed()
    curSpeedN = 0
    fanSpeeds = []
    
    while ( curSpeedN < maxSpeedN ) {
        curSpeedN += 1
        newSpeedS = parent.translateBondFanSpeedToHE( device, maxSpeedN, curSpeedN )
        logDebug "getSupportedFanSpeeds() Found new speed: ${newSpeedS}"
        fanSpeeds = fanSpeeds + [ newSpeedS ]
    }
    speedList = fanSpeeds.toListString()
    logDebug "getSupportedFanSpeeds() fanSpeeds = ${speedList}"
    sendEvent(name: "supportedFanSpeeds", value: groovy.json.JsonOutput.toJson(fanSpeeds.reverse() + ["off", "on"]))
}

def on() {
	parent.handleOn(device)
	if (state.lastSpeed != null)
	{
		parent.handleFanSpeed(device, state.lastSpeed)
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
        maxSpeedN = getMaxSpeed()
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