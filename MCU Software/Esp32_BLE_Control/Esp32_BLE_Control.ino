#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <RTClib.h>
#include <extEEPROM.h>
#include <DHT22.h>
#include <MHZ19.h>
#include <MQ131.h>
#include <PMS.h>
#include <HardwareSerial.h>

// ---------------------- BLE CONFIG ----------------------
#define SERVICE_UUID            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_RX_UUID   "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_TX_UUID   "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLECharacteristic* txCharacteristic = nullptr;
BLEAdvertising* advertising = nullptr;
BLEServer* server = nullptr;
bool deviceConnected = false;

// ---------------------- PINS AND VALUES ----------------------
#define REC_PIN         2
#define INPUT_PIN       4
#define DHT_PIN         14
#define MHZ19_RX_PIN    26
#define MHZ19_TX_PIN    25
#define PMS_RX_PIN      16
#define PMS_TX_PIN      17
#define RED_LED_PIN      15
#define MQ131_ANALOG_PIN 35
#define ozoneThreshold  9999999

// ---------------------- SENSORS ----------------------
HardwareSerial mhz19Serial(1);
HardwareSerial pmsSerial(2);
DHT22 dht(DHT_PIN);
MHZ19 co2Sensor;
PMS pms(pmsSerial);
PMS::DATA pmsData;
float temperature = 0, humidity = 0;
int co2_uart = 0;
uint16_t pm1 = 0, pm2_5 = 0, pm10 = 0;
int ozone_ppb = 0;

// ---------------------- EEPROM & RTC ----------------------
#define MAX_SCHEDULES 7
RTC_DS3231 rtc;
extEEPROM extEEPROM(kbits_32, 1, 32, 0x57);
struct Schedule {
  uint8_t dayOfWeek;
  uint8_t startHour;
  uint8_t startMinute;
  uint8_t endHour;
  uint8_t endMinute;
  bool enabled;
};
Schedule schedules[MAX_SCHEDULES];

// ---------------------- GLOBALS ----------------------
String inputBuffer = "";
bool isActive = false;
bool isPaused = false;
bool isManualOn = false;

// ---------------------- HELPER FUNCTIONS ----------------------
int dayStringToNumber(String day) {
  day.toLowerCase();
  const char* days[] = { "sun", "mon", "tue", "wed", "thu", "fri", "sat" };
  for (int i = 0; i < 7; i++) {
    if (day.startsWith(days[i])) return i;
  }
  return -1;
}

void saveSchedulesToEEPROM() {
  int address = 0;
  for (int i = 0; i < MAX_SCHEDULES; i++) {
    extEEPROM.write(address, (byte*)&schedules[i], sizeof(Schedule));
    address += sizeof(Schedule);
  }
  Serial.println("Schedules saved.");
}

void loadSchedulesFromEEPROM() {
  int address = 0;
  for (int i = 0; i < MAX_SCHEDULES; i++) {
    extEEPROM.read(address, (byte*)&schedules[i], sizeof(Schedule));
    address += sizeof(Schedule);
  }
  Serial.println("Schedules loaded.");
}

void clearSchedules() {
  for (int i = 0; i < MAX_SCHEDULES; i++) {
    schedules[i] = { 0xFF, 0, 0, 0, 0, false };
  }
  saveSchedulesToEEPROM();
  Serial.println("Schedules cleared.");
}

String printSchedules() {
  const char* days[] = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
  String output = "Schedules:\n";
  for (int i = 0; i < MAX_SCHEDULES; i++) {
    if (schedules[i].dayOfWeek == 0xFF) continue;
    String line = "ID:" + String(i) + "|" + String(days[schedules[i].dayOfWeek]) + "|";
    if (schedules[i].startHour < 10) line += "0";
    line += String(schedules[i].startHour) + ":";
    if (schedules[i].startMinute < 10) line += "0";
    line += String(schedules[i].startMinute) + "|";
    int startMinutes = schedules[i].startHour * 60 + schedules[i].startMinute;
    int endMinutes = schedules[i].endHour * 60 + schedules[i].endMinute;
    int duration = endMinutes - startMinutes;
    line += String(duration) + "|" + (schedules[i].enabled ? "ON" : "OFF");
    output += line + "\n";
  }
  Serial.print(output);
  return output;
}

void parseLine(String line) {
  line.trim();
  if (line == "") return;

  if (line == "SHOW") {
    String scheduleOutput = printSchedules();
    if (deviceConnected && txCharacteristic != nullptr) {
      txCharacteristic->setValue(scheduleOutput.c_str());
      txCharacteristic->notify();
    }
    return;
  }
  if (line == "CLEAR") {
    clearSchedules();
    return;
  }
  if (line == "SAVE") {
    saveSchedulesToEEPROM();
    return;
  }
  if (line == "NOW") {
    DateTime now = rtc.now();
    Serial.printf("Current Time: %02d:%02d:%02d | Day: %d\n",
                  now.hour(), now.minute(), now.second(), now.dayOfTheWeek());
    return;
  }
  if (line.startsWith("MANUALTOGGLE|")) {
    String cmd = line.substring(String("MANUALTOGGLE|").length());
    isManualOn = (cmd == "ON");
    return;
  }
  if (line.startsWith("TIMESET|")) {
    int first = line.indexOf('|');
    int second = line.indexOf('|', first + 1);
    if (first == -1 || second == -1) {
      Serial.println("Invalid TIMESET format.");
      return;
    }
    String dateStr = line.substring(first + 1, second);
    String timeStr = line.substring(second + 1);
    int y = dateStr.substring(0, 4).toInt();
    int m = dateStr.substring(5, 7).toInt();
    int d = dateStr.substring(8, 10).toInt();
    int hr = timeStr.substring(0, 2).toInt();
    int min = timeStr.substring(3, 5).toInt();
    int sec = timeStr.substring(6, 8).toInt();
    rtc.adjust(DateTime(y, m, d, hr, min, sec));
    Serial.println("RTC time updated.");
    return;
  }
  if (line.startsWith("SETSCHEDULE|")) {
    String params = line.substring(String("SETSCHEDULE|").length());
    int pipe1 = params.indexOf('|');
    int pipe2 = params.indexOf('|', pipe1 + 1);
    int pipe3 = params.indexOf('|', pipe2 + 1);
    if (pipe1 == -1 || pipe2 == -1 || pipe3 == -1) {
      Serial.println("Invalid SETSCHEDULE format.");
      return;
    }
    String dayStr = params.substring(0, pipe1);
    String timeStr = params.substring(pipe1 + 1, pipe2);
    int duration = params.substring(pipe2 + 1, pipe3).toInt();
    bool enabled = (params.substring(pipe3 + 1) == "ON");
    int dayIndex = dayStringToNumber(dayStr);
    if (dayIndex == -1) {
      Serial.println("Invalid day.");
      return;
    }
    int colon = timeStr.indexOf(':');
    int sh = timeStr.substring(0, colon).toInt();
    int sm = timeStr.substring(colon + 1).toInt();
    int emins = sh * 60 + sm + duration;
    int eh = emins / 60;
    int em = emins % 60;
    Schedule s = { (uint8_t)dayIndex, (uint8_t)sh, (uint8_t)sm, (uint8_t)eh, (uint8_t)em, enabled };
    bool replaced = false;
    for (int i = 0; i < MAX_SCHEDULES; i++) {
      if (schedules[i].dayOfWeek == dayIndex) {
        schedules[i] = s;
        replaced = true;
        Serial.println("Schedule updated.");
        break;
      }
    }
    if (!replaced) {
      for (int i = 0; i < MAX_SCHEDULES; i++) {
        if (schedules[i].dayOfWeek == 0xFF) {
          schedules[i] = s;
          Serial.println("Schedule added.");
          replaced = true;
          break;
        }
      }
    }
    return;
  }
}
// ---------------------- SENSOR FUNCTIONS ----------------------
void readDHTSensor() {
  temperature = dht.getTemperature();
  humidity = dht.getHumidity();
  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("Error reading DHT22 sensor!");
    temperature = 0;
    humidity = 0;
  }
}

void readMHZ19Sensor() {
  co2_uart = co2Sensor.getCO2();
  if (co2_uart <= 0) {
    Serial.println("MH-Z19E: Failed to read CO2");
    co2_uart = 0;
  }
}

void readPMS7003Sensor() {
  pms.wakeUp();
  if (pms.readUntil(pmsData)) {
    pm1 = pmsData.PM_AE_UG_1_0;
    pm2_5 = pmsData.PM_AE_UG_2_5;
    pm10 = pmsData.PM_AE_UG_10_0;
  } else {
    Serial.println("PMS7003: Failed to read data.");
    pm1 = pm2_5 = pm10 = 0;
  }
}

void readMQ131Sensor() {
  MQ131.sample();
  ozone_ppb = MQ131.getO3(PPB);
  if (ozone_ppb < 0) {
    Serial.println("MQ131: Failed to read ozone.");
    ozone_ppb = 0;
  }
  isPaused = (ozone_ppb >= ozoneThreshold);
}

void readAllSensors() {
  readDHTSensor();
  readMHZ19Sensor();
  readPMS7003Sensor();
  readMQ131Sensor();
}

// ---------------------- BLE SEND FUNCTION ----------------------
void sendMessage() {
  String sensorMsg = "SensorInfo:\n";
  sensorMsg += "Temp:" + String(temperature, 1) + " C\n";
  sensorMsg += "Hum:" + String(humidity, 1) + " %\n";
  sensorMsg += "CO2:" + String(co2_uart) + " ppm\n";
    sensorMsg += "PMS7003:PM1: " + String(pm1) + " | PM25: " + String(pm2_5) + " | PM10: " + String(pm10) + "\n";
  sensorMsg += "O3:" + String(ozone_ppb) + " ppb";
  
  Serial.println("__________________________");
  Serial.println(sensorMsg);
  
  if (deviceConnected && txCharacteristic != nullptr) {
    String statusMsg = "RecStatus:" + String(isPaused ? "PAUSED" : (isActive ? "HIGH" : "LOW"));
    txCharacteristic->setValue(statusMsg.c_str());
    txCharacteristic->notify();
    
    // You may combine messages into a single notification if desired:
    txCharacteristic->setValue(sensorMsg.c_str());
    txCharacteristic->notify();
  }
}

// ---------------------- SCHEDULE CHECK FUNCTION ----------------------
void checkState() {
  DateTime now = rtc.now();
  int currentDay = now.dayOfTheWeek();
  int currentMinutes = now.hour() * 60 + now.minute();
  isActive = false;
  for (int i = 0; i < MAX_SCHEDULES; i++) {
    Schedule s = schedules[i];
    if (!s.enabled || s.dayOfWeek != currentDay) continue;
    int start = s.startHour * 60 + s.startMinute;
    int end = s.endHour * 60 + s.endMinute;
    if (currentMinutes >= start && currentMinutes < end) {
      isActive = true;
      break;
    }
  }
  if (isManualOn) isActive = true;
  
  digitalWrite(REC_PIN, isPaused ? LOW : (isActive ? HIGH : LOW));
  digitalWrite(RED_LED_PIN,isPaused ? HIGH : (isActive ? LOW : HIGH));
}

// ---------------------- FREE RTOS TASKS ----------------------
void sendMessageTask(void * parameter) {
  TickType_t xLastWakeTime = xTaskGetTickCount();
  const TickType_t interval = pdMS_TO_TICKS(1500);
  for (;;) {
    sendMessage();
    vTaskDelayUntil(&xLastWakeTime, interval);
  }
}

void sensorTask(void * parameter) {
  TickType_t xLastWakeTime = xTaskGetTickCount();
  const TickType_t interval = pdMS_TO_TICKS(2000);
  for (;;) {
    readAllSensors();
    vTaskDelayUntil(&xLastWakeTime, interval);
  }
}

void checkStateTask(void * parameter) {
  TickType_t xLastWakeTime = xTaskGetTickCount();
  const TickType_t interval = pdMS_TO_TICKS(250);
  for (;;) {
    checkState();
    vTaskDelayUntil(&xLastWakeTime, interval);
  }
}

// ---------------------- BLE CALLBACK CLASSES ----------------------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("BLE client connected.");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("BLE client disconnected.");
    advertising->start();
  }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String rxData = characteristic->getValue().c_str();
    Serial.println("BLE RX: " + rxData);
    parseLine(rxData);
  }
};

// ---------------------- SETUP ----------------------
void setup() {
  Serial.begin(115200);
  Wire.begin();
  pinMode(REC_PIN, OUTPUT);
  pinMode(RED_LED_PIN, OUTPUT);
  pinMode(INPUT_PIN, INPUT);

  // BLE initialization
  BLEDevice::init("FblsRcrByPV_id:1");
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(SERVICE_UUID);
  
  BLECharacteristic* rxChar = service->createCharacteristic(
    CHARACTERISTIC_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  rxChar->setCallbacks(new RxCallbacks());
  
  txCharacteristic = service->createCharacteristic(
    CHARACTERISTIC_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  txCharacteristic->addDescriptor(new BLE2902());
  
  service->start();
  advertising = server->getAdvertising();
  advertising->start();
  Serial.println("BLE ready.");
  
  extEEPROM.begin();
  rtc.begin();
  loadSchedulesFromEEPROM();
  
  mhz19Serial.begin(9600, SERIAL_8N1, MHZ19_RX_PIN, MHZ19_TX_PIN);
  pmsSerial.begin(9600, SERIAL_8N1, PMS_RX_PIN, PMS_TX_PIN);
  co2Sensor.begin(mhz19Serial);
  co2Sensor.autoCalibration(false);
  MQ131.begin(-1, MQ131_ANALOG_PIN, LOW_CONCENTRATION, 1000000);
  MQ131.setTimeToRead(5);
  MQ131.setR0(9000);
  

  xTaskCreatePinnedToCore(sendMessageTask, "SendMessageTask", 4096, NULL, 2, NULL, 0);
  // Sensor reading core 1
  xTaskCreatePinnedToCore(sensorTask, "SensorTask", 4096, NULL, 1, NULL, 1);
  xTaskCreatePinnedToCore(checkStateTask, "CheckStateTask", 4096, NULL, 2, NULL, 1);
  
  Serial.println("Defined serial commands:");
  Serial.println("Use SHOW to get the list of set schedules");
  Serial.println("Use CLEAR to wipe all set schedules");
  Serial.println("Use NOW to get the time set on the RTC module");
  Serial.println("Use SAVE to store schedules.");
  Serial.println("Use TIMESET to set the RTC time.");
  Serial.println("Example: TIMESET|2025-03-29|14:45:00");
  Serial.println("Use SETSCHEDULE to set a schedule for the day.");
  Serial.println("Example: SETSCHEDULE|Mon|07:00|60|ON");

}
// ---------------------- LOOP ---------------------- 
void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        parseLine(inputBuffer);
        inputBuffer = "";
      }
    } else {
      inputBuffer += c;
    }
  }
  delay(10);
}
