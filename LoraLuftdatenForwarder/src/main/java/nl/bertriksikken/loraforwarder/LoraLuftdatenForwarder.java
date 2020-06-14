package nl.bertriksikken.loraforwarder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.apache.log4j.PropertyConfigurator;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import nl.bertriksikken.loraforwarder.rudzl.dto.RudzlMessage;
import nl.bertriksikken.loraforwarder.ttnulm.PayloadParseException;
import nl.bertriksikken.loraforwarder.ttnulm.TtnCayenneMessage;
import nl.bertriksikken.loraforwarder.ttnulm.TtnUlmMessage;
import nl.bertriksikken.luftdaten.ILuftdatenApi;
import nl.bertriksikken.luftdaten.LuftdatenConfig;
import nl.bertriksikken.luftdaten.LuftdatenUploader;
import nl.bertriksikken.opensense.IOpenSenseRestApi;
import nl.bertriksikken.opensense.OpenSenseConfig;
import nl.bertriksikken.opensense.OpenSenseUploader;
import nl.bertriksikken.pm.ESensorItem;
import nl.bertriksikken.pm.SensorData;
import nl.bertriksikken.ttn.MqttListener;
import nl.bertriksikken.ttn.TtnAppConfig;
import nl.bertriksikken.ttn.TtnConfig;
import nl.bertriksikken.ttn.dto.TtnUplinkMessage;

public final class LoraLuftdatenForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(LoraLuftdatenForwarder.class);
    private static final String CONFIG_FILE = "loraluftdatenforwarder.yaml";

    private final MqttListener mqttListener;
    private final LuftdatenUploader luftdatenUploader;
    private final OpenSenseUploader openSenseUploader;
    private final EPayloadEncoding encoding;

    public static void main(String[] args) throws IOException, MqttException {
        PropertyConfigurator.configure("log4j.properties");

        LoraForwarderConfig config = readConfig(new File(CONFIG_FILE));
        LoraLuftdatenForwarder app = new LoraLuftdatenForwarder(config);
        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }

    private LoraLuftdatenForwarder(LoraForwarderConfig config) {
        LuftdatenConfig luftdatenConfig = config.getLuftdatenConfig();
        ILuftdatenApi restClient = LuftdatenUploader.newRestClient(luftdatenConfig.getUrl(),
                Duration.ofSeconds(luftdatenConfig.getTimeout()));
        luftdatenUploader = new LuftdatenUploader(restClient);

        OpenSenseConfig openSenseConfig = config.getOpenSenseConfig();
        IOpenSenseRestApi openSenseClient = OpenSenseUploader.newRestClient(openSenseConfig.getUrl(),
                Duration.ofSeconds(openSenseConfig.getTimeoutSec()));
        openSenseUploader = new OpenSenseUploader(config.getOpenSenseConfig().getIds(), openSenseClient);

        TtnConfig ttnConfig = config.getTtnConfig();
        TtnAppConfig ttnAppConfig = config.getTtnConfig().getApps().get(0);
        encoding = ttnAppConfig.getEncoding();

        mqttListener = new MqttListener(this::messageReceived, ttnConfig.getUrl(), ttnAppConfig.getName(),
                ttnAppConfig.getKey());

        LOG.info("Created new Luftdaten forwarder for encoding {}", encoding);
    }

    private void messageReceived(String topic, String message) {
        Instant instant = Instant.now();
        
        LOG.info("Received: '{}'", message);

        // decode JSON
        ObjectMapper mapper = new ObjectMapper();
        TtnUplinkMessage uplink;
        try {
            uplink = mapper.readValue(message, TtnUplinkMessage.class);
        } catch (IOException e) {
            LOG.warn("Could not parse JSON: '{}'", message);
            return;
        }
        String sensorId = "TTN-" + uplink.getHardwareSerial();

        // decode and upload
        try {
            SensorData sensorData = decodeTtnMessage(instant, sensorId, uplink);
            luftdatenUploader.scheduleUpload(sensorId, sensorData);
            openSenseUploader.scheduleUpload(uplink.getHardwareSerial(), sensorData);
        } catch (PayloadParseException e) {
            LOG.warn("Could not parse payload from: '{}", uplink);
        }
    }

    // package-private to allow testing
    SensorData decodeTtnMessage(Instant instant, String sensorId, TtnUplinkMessage uplinkMessage)
            throws PayloadParseException {
        SensorData sensorData = new SensorData();

        switch (encoding) {
        case RUDZL:
            RudzlMessage rudzlMessage = new RudzlMessage(uplinkMessage.getPayloadFields());
            sensorData.addValue(ESensorItem.PM10, rudzlMessage.getPM10());
            sensorData.addValue(ESensorItem.PM2_5, rudzlMessage.getPM2_5());
            sensorData.addValue(ESensorItem.HUMI, rudzlMessage.getRH());
            sensorData.addValue(ESensorItem.TEMP, rudzlMessage.getT());
            sensorData.addValue(ESensorItem.PRESSURE, rudzlMessage.getP() * 100.0); // mBar to Pa
            break;
        case TTN_ULM:
            TtnUlmMessage ulmMessage = new TtnUlmMessage();
            ulmMessage.parse(uplinkMessage.getRawPayload());
            sensorData.addValue(ESensorItem.PM10, ulmMessage.getPm10());
            sensorData.addValue(ESensorItem.PM2_5, ulmMessage.getPm2_5());
            sensorData.addValue(ESensorItem.HUMI, ulmMessage.getRhPerc());
            sensorData.addValue(ESensorItem.TEMP, ulmMessage.getTempC());
            break;
        case CAYENNE:
            TtnCayenneMessage cayenne = new TtnCayenneMessage();
            cayenne.parse(uplinkMessage.getRawPayload());
            if (cayenne.hasPm10()) {
                sensorData.addValue(ESensorItem.PM10, cayenne.getPm10());
            }
            if (cayenne.hasPm2_5()) {
                sensorData.addValue(ESensorItem.PM2_5, cayenne.getPm2_5());
            }
            if (cayenne.hasRhPerc()) {
                sensorData.addValue(ESensorItem.HUMI, cayenne.getRhPerc());
            }
            if (cayenne.hasTempC()) {
                sensorData.addValue(ESensorItem.TEMP, cayenne.getTempC());
            }
            if (cayenne.hasPressureMillibar()) {
                sensorData.addValue(ESensorItem.PRESSURE, 100.0 * cayenne.getPressureMillibar());
            }
            if (cayenne.hasPosition()) {
                double[] position = cayenne.getPosition();
                sensorData.addValue(ESensorItem.POS_LAT, position[0]);
                sensorData.addValue(ESensorItem.POS_LON, position[1]);
                sensorData.addValue(ESensorItem.POS_ALT, position[2]);
            }
            break;
        default:
            throw new IllegalStateException("Unhandled encoding: " + encoding);
        }
        return sensorData;
    }

    /**
     * Starts the application.
     * 
     * @throws MqttException in case of a problem starting MQTT client
     */
    private void start() throws MqttException {
        LOG.info("Starting LoraLuftdatenForwarder application");

        // start sub-modules
        luftdatenUploader.start();
        openSenseUploader.start();
        mqttListener.start();

        LOG.info("Started LoraLuftdatenForwarder application");
    }

    /**
     * Stops the application.
     * 
     * @throws MqttException
     */
    private void stop() {
        LOG.info("Stopping LoraLuftdatenForwarder application");

        mqttListener.stop();
        openSenseUploader.stop();
        luftdatenUploader.stop();

        LOG.info("Stopped LoraLuftdatenForwarder application");
    }

    private static LoraForwarderConfig readConfig(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (FileInputStream fis = new FileInputStream(file)) {
            return mapper.readValue(fis, LoraForwarderConfig.class);
        } catch (IOException e) {
            LOG.warn("Failed to load config {}, writing defaults", file.getAbsoluteFile());
            LoraForwarderConfig config = new LoraForwarderConfig();
            mapper.writeValue(file, config);
            return config;
        }
    }

}
