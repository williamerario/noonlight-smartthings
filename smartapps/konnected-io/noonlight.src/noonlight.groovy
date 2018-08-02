/**
 *  Noonlight
 *
 *  Copyright 2018 Nate Clark, Konnected Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.time.TimeCategory
import groovy.json.JsonOutput

public static String version() { return "0.1.0" }
public static String noonlightApiUri() { return "https://api-sandbox.safetrek.io/v1/alarms" }

definition(
    name: "Noonlight",
    namespace: "konnected-io",
    author: "Nate Clark, Konnected Inc",
    description: "Break-in? Fire? Medical emergency? Noonlight will send the right emergency help, 24/7.",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/konnected-noonlight/noonlight-symbol-white1x.png",
    iconX2Url: "https://s3.amazonaws.com/konnected-noonlight/noonlight-symbol-white2x.png",
    iconX3Url: "https://s3.amazonaws.com/konnected-noonlight/noonlight-symbol-white3x.png")

mappings {
  path("/noonlight/token") { action: [ POST: "updateNoonlightToken"] }
}

preferences {
  page(name: "pageConfiguration", install: true, uninstall: true, content: "pageConfiguration")
}

def pageConfiguration() {
  if(!state.accessToken) { createAccessToken() }
    if(!validNoonlightToken()) {
      dynamicPage(name: "pageConfiguration") {
        section {
          href(
            name:        "oauth_init",
            image:       "https://s3.amazonaws.com/konnected-noonlight/noonlight-symbol-white2x.png",
            title:       "Connect Noonlight",
            description: "Sign in or sign up to get started",
            url:         "https://konnected-noonlight.herokuapp.com/st/auth/?app_id=${app.id}&api_host=${apiServerUrl}&access_token=${state.accessToken}",
            style: "embedded"
          )
        }
      }
  } else {
    dynamicPage(name: "pageConfiguration") {
      section {
        paragraph("You are connected to Noonlight!",
          image:       "https://s3.amazonaws.com/konnected-noonlight/noonlight-symbol-white2x.png")
      }

      section("Noonlight will receive information from these devices when an alarm is triggered") {
        input "contactSensors", "capability.contactSensor", title: "Doors & Windows", multiple: true, required: false
        input "motionSensors", "capability.motionSensor", title: "Motion Sensors", multiple: true, required: false
        input "smokeDetectors", "capability.smokeDetector", title: "Smoke Detectors", multiple: true, required: false
        input "coDetectors", "capability.carbonMonoxideDetector", title: "Carbon Monoxide Detectors", multiple: true, required: false
        input "tempSensors", "capability.temperatureMeasurement", title: "Temperature Sensors", multiple: true, required: false
      }

      section("Share Presence with Noonlight when there's an emergency so we know who is home") {
        input "presenceSensors", "capability.presenceSensor", title: "Presence sensor(s)", multiple: true, required: false
      }

      section("What's next?") {
        paragraph "Configure Smart Home Monitor to alert Noonlight in an emergency. Go to Smart Home Monitor > Configure > Security and/or Smoke > Alert with lights > Noonlight"
      }

      section("How does Noonlight work?") {
        paragraph "Immediately after an alarm is triggered from your smart device, a certified Noonlight dispatcher will receive information about your smart home devices and will attempt to contact you. If there's an emergency, Noonlight will send the appropriate first responders to your home. (Be sure to check your home location in Location Settings.)"
        paragraph "False alarm? Simply cancel with your 4-digit Noonlight PIN when contacted."
      }

      section("About") {
        paragraph "This integration was created by Konnected and is powered by Noonlight."
        href(title: "Learn more at noonlight.com", url: "https://noonlight.com")
        paragraph "Noonlight SmartApp v${version()}"
      }
    }
  }
}

def installed() {
  log.debug "Installed with settings: ${settings}"

  initialize()
}

def updated() {
  log.debug "Updated with settings: ${settings}"

  unsubscribe()
  initialize()
}

def initialize() {
  runEvery5Minutes(validNoonlightToken)
  validNoonlightToken()
  childDeviceConfiguration()
}

def updateNoonlightToken() {
  log.debug "Updating Noonlight Token"
  state.noonlightToken = request.JSON.token
  state.noonlightTokenExpires = request.JSON.expires
  return
}

def createAlarm() {
  def alarm_attributes = [
    uri: noonlightApiUri(),
    body: [
      'location.coordinates': [ lat: location.getLatitude(), lng: location.getLongitude(), accuracy: 5 ],
    ],
    headers: ['Authorization': "Bearer ${state.noonlightToken}"]
  ]

  log.debug JsonOutput.toJson(alarm_attributes.body)

  try {
    httpPostJson(alarm_attributes) { response ->
      log.debug "Noonlight response: $response.data"
      if (response.status >= 200 && response.status < 300) {
        processNoonlightResponse(response.data)
      } else {
        sendPush("An error occurred connecting to Noonlight and your alert was not sent. If this is a real emergency, call 9-1-1.")
      }
    }
  } catch(e) {
    log.error "something went wrong: $e"
  }
}

def cancelAlarm() {
  def alarm_id = state.currentAlarm
  def alarm_attributes = [
    uri: "${noonlightApiUri()}/$alarm_id/status",
    body: [ status: "CANCELED" ],
    headers: ['Authorization': "Bearer ${state.noonlightToken}"]
  ]
  try {
    httpPutJson(alarm_attributes) { response ->
      log.debug response.data
      if (response.data.status == 200) {
        state.currentAlarm = null
        getChildDevice("noonlight").switchOff()
      }
    }
  } catch(e) {
    log.error "$e"
  }
}

def processNoonlightResponse(data) {
  if (data.status == 'ACTIVE') {
    state.currentAlarm = data.id
    getChildDevice("noonlight")?.switchOn()
    sendEventsToNoonlight()
  }
}

def sendEventsToNoonlight() {
  def currentStates = collectCurrentStates()
  def recentEvents = collectRecentEvents()
}

def validNoonlightToken() {
  if (state.noonlightToken) {
    def expire_date = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", state.noonlightTokenExpires)
    def expires_in = (expire_date.time - new Date().time)
    if (expires_in > 0) {
    log.debug "Noonlight token is valid for $expires_in milliseconds"
      return true
    } else {
      log.debug "Noonlight token has expired!"
      return false
    }
  } else {
    log.debug "Noonlight token is not set!"
    return false
  }
}

def childDeviceConfiguration() {
  if (!getChildDevice("noonlight")) {
    addChildDevice("konnected-io", 'Noonlight Alarm', "noonlight", null, [label: "Noonlight", completedSetup: true])
  }
}

def payloadFor(device, attr) {
  def st = device.currentState(attr)
  return [
  	timestamp: st.date,
    device_id: device.id,
    device_model: device.modelName,
    device_manufacturer: device.manufacturerName,
  	device_name: device.displayName,
    attribute: attr,
    value: st.value,
    unit: st.unit
  ]
}

def collectCurrentStates() {
  return motionSensors.collect{ payloadFor(it, 'motion') } +
      contactSensors.collect{ payloadFor(it, 'contact') } +
      smokeDetectors.collect{ payloadFor(it, 'smoke') } +
      coDetectors.collect{ payloadFor(it, 'carbonMonoxide') } +
      tempSensors.collect{ payloadFor(it, 'temperature') } +
      presenceSensors.collect{ payloadFor(it, 'presence') }
}

def collectRecentEvents() {
  def fiveMinutesAgo = new Date()
  use(TimeCategory) {
    fiveMinutesAgo = fiveMinutesAgo - 5.minutes
  }

  def allEvents = motionSensors.eventsSince(fiveMinutesAgo) +
    contactSensors.eventsSince(fiveMinutesAgo) +
    smokeDetectors.eventsSince(fiveMinutesAgo) +
    tempSensors.eventsSince(fiveMinutesAgo) +
    presenceSensors.eventsSince(fiveMinutesAgo)

  return allEvents.flatten().findAll { it.isStateChange() }.collect {
  	[
      timestamp: it.date,
      device_id: it.deviceId,
      device_model: it.device.modelName,
      device_manufacturer: it.device.manufacturerName,
      device_name: it.displayName,
      attribute: it.name,
      value: it.value,
      unit: it.unit
    ]
  }
}
