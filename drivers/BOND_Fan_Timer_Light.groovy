/**
 *  BOND Fan Timer Light
 *
 *  Copyright 2019 Dominick Meglio
 *
 */

metadata {
    definition (
        name: "BOND Fan Timer Light", 
        namespace: "bond", 
        author: "dmeglio@gmail.com",
        importUrl: "https://github.com/sonoranwanderer/hubitat-bond/raw/refs/heads/master/drivers/BOND_Fan_Timer_Light.groovy"
    ) {
        capability "Switch"
        capability "Light"


        command "dim", ["number"]
        command "startDimming"
        command "stopDimming"

        command "fixPower", [[name:"Power*", type: "ENUM", description: "Power", constraints: ["off","on"] ] ]
        command "toggle"
    }
}

def dim(duration) {
    parent.handleDim(device, duration)
}

def startDimming() {
    parent.handleStartDimming(device)
}

def stopDimming() {
    parent.handleStopDimming(device)
}

def on() {
    parent.handleLightOn(device)
}

def off() {
    parent.handleLightOff(device)
}

def toggle() {
    if (device.currentValue("switch") == "on")
        off()
    else
        on()
}

def installed() {
    sendEvent(name: "numberOfButtons", value: "1")
}

def updated() {
    sendEvent(name: "numberOfButtons", value: "1")
}

def fixPower(power) {
    parent.fixLightPower(device, power)
}
