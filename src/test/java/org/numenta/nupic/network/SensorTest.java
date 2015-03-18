package org.numenta.nupic.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;
import org.numenta.nupic.FieldMetaType;
import org.numenta.nupic.Parameters;
import org.numenta.nupic.Parameters.KEY;
import org.numenta.nupic.datagen.ResourceLocator;
import org.numenta.nupic.encoders.DateEncoder;
import org.numenta.nupic.encoders.Encoder;
import org.numenta.nupic.encoders.EncoderTuple;
import org.numenta.nupic.encoders.MultiEncoder;
import org.numenta.nupic.encoders.RandomDistributedScalarEncoder;
import org.numenta.nupic.network.SensorParams.Keys;
import org.numenta.nupic.util.Tuple;

/**
 * Higher level test than the individual sensor tests. These
 * tests ensure the complete functionality of sensors as a whole.
 * 
 * @author David Ray
 *
 */
public class SensorTest {
    private Map<String, Map<String, Object>> setupMap(
        Map<String, Map<String, Object>> map,
            int n, int w, double min, double max, double radius, double resolution, Boolean periodic,
                Boolean clip, Boolean forced, String fieldName, String fieldType, String encoderType) {
        
        if(map == null) {
            map = new HashMap<String, Map<String, Object>>();
        }
        Map<String, Object> inner = null;
        if((inner = map.get(fieldName)) == null) {
            map.put(fieldName, inner = new HashMap<String, Object>());
        }
        
        inner.put("n", n);
        inner.put("w", w);
        inner.put("minVal", min);
        inner.put("maxVal", max);
        inner.put("radius", radius);
        inner.put("resolution", resolution);
        
        if(periodic != null) inner.put("periodic", periodic);
        if(clip != null) inner.put("clip", clip);
        if(forced != null) inner.put("forced", forced);
        if(fieldName != null) inner.put("fieldName", fieldName);
        if(fieldType != null) inner.put("fieldType", fieldType);
        if(encoderType != null) inner.put("encoderType", encoderType);
        
        return map;
    }
    
    private Parameters getTestEncoderParams() {
        Map<String, Map<String, Object>> fieldEncodings = setupMap(
            null,
            0, // n
            0, // w
            0, 0, 0, 0, null, null, null,
            "timestamp", "datetime", "DateEncoder");
        fieldEncodings = setupMap(
            fieldEncodings, 
            25, 
            3, 
            0, 0, 0, 0.1, null, null, null, 
            "consumption", "float", "RandomDistributedScalarEncoder");
        
        fieldEncodings.get("timestamp").put(KEY.DATEFIELD_DOFW.getFieldName(), new Tuple(1, 1.0)); // Day of week
        fieldEncodings.get("timestamp").put(KEY.DATEFIELD_TOFD.getFieldName(), new Tuple(5, 4.0)); // Time of day
        fieldEncodings.get("timestamp").put(KEY.DATEFIELD_PATTERN.getFieldName(), "MM/dd/YY HH:mm");
        
        // This will work also
        //fieldEncodings.get("timestamp").put(KEY.DATEFIELD_FORMATTER.getFieldName(), DateEncoder.FULL_DATE);
                
        Parameters p = Parameters.getEncoderDefaultParameters();
        p.setParameterByKey(KEY.FIELD_ENCODING_MAP, fieldEncodings);
        
        return p;
    }

    /**
     * Tests that the creation mechanism detects insufficient state
     * for creating {@link Sensor}s.
     */
    @Test
    public void testHandlesImproperInstantiation() {
        try {
            Sensor.create(null, null);
            fail();
        }catch(Exception e) {
            assertEquals("Factory cannot be null", e.getMessage());
        }
        
        try {
            Sensor.create(FileSensor::create, null);
            fail();
        }catch(Exception e) {
            assertEquals("Properties (i.e. \"SensorParams\") cannot be null", e.getMessage());
        }
    }
    
    /**
     * Tests the formation of meta constructs (i.e. may be header or other) which
     * describe the format of columnated data and processing hints (how and when to reset).
     */
    @Test
    public void testMetaFormation() {
        Sensor<File> sensor = Sensor.create(
            FileSensor::create, 
            SensorParams.create(Keys::path, "", ResourceLocator.path("rec-center-hourly.csv")));
        
        // Cast the ValueList to the more complex type (SensorInputMeta)
        SensorInputMeta meta = (SensorInputMeta)sensor.getMeta();
        assertTrue(meta.getFieldTypes().stream().allMatch(
            l -> l.equals(FieldMetaType.DATETIME) || l.equals(FieldMetaType.FLOAT)));
        assertTrue(meta.getFieldNames().stream().allMatch(
            l -> l.equals("timestamp") || l.equals("consumption")));
        assertTrue(meta.getFlags().stream().allMatch(
            l -> l.equals(SensorFlags.T) || l.equals(SensorFlags.B)));
    }
    
    /**
     * Tests the auto-creation of Encoders from Sensor meta data.
     */
    @Test
    public void testInternalEncoderCreation() {
        
        Sensor<File> sensor = Sensor.create(
            FileSensor::create, 
            SensorParams.create(
                Keys::path, "", ResourceLocator.path("rec-center-hourly.csv")));
        
        
        // Cast the ValueList to the more complex type (SensorInputMeta)
        HTMSensor<File> htmSensor = (HTMSensor<File>)sensor;
        SensorInputMeta meta = (SensorInputMeta)htmSensor.getMeta();
        assertTrue(meta.getFieldTypes().stream().allMatch(
            l -> l.equals(FieldMetaType.DATETIME) || l.equals(FieldMetaType.FLOAT)));
        assertTrue(meta.getFieldNames().stream().allMatch(
            l -> l.equals("timestamp") || l.equals("consumption")));
        assertTrue(meta.getFlags().stream().allMatch(
            l -> l.equals(SensorFlags.T) || l.equals(SensorFlags.B)));
        
        // Set the parameters on the sensor.
        // This enables it to auto-configure itself; a step which will
        // be done at the Region level.
        Encoder<Object> multiEncoder = htmSensor.getEncoder();
        assertNotNull(multiEncoder);
        assertTrue(multiEncoder instanceof MultiEncoder);
        
        // Set the Local parameters on the Sensor
        htmSensor.setLocalParameters(getTestEncoderParams());
        List<EncoderTuple> encoders = multiEncoder.getEncoders(multiEncoder);
        assertEquals(2, encoders.size());
        
        // Test date specific encoder configuration
        //
        // All encoders in the MultiEncoder are accessed in a particular
        // order (the alphabetical order their corresponding fields are in),
        // so alphabetically "consumption" proceeds "timestamp"
        // so we need to ensure that the proper order is preserved (i.e. exists at index 1)
        DateEncoder dateEnc = (DateEncoder)encoders.get(1).getEncoder();
        try {
            dateEnc.parseEncode("7/12/10 13:10");
            dateEnc.parseEncode("7/12/2010 13:10");
            // Should fail here due to conflict with configured format
            dateEnc.parseEncode("13:10 7/12/10");
            fail();
        }catch(Exception e) {
           assertEquals("Invalid format: \"13:10 7/12/10\" is malformed at \":10 7/12/10\"", e.getMessage());
        }
        
        RandomDistributedScalarEncoder rdse = (RandomDistributedScalarEncoder)encoders.get(0).getEncoder();
        int[] encoding = rdse.encode(35.3);
        System.out.println(Arrays.toString(encoding));
        
        // Now test the encoding of an input row
        Map<String, Object> d = new HashMap<String, Object>();
        d.put("timestamp", dateEnc.parse("7/12/10 13:10"));
        d.put("consumption", 35.3);
        int[] output = multiEncoder.encode(d);
        int[] expected = {0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 
                          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(Arrays.equals(expected, output));
    }
    
    /**
     * Test that we can query the stream for its terminal state, which {@link Stream}s
     * don't provide out of the box.
     */
    @Test
    public void testSensorTerminalOperationDetection() {
        Sensor<File> sensor = Sensor.create(
            FileSensor::create, 
            SensorParams.create(
                Keys::path, "", ResourceLocator.path("rec-center-hourly.csv")));
        
        HTMSensor<File> htmSensor = (HTMSensor<File>)sensor;
        // We haven't done anything with the stream yet, so it should not be terminal
        assertFalse(htmSensor.isTerminal());
        htmSensor.getInputStream().forEach(l -> System.out.println(Arrays.toString((String[])l)));
        // Should now be terminal after operating on the stream
        assertTrue(htmSensor.isTerminal());
    }
    
    /**
     * Tests mechanism by which {@link Sensor}s will input information
     * and output information.
     */
    @Test
    public void testSensorInputOutput() {
        
        Sensor<File> sensor = Sensor.create(
            FileSensor::create, 
            SensorParams.create(
                Keys::path, "", ResourceLocator.path("rec-center-hourly-small.csv")));
        
        HTMSensor<File> htmSensor = (HTMSensor<File>)sensor;
        
        htmSensor.setLocalParameters(getTestEncoderParams());
        
        Stream<int[]> outputStream = htmSensor.getOutputStream();
        outputStream.forEach(l -> System.out.println(Arrays.toString((int[])l)));
    }
}
