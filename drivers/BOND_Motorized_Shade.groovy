/**
 *  BOND Motorized Shade
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Additional copyright 2024 @terminal3
 *
 *  20241024 - added lower and raise commands 
 *
 *  VERSION 202410242015
 */

metadata {
    definition (
        name: "BOND Motorized Shade", 
        namespace: "bond", 
        author: "dmeglio@gmail.com",
        importUrl: "https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/master/drivers/BOND_Motorized_Shade.groovy"
    ) {
        capability "WindowShade"
        capability "Switch"

        command "stop"
        command "fixShade", [[name:"Shade*", type: "ENUM", description: "Shade", constraints: ["open","close"] ] ]
        command "toggle"
        command "lower"
        command "raise"
    }
}

def open() {
    parent.handleOpen(device)
}

def raise() {
    parent.handleOpenNext(device)
}

def close() {
    parent.handleClose(device)
}

def lower() {
    parent.handleCloseNext(device)
}

def on() {
    open()
}

def off() {
    close()
}

def toggle() {
    if (device.currentValue("windowShade") == "open")
        close()
    else
        open()
}

def stop() {
    parent.handleStop(device)
}

def fixShade(shade) {
    parent.fixShadeState(device, shade)
}

def setPosition(Number position) {
    if (position == 0) {
        log.info "position special value 0 is set, trigger CLose command"
        close()
    } else if (position == 50) {
        log.info "position special value 50 is set, triggering Preset command"
        parent.handlePreset(device)
    } else if (position == 100) {
        log.info "position special value 100 is set, triggering Open command"
        open()
    } else {
        log.info "no-op for position value " + position + ", set position to 50 to trigger Preset command"
    }
}
