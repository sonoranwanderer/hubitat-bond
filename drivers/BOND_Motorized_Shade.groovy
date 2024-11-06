/**
 *  BOND Motorized Shade
 *
 *  Copyright 2019-2020 Dominick Meglio
 *  Additional copyright 2024 @terminal3
 *
 *  20241024 - added lower and raise commands 
 *
 */

import groovy.transform.Field
@Field static final String VERSION   = "202411060930"
@Field static final String DRIVER    = "Bond Motorized Shade"
@Field static final String COMM_LINK = "https://github.com/sonoranwanderer/hubitat-bond"

metadata {
    definition (
        name: "BOND Motorized Shade", 
        namespace: "bond", 
        author: "gatewoodgreen@gmail.com",
        importUrl: "https://raw.githubusercontent.com/sonoranwanderer/hubitat-bond/master/drivers/BOND_Motorized_Shade.groovy"
    ) {
        capability "WindowShade"
        capability "Switch"
        capability "Initialize"

        command "stop"
        command "fixShade", [[name:"Shade*", type: "ENUM", description: "Shade", constraints: ["open","close"] ] ]
        command "toggle"
        command "lower"
        command "raise"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo("Bond Driver Version")
        input name: "logLevel", type: "enum",   title: "Logging Level", defaultValue: 3, options: [3: "info", 2:"debug", 1:"trace"], required: true
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

def installed() {
    logEvent( "installed(): ${DRIVER}: ${VERSION}", "info" )
}

def updated() {
    logEvent( "updated(): ${DRIVER}: ${VERSION}", "info" )
}

def initialize() {
    logEvent( "initialize(): ${DRIVER}: ${VERSION}", "info" )
}

String fmtHelpInfo(String str) {
	String info = "${DRIVER}: ${VERSION}".trim()
	String prefLink = "<a href='${COMM_LINK}' target='_blank'>${str}<br><div style='font-size: 70%;'>${info}</div></a>"
	String topStyle = "style='font-size: 18px; padding: 1px 12px; border: 2px solid Crimson; border-radius: 6px;'" //SlateGray
	String topLink = "<a ${topStyle} href='${COMM_LINK}' target='_blank'>${str}<br><div style='font-size: 14px;'>${info}</div></a>"

	return "<div style='font-size: 160%; font-style: bold; padding: 2px 0px; text-align: center;'>${prefLink}</div>" +
		"<div style='text-align: center; position: absolute; top: 46px; right: 60px; padding: 0px;'><ul class='nav'><li>${topLink}</ul></li></div>"
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