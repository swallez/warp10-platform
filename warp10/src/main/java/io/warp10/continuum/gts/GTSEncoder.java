//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.continuum.gts;

import io.warp10.continuum.store.thrift.data.Metadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.bouncycastle.crypto.engines.AESWrapEngine;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.params.KeyParameter;

import com.google.common.base.Charsets;

/**
 * Utility class used to create Geo Time Series
 */
public class GTSEncoder {

  /**
   * Mask to extract encryption flag.
   */
  static final byte FLAGS_MASK_ENCRYPTED = (byte) 0xff;

  /**
   * Mask to extract the flags continuation bit
   */
  static final byte FLAGS_MASK_CONTINUATION = (byte) 0x80;

  /**
   * Mask to extract the timestamp flags
   */
  static final byte FLAGS_MASK_TIMESTAMP = (byte) 0x60;

  /**
   * Mask to extract the type from the flags
   */
  static final byte FLAGS_MASK_TYPE = (byte) 0x18;

  /**
   * Mask to extract the type flags
   */
  static final byte FLAGS_MASK_TYPE_FLAGS = (byte) 0x07;

  /**
   * Mask to extract the location flags
   */
  static final byte FLAGS_MASK_LOCATION = (byte) 0x70;

  /**
   * Mask to extract the elevation flags
   */
  static final byte FLAGS_MASK_ELEVATION = (byte) 0x0f;

  /**
   * Flag indicating encrypted data
   */
  public static final byte FLAGS_ENCRYPTED = (byte) 0x00;

  /**
   * Flag indicating the continuation (i.e. more flag bytes)
   */
  static final byte FLAGS_CONTINUATION = (byte) 0x80;

  static final byte FLAGS_TIMESTAMP_ZIGZAG_DELTA_PREVIOUS = 0x00;
  //static final byte FLAGS_TIMESTAMP_ZIGZAG_ABSOLUTE = 0x20;
  static final byte FLAGS_TIMESTAMP_EQUALS_BASE = 0x20;
  static final byte FLAGS_TIMESTAMP_ZIGZAG_DELTA_BASE = 0x40;
  static final byte FLAGS_TIMESTAMP_RAW_ABSOLUTE = 0x60;

  static final byte FLAGS_TYPE_BOOLEAN = 0x00;
  static final byte FLAGS_TYPE_LONG = 0x08;
  static final byte FLAGS_TYPE_DOUBLE = 0x10;
  static final byte FLAGS_TYPE_STRING = 0x18;

  //
  // Where to store boolean values, we need two different bits because
  // the ENCRYPTED flag is 0x00 so we would not be able to differenciate a
  // 'false' from the ENCRYPTED flag if we don't explicitely set a bit for false
  //
  
  static final byte FLAGS_BOOLEAN_VALUE_TRUE = 0x04;
  static final byte FLAGS_BOOLEAN_VALUE_FALSE = 0x02;

  //
  // Piggyback on BOOLEAN values for delete tombstone markers
  //
  
  static final byte FLAGS_DELETE_MARKER = 0x07;
  
  static final byte FLAGS_LONG_ZIGZAG = 0x04;
  static final byte FLAGS_LONG_DELTA_PREVIOUS = 0x02;

  static final byte FLAGS_DOUBLE_IEEE754 = 0x04;

  static final byte FLAGS_VALUE_IDENTICAL = 0x01;

  static final byte FLAGS_LOCATION = 0x40;
  static final byte FLAGS_LOCATION_GEOXPPOINT_ZIGZAG_DELTA = 0x20;
  static final byte FLAGS_LOCATION_IDENTICAL = 0x10;

  static final byte FLAGS_ELEVATION = 0x08;
  static final byte FLAGS_ELEVATION_ZIGZAG = 0x04;
  static final byte FLAGS_ELEVATION_DELTA_PREVIOUS = 0x02;
  static final byte FLAGS_ELEVATION_IDENTICAL = 0x01;

  private long baseTimestamp = 0L;

  /**
   * Timestamp of last added measurement.
   */
  private long lastTimestamp = 0L;

  /**
   * GeoXPPoint of last added measurement.
   */
  private long lastGeoXPPoint = GeoTimeSerie.NO_LOCATION;

  /**
   * Elevation of last added measurement.
   */
  private long lastElevation = GeoTimeSerie.NO_ELEVATION;

  /**
   * Last long value set
   */
  private long lastLongValue = Long.MAX_VALUE;

  /**
   * Last BigDecimal value set
   */
  private BigDecimal lastBDValue = null;

  /**
   * Last Double value set
   */
  private double lastDoubleValue = Double.NaN;

  /**
   * Last String value set
   */
  private String lastStringValue = null;

  //
  // The following 7 fields are initial values which are needed
  // to decode delta encoded values when creating an encoder from
  // a decoder. @see GTSDecoder.getEncoder
  //
  
  private long initialTimestamp = lastTimestamp;
  private long initialGeoXPPoint = lastGeoXPPoint;
  private long initialElevation = lastElevation;
  private long initialLongValue = lastLongValue;
  private double initialDoubleValue = lastDoubleValue;
  private BigDecimal initialBDValue = lastBDValue;
  private String initialStringValue = lastStringValue;
  
  /**
   * OutputStream which collects the encoded values
   */
  ByteArrayOutputStream stream;

  private byte[] wrappingKey;

  /**
   * Metadata describing the Encoder.
   */
  private Metadata metadata;

  /**
   * Number of values this encoder contains.
   */
  private long count = 0L;

  private boolean noDeltaMetaTimestamp = false;
  private boolean noDeltaMetaLocation = false;
  private boolean noDeltaMetaElevation = false;
  
  private boolean noDeltaValue = false;
  
  public GTSEncoder() {
    this.stream = new ByteArrayOutputStream();
    this.wrappingKey = null;
  }

  public GTSEncoder(long baseTimestamp, byte[] key, byte[] content) {
    this.baseTimestamp = baseTimestamp;
    this.wrappingKey = null == key ? null : Arrays.copyOf(key, key.length);
    this.stream = new ByteArrayOutputStream();
    try {
      this.stream.write(content);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    // Disable delta encoding since we have no idea what the last value was
    this.safeDelta();
  }
  
  /**
   * Create an encoder using the given timestamp as its base.
   * Base timestamp may be used to encode value timestamps as deltas.
   * 
   * @param baseTimestamp
   *          Timestamp to use as base.
   */
  public GTSEncoder(long baseTimestamp) {
    this.baseTimestamp = baseTimestamp;
    this.stream = new ByteArrayOutputStream();
    this.wrappingKey = null;
  }

  /**
   * Create an encoder using the given base and AES wrapping key.
   * 
   * @param baseTimestamp
   *          Timestamp to use as base (to compute deltas)
   * @param key
   *          AES Wrapping key to use to encrypt encoded values.
   */
  public GTSEncoder(long baseTimestamp, byte[] key) {
    this.baseTimestamp = baseTimestamp;
    this.stream = new ByteArrayOutputStream();    
    this.wrappingKey = null == key ? null : Arrays.copyOf(key, key.length);
  }

  public GTSEncoder(long baseTimestamp, byte[] key, int size) {
    this.baseTimestamp = baseTimestamp;
    this.stream = new ByteArrayOutputStream(size);    
    this.wrappingKey = null == key ? null : Arrays.copyOf(key, key.length);
  }

  /**
   * Encode an additional value in the GTS.
   * 
   * @param timestamp
   *          Timestamp in microseconds at which the measurement was done
   * @param location
   *          GeoXPPoint of the measurement
   * @param elevation
   *          Elevation of the measurement
   * @param value
   *          Value of the measurement
   * @return
   */
  // Allocate an 8 bytes buffer that we will reuse in 'addValue' since addValue is synchronized
  private byte[] buf8 = new byte[8];
  private byte[] buf10 = new byte[10];
  public synchronized int addValue(long timestamp, long location, long elevation, Object value) throws IOException {
    //
    // Determine the encoding for the timestamp
    // We choose the encoding mode which leads to the least number of bytes
    // produced.
    //

    byte tsTypeFlag = (byte) 0x0;

    if (noDeltaMetaTimestamp) {
      //
      // If timestamp is < 2**48 then its varint encoding fits on less than 8
      // bytes otherwise use 8 bytes representation without varint encoding to save
      // space.
      //
      
      //if (timestamp < (1L << 48)) {
      //  tsTypeFlag |= FLAGS_TIMESTAMP_ZIGZAG_ABSOLUTE;
      //} else {
      //  tsTypeFlag |= FLAGS_TIMESTAMP_RAW_ABSOLUTE;
      //}
      tsTypeFlag |= FLAGS_TIMESTAMP_RAW_ABSOLUTE;
      noDeltaMetaTimestamp = false;
    } else {
      if (baseTimestamp == timestamp) {
        //
        // Special case, the timestamp is equal to the base, simply indicate it in the flags
        //
        tsTypeFlag |= FLAGS_TIMESTAMP_EQUALS_BASE;
      } else if (0L != lastTimestamp) {        
        long deltaBase = Math.abs(timestamp - baseTimestamp); 
        long deltaLast = Math.abs(timestamp - lastTimestamp);
        if (deltaBase < deltaLast) {
          if (deltaBase < (1L << 48)) {
            tsTypeFlag |= FLAGS_TIMESTAMP_ZIGZAG_DELTA_BASE;
          } else {
            tsTypeFlag |= FLAGS_TIMESTAMP_RAW_ABSOLUTE;
          }
        } else {
          if (deltaLast < (1L << 48)) {
            tsTypeFlag |= FLAGS_TIMESTAMP_ZIGZAG_DELTA_PREVIOUS;
          } else {
            tsTypeFlag |= FLAGS_TIMESTAMP_RAW_ABSOLUTE;
          }
        }
      } else {
        long deltaBase = Math.abs(timestamp - baseTimestamp);
        
        if (deltaBase < (1L << 48)) {
          tsTypeFlag |= FLAGS_TIMESTAMP_ZIGZAG_DELTA_BASE;
        } else {
          tsTypeFlag |= FLAGS_TIMESTAMP_RAW_ABSOLUTE;
        }
      }
    }

    //
    // Determine the value type and encoding
    //

    if (value instanceof BigInteger || value instanceof Long
        || value instanceof Integer || value instanceof Short
        || value instanceof Byte) {
      tsTypeFlag |= FLAGS_TYPE_LONG;
      long longValue = ((Number) value).longValue();
      
      if (!noDeltaValue && Long.MAX_VALUE != lastLongValue && longValue == lastLongValue) {
        tsTypeFlag |= FLAGS_VALUE_IDENTICAL;
      } else {
        long offset = longValue - lastLongValue;
        if (!noDeltaValue && Long.MAX_VALUE != lastLongValue
            && ((Math.abs(offset) < Math.abs(longValue)) && Math.abs(offset) < (1L << 48))) {
          tsTypeFlag |= FLAGS_LONG_DELTA_PREVIOUS;
          tsTypeFlag |= FLAGS_LONG_ZIGZAG;
        } else if (Math.abs(longValue) < (1L << 48)) {
          tsTypeFlag |= FLAGS_LONG_ZIGZAG;
        }
      }
    } else if (value instanceof Boolean) {
      tsTypeFlag |= FLAGS_TYPE_BOOLEAN;

      // Set value in flag

      if (((Boolean) value).booleanValue()) {
        tsTypeFlag |= FLAGS_BOOLEAN_VALUE_TRUE;
      } else {
        tsTypeFlag |= FLAGS_BOOLEAN_VALUE_FALSE;
      }
      
    } else if (value instanceof String) {
      tsTypeFlag |= FLAGS_TYPE_STRING;
      if (((String) value).equals(lastStringValue)) {
        tsTypeFlag |= FLAGS_VALUE_IDENTICAL;
      }
    } else if (value instanceof Double || value instanceof Float) {
      tsTypeFlag |= FLAGS_TYPE_DOUBLE;
      // Only compare to the previous double value if the last floating point value was NOT encoded as a BigDecimal
      if (null == lastBDValue && lastDoubleValue == ((Number) value).doubleValue()) {
        tsTypeFlag |= FLAGS_VALUE_IDENTICAL;
      } else {
        tsTypeFlag |= FLAGS_DOUBLE_IEEE754;
      }
    } else if (value instanceof BigDecimal) {
      tsTypeFlag |= FLAGS_TYPE_DOUBLE;
      BigDecimal doubleValue = (BigDecimal) value;

      // Strip trailing zero so we optimize the representation
      doubleValue = doubleValue.stripTrailingZeros();

      if (null != lastBDValue && 0 == lastBDValue.compareTo(doubleValue)) {
        tsTypeFlag |= FLAGS_VALUE_IDENTICAL;
      } else {
        int scale = doubleValue.scale();

        // If scale does not fit on a byte, use IEEE754
        if (scale > 127 || scale < -128) {
          tsTypeFlag |= FLAGS_DOUBLE_IEEE754;
        } else {
          BigInteger bi = doubleValue.unscaledValue();

          int bitlen = bi.bitLength();

          // If mantissa is greater than 46 bits, use IEEE754
          if (bitlen > 46) {
            tsTypeFlag |= FLAGS_DOUBLE_IEEE754;
          }
        }
      }
    } else if (null == value) {
      tsTypeFlag |= FLAGS_TYPE_BOOLEAN;
      tsTypeFlag |= FLAGS_DELETE_MARKER;
    } else {
      throw new RuntimeException("Unsuported value type '" + value.getClass() + "'");
    }

    //
    // Handle location and elevation
    //

    byte locElevFlag = 0x0;

    if (GeoTimeSerie.NO_LOCATION != location && null != value) {
      tsTypeFlag |= FLAGS_CONTINUATION;

      locElevFlag |= FLAGS_LOCATION;

      //
      // Check if there is a previous location, if so compute
      // the delta and check that its ABS is < 1**48. If that is
      // the case, encoding it as zig zag varint will save space.
      // Otherwise, encode location as raw GeoXPPoint.
      //

      if (GeoTimeSerie.NO_LOCATION != lastGeoXPPoint && !noDeltaMetaLocation) {
        if (lastGeoXPPoint == location) {
          locElevFlag |= FLAGS_LOCATION_IDENTICAL;
        } else {
          long delta = location - lastGeoXPPoint;
          if (Math.abs(delta) < (1L << 48)) {
            locElevFlag |= FLAGS_LOCATION_GEOXPPOINT_ZIGZAG_DELTA;
          }
        }
      } else {
        // Do nothing, implicitely we will encode location as raw GeoXPPoint
        noDeltaMetaLocation = false;
      }
    } else {
      lastGeoXPPoint = GeoTimeSerie.NO_LOCATION;
    }

    if (GeoTimeSerie.NO_ELEVATION != elevation && null != value) {
      tsTypeFlag |= FLAGS_CONTINUATION;

      locElevFlag |= FLAGS_ELEVATION;

      //
      // Check delta from previous elevation if it exists.
      // If it's worth it spacewise, set encoding to zig zag varint delta.
      //

      if (GeoTimeSerie.NO_ELEVATION != lastElevation && !noDeltaMetaElevation) {
        if (lastElevation == elevation) {
          locElevFlag |= FLAGS_ELEVATION_IDENTICAL;
        } else {
          long delta = elevation - lastElevation;
          if (Math.abs(delta) < (1L << 48)) {
            locElevFlag |= FLAGS_ELEVATION_DELTA_PREVIOUS;
            locElevFlag |= FLAGS_ELEVATION_ZIGZAG;
          } else {
            // Delta is too large to be efficiently encoded as zig zag varint.
            // Check if raw elevation would benefit from such encoding.
            if (Math.abs(elevation) < (1L << 48)) {
              locElevFlag |= FLAGS_ELEVATION_ZIGZAG;
            }
          }
        }
      } else {
        if (Math.abs(elevation) < (1L << 48)) {
          locElevFlag |= FLAGS_ELEVATION_ZIGZAG;
        }
        noDeltaMetaElevation = false;
      }
    } else {
      lastElevation = GeoTimeSerie.NO_ELEVATION;
    }

    //
    // Ok, we now have set all the flags, we can start adding to the stream.
    //

    // First add the flags

    this.stream.write(tsTypeFlag);

    if (FLAGS_CONTINUATION == (tsTypeFlag & FLAGS_CONTINUATION)) {
      this.stream.write(locElevFlag);
    }

    // Write timestamp

    switch (tsTypeFlag & FLAGS_MASK_TIMESTAMP) {
      case FLAGS_TIMESTAMP_RAW_ABSOLUTE: {
        byte[] buf = buf8; //new byte[8];
        //ByteBuffer bb = ByteBuffer.wrap(buf);
        //bb.order(ByteOrder.BIG_ENDIAN);
        //bb.putLong(timestamp);
        
        buf[0] = (byte) ((timestamp >> 56) & 0xff);
        buf[1] = (byte) ((timestamp >> 48) & 0xff);
        buf[2] = (byte) ((timestamp >> 40) & 0xff);
        buf[3] = (byte) ((timestamp >> 32) & 0xff);
        buf[4] = (byte) ((timestamp >> 24) & 0xff);
        buf[5] = (byte) ((timestamp >> 16) & 0xff);
        buf[6] = (byte) ((timestamp >> 8) & 0xff);
        buf[7] = (byte) (timestamp & 0xff);
        
        this.stream.write(buf);
      }
        break;
      //case FLAGS_TIMESTAMP_ZIGZAG_ABSOLUTE:
      //  this.stream.write(Varint.encodeSignedLong(timestamp));
      //  break;
      case FLAGS_TIMESTAMP_EQUALS_BASE:
        // no timestamp encoding
        break;
      case FLAGS_TIMESTAMP_ZIGZAG_DELTA_BASE:
        //BUF10 this.stream.write(Varint.encodeSignedLong(timestamp - baseTimestamp));
        int l = Varint.encodeSignedLongInBuf(timestamp - baseTimestamp, buf10);
        this.stream.write(buf10, 0, l);
        break;
      case FLAGS_TIMESTAMP_ZIGZAG_DELTA_PREVIOUS:
        //BUF10 this.stream.write(Varint.encodeSignedLong(timestamp - lastTimestamp));
        int ll = Varint.encodeSignedLongInBuf(timestamp - lastTimestamp, buf10);
        this.stream.write(buf10, 0, ll);
        break;
      default:
        throw new RuntimeException("Invalid timestamp format.");
    }

    // Keep track of timestamp
    lastTimestamp = timestamp;

    // Write location data

    if (FLAGS_LOCATION == (locElevFlag & FLAGS_LOCATION)) {
      if (FLAGS_LOCATION_IDENTICAL != (locElevFlag & FLAGS_LOCATION_IDENTICAL)) {
        if (FLAGS_LOCATION_GEOXPPOINT_ZIGZAG_DELTA == (locElevFlag & FLAGS_LOCATION_GEOXPPOINT_ZIGZAG_DELTA)) {
          long delta = location - lastGeoXPPoint;
          //BUF10 this.stream.write(Varint.encodeSignedLong(delta));
          int l = Varint.encodeSignedLongInBuf(delta, buf10);
          this.stream.write(buf10, 0, l);
        } else {
          byte[] buf = buf8;//new byte[8];
          //ByteBuffer bb = ByteBuffer.wrap(buf);
          //bb.order(ByteOrder.BIG_ENDIAN);
          //bb.putLong(location);
          
          buf[0] = (byte) ((location >> 56) & 0xff);
          buf[1] = (byte) ((location >> 48) & 0xff);
          buf[2] = (byte) ((location >> 40) & 0xff);
          buf[3] = (byte) ((location >> 32) & 0xff);
          buf[4] = (byte) ((location >> 24) & 0xff);
          buf[5] = (byte) ((location >> 16) & 0xff);
          buf[6] = (byte) ((location >> 8) & 0xff);
          buf[7] = (byte) (location & 0xff);

          this.stream.write(buf);
        }
      }
      lastGeoXPPoint = location;
    }

    // Write elevation data

    if (FLAGS_ELEVATION == (locElevFlag & FLAGS_ELEVATION)) {
      if (FLAGS_ELEVATION_IDENTICAL != (locElevFlag & FLAGS_ELEVATION_IDENTICAL)) {
        boolean zigzag = FLAGS_ELEVATION_ZIGZAG == (locElevFlag & FLAGS_ELEVATION_ZIGZAG);
        long toencode = elevation;

        if (FLAGS_ELEVATION_DELTA_PREVIOUS == (locElevFlag & FLAGS_ELEVATION_DELTA_PREVIOUS)) {
          toencode = elevation - lastElevation;
        }

        if (zigzag) {
          //BUF10 this.stream.write(Varint.encodeSignedLong(toencode));
          int l = Varint.encodeSignedLongInBuf(toencode, buf10);
          this.stream.write(buf10, 0, l);
        } else {
          byte[] buf = buf8; //new byte[8];
          //ByteBuffer bb = ByteBuffer.wrap(buf);
          //bb.order(ByteOrder.BIG_ENDIAN);
          //bb.putLong(toencode);
          
          buf[0] = (byte) ((toencode >> 56) & 0xff);
          buf[1] = (byte) ((toencode >> 48) & 0xff);
          buf[2] = (byte) ((toencode >> 40) & 0xff);
          buf[3] = (byte) ((toencode >> 32) & 0xff);
          buf[4] = (byte) ((toencode >> 24) & 0xff);
          buf[5] = (byte) ((toencode >> 16) & 0xff);
          buf[6] = (byte) ((toencode >> 8) & 0xff);
          buf[7] = (byte) (toencode & 0xff);

          this.stream.write(buf);
        }
      }
      lastElevation = elevation;
    }

    // Write value (if type is not boolean, as boolean values are included in
    // the type flags)

    switch (tsTypeFlag & FLAGS_MASK_TYPE) {
      case FLAGS_TYPE_STRING:
        if (FLAGS_VALUE_IDENTICAL != (tsTypeFlag & FLAGS_VALUE_IDENTICAL)) {
          // Convert String to UTF8 byte array
          byte[] utf8 = ((String) value).getBytes(Charsets.UTF_8);
          // Store encoded byte array length as zig zag varint
          //BUF10 this.stream.write(Varint.encodeUnsignedLong(utf8.length));
          int l = Varint.encodeUnsignedLongInBuf(utf8.length, buf10);
          this.stream.write(buf10, 0, l);
          // Store UTF8 bytes
          this.stream.write(utf8);

          // Keep track of last value
          lastStringValue = (String) value;
        }
        break;

      case FLAGS_TYPE_LONG:
        if (FLAGS_VALUE_IDENTICAL != (tsTypeFlag & FLAGS_VALUE_IDENTICAL)) {
          long lvalue = ((Number) value).longValue();
          long toencode = lvalue;

          if (FLAGS_LONG_DELTA_PREVIOUS == (tsTypeFlag & FLAGS_LONG_DELTA_PREVIOUS)) {
            toencode = lvalue - lastLongValue;
          }

          if (FLAGS_LONG_ZIGZAG == (tsTypeFlag & FLAGS_LONG_ZIGZAG)) {
            //BUF10 this.stream.write(Varint.encodeSignedLong(toencode));
            int l = Varint.encodeSignedLongInBuf(toencode, buf10);
            this.stream.write(buf10, 0, l);
          } else {
            byte[] buf = buf8; //new byte[8];
            //ByteBuffer bb = ByteBuffer.wrap(buf);
            //bb.order(ByteOrder.BIG_ENDIAN);
            //bb.putLong(toencode);

            buf[0] = (byte) ((toencode >> 56) & 0xff);
            buf[1] = (byte) ((toencode >> 48) & 0xff);
            buf[2] = (byte) ((toencode >> 40) & 0xff);
            buf[3] = (byte) ((toencode >> 32) & 0xff);
            buf[4] = (byte) ((toencode >> 24) & 0xff);
            buf[5] = (byte) ((toencode >> 16) & 0xff);
            buf[6] = (byte) ((toencode >> 8) & 0xff);
            buf[7] = (byte) (toencode & 0xff);

            this.stream.write(buf);
          }

          noDeltaValue = false;
          // Keep track of last value
          lastLongValue = lvalue;
        }
        break;

      case FLAGS_TYPE_DOUBLE:
        if (FLAGS_VALUE_IDENTICAL != (tsTypeFlag & FLAGS_VALUE_IDENTICAL)) {

          if (FLAGS_DOUBLE_IEEE754 == (tsTypeFlag & FLAGS_DOUBLE_IEEE754)) {
            byte[] buf = buf8; //new byte[8];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            bb.order(ByteOrder.BIG_ENDIAN);
            // Keep track of last value
            lastDoubleValue = ((Number) value).doubleValue();
            bb.putDouble(lastDoubleValue);
            this.stream.write(buf);
            // Clear the last BDValue otherwise we might incorrectly encode the next value specified as a BigDecimal
            lastBDValue = null;
          } else {
            BigDecimal dvalue = (BigDecimal) value;
            dvalue = dvalue.stripTrailingZeros();

            int scale = dvalue.scale();
            long unscaled = dvalue.unscaledValue().longValue();

            this.stream.write(scale);
            //BUF10 this.stream.write(Varint.encodeSignedLong(unscaled));
            int l = Varint.encodeSignedLongInBuf(unscaled, buf10);
            this.stream.write(buf10, 0, l);
            // Keep track of last value
            lastBDValue = dvalue;
          }
        }
        break;

      case FLAGS_TYPE_BOOLEAN:
        // do nothing.
        break;
      default:
        throw new RuntimeException("Invalid type encountered!");
    }

    this.count++;
    
    return this.stream.size();
  }
  
  public void setWrappingKey(byte[] key) {
    this.wrappingKey = null == key ? null : Arrays.copyOf(key, key.length);
  }
  
  /**
   * Return the bytes currently in this encoder.
   * If 'wrappingKey' is non null, encrypt the bytes prior to returning them.
   * 
   * @return The (possibly encrypted bytes) or null if an exception is raised
   *         while encrypting.
   * 
   */
  public byte[] getBytes() {
    if (null == this.wrappingKey) {
      return this.stream.toByteArray();
    } else {
      AESWrapEngine engine = new AESWrapEngine();
      KeyParameter params = new KeyParameter(this.wrappingKey);
      engine.init(true, params);
      PKCS7Padding padding = new PKCS7Padding();
      byte[] unpadded = this.stream.toByteArray();

      //
      // Add padding
      //

      byte[] padded = new byte[unpadded.length + (8 - unpadded.length % 8)];
      System.arraycopy(unpadded, 0, padded, 0, unpadded.length);
      padding.addPadding(padded, unpadded.length);

      //
      // Wrap
      //

      byte[] encrypted = engine.wrap(padded, 0, padded.length);

      //
      // Add 0x0 flag and encrypted data size
      //

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      try {
        baos.write(GTSEncoder.FLAGS_ENCRYPTED);
        baos.write(Varint.encodeUnsignedLong(encrypted.length));
        baos.write(encrypted);
        return baos.toByteArray();
      } catch (IOException ioe) {
        return null;
      }
    }
  }
  
  /**
   * Return the current size of the encoded data.
   * 
   * @return
   */
  public int size() {
    return this.stream.size();
  }

  /**
   * Return the number of values encoded by this encoder.
   * @return
   */
  public long getCount() {
    return this.count;
  }
  
  /**
   * Encode the given GTS instance.
   * 
   * @param gts
   */
  public void encode(GeoTimeSerie gts) throws IOException {
    for (int i = 0; i < gts.values; i++) {
      addValue(gts.ticks[i], null != gts.locations ? gts.locations[i] : GeoTimeSerie.NO_LOCATION, null != gts.elevations ? gts.elevations[i] : GeoTimeSerie.NO_ELEVATION, GTSHelper.valueAtIndex(gts, i));
    }
  }
  
  /**
   * Return a decoder instance capable of decoding the encoded content of this
   * encoder.
   * 
   * @param safeMetadata Is it safe to reuse the Metadata instance?
   * @return A suitable GTSDecoder instance.
   */
  public GTSDecoder getDecoder(boolean safeMetadata) {
    GTSDecoder decoder = new GTSDecoder(this.baseTimestamp, this.wrappingKey, ByteBuffer.wrap(this.stream.toByteArray()));
    if (!safeMetadata) {
      decoder.setMetadata(this.getMetadata());
    } else {
      decoder.safeSetMetadata(this.getMetadata());
    }
    decoder.initialize(
      this.initialTimestamp,
      this.initialGeoXPPoint,
      this.initialElevation,
      this.initialLongValue,
      this.initialDoubleValue,
      this.initialBDValue,
      this.initialStringValue);
    
    decoder.setCount(this.getCount());
    return decoder;
  }

  public GTSDecoder getDecoder() {
    return getDecoder(false);
  }
  
  /**
   * Set the initial values of the encoder, to be used in the created decoder to decode delta encoded values
   * 
   * @param initialTimestamp
   * @param initialGeoXPPoint
   * @param initialElevation
   * @param initialLongValue
   * @param initialDoubleValue
   * @param initialBDValue
   * @param initialStringValue
   */
  synchronized void initialize(long initialTimestamp, long initialGeoXPPoint, long initialElevation, long initialLongValue, double initialDoubleValue, BigDecimal initialBDValue, String initialStringValue) {
    this.initialTimestamp = initialTimestamp;
    this.initialGeoXPPoint = initialGeoXPPoint;
    this.initialElevation = initialElevation;
    this.initialLongValue = initialLongValue;
    this.initialDoubleValue = initialDoubleValue;
    this.initialBDValue = initialBDValue;
    this.initialStringValue = initialStringValue;
  }
  
  /**
   * Reset the state of this encoder with that of 'encoder'.
   * 
   * @param encoder
   * @throws IOException
   */
  public void reset(GTSEncoder encoder) throws IOException {
    this.initialize(encoder.initialTimestamp, encoder.initialGeoXPPoint, encoder.initialElevation, encoder.initialLongValue, encoder.initialDoubleValue, encoder.initialBDValue, encoder.initialStringValue);
    
    this.baseTimestamp = encoder.baseTimestamp;
    this.count = encoder.count;
    
    this.lastBDValue = encoder.lastBDValue;
    this.lastDoubleValue = encoder.lastDoubleValue;
    this.lastGeoXPPoint = encoder.lastGeoXPPoint;
    this.lastElevation = encoder.lastElevation;
    this.lastLongValue = encoder.lastLongValue;
    this.lastStringValue = encoder.lastStringValue;
    this.lastTimestamp = encoder.lastTimestamp;
  
    this.metadata = encoder.metadata;
    
    this.wrappingKey = encoder.wrappingKey;
    
    this.noDeltaMetaTimestamp = encoder.noDeltaMetaTimestamp;
    this.noDeltaMetaLocation = encoder.noDeltaMetaLocation;
    this.noDeltaMetaElevation = encoder.noDeltaMetaElevation;
    this.noDeltaValue = encoder.noDeltaValue;
    
    this.stream.reset();
    this.stream.write(encoder.stream.toByteArray());
  }
  
  public void reset(long baseTS) throws IOException {
    baseTimestamp = baseTS;
    lastTimestamp = 0L;
    lastGeoXPPoint = GeoTimeSerie.NO_LOCATION;
    lastElevation = GeoTimeSerie.NO_ELEVATION;
    lastLongValue = Long.MAX_VALUE;
    lastBDValue = null;
    lastDoubleValue = Double.NaN;
    lastStringValue = null;

    initialTimestamp = lastTimestamp;
    initialGeoXPPoint = lastGeoXPPoint;
    initialElevation = lastElevation;
    initialLongValue = lastLongValue;
    initialDoubleValue = lastDoubleValue;
    initialBDValue = lastBDValue;
    initialStringValue = lastStringValue;

    metadata = null;
    count = 0L;

    noDeltaMetaTimestamp = false;
    noDeltaMetaLocation = false;
    noDeltaMetaElevation = false;
    noDeltaValue = false;
    
    stream.reset();
  }
  
  /**
   * Merge data encoded in another encoder with this one.
   * 
   * If the two encoders have different base timestamps or different
   * encryption keys, the values will be fetched using a decoder
   * and added individually. Otherwise a fastpath is taken and
   * the encoded bytes are added.
   *
   * @param encoder GTSEncoder instance containing the data to merge
   * @throws IOException
   */
  public synchronized void merge(GTSEncoder encoder) throws IOException {

    //
    // If the current encoder is empty and the base timestamps and wrapping
    // keys match, simply reset 'this' with 'encoder'
    //
    
    if (0 == this.size() && this.baseTimestamp == encoder.baseTimestamp && Arrays.equals(this.wrappingKey, encoder.wrappingKey)) {
      this.reset(encoder);
      return;
    }
    
    //
    // If the initialization parameters of 'encoder' differ from the last values of 'this'
    // or if the base timestamp of wrapping keys differ, use the safe path and copy values individually
    //
    
    if (this.baseTimestamp != encoder.baseTimestamp
        || !Arrays.equals(this.wrappingKey, encoder.wrappingKey)
        || this.lastTimestamp != encoder.initialTimestamp
        || this.lastGeoXPPoint != encoder.initialGeoXPPoint
        || this.lastElevation != encoder.initialElevation
        || this.lastLongValue != encoder.initialLongValue
        || this.lastDoubleValue != encoder.initialDoubleValue
        || this.lastBDValue != encoder.initialBDValue
        || this.lastStringValue != encoder.initialStringValue) {
      GTSDecoder decoder = encoder.getDecoder(true);

      while (decoder.next()) {
        this.addValue(decoder.getTimestamp(), decoder.getLocation(), decoder.getElevation(), decoder.getValue());
      }      
    } else {
      //
      // Same basetimestamp, wrapping key and matching 'last' and 'initial' values, take the fast path!
      //
      
      // Copy the data
      this.stream.write(encoder.getBytes());
      
      // Copy the last values
      this.lastTimestamp = encoder.lastTimestamp;
      this.lastElevation = encoder.lastElevation;
      this.lastGeoXPPoint = encoder.lastGeoXPPoint;
      this.lastLongValue = encoder.lastLongValue;
      this.lastBDValue = encoder.lastBDValue;
      this.lastDoubleValue = encoder.lastDoubleValue;
      this.lastStringValue = encoder.lastStringValue;
      this.count += encoder.getCount();
    }
  }
  
  public long getBaseTimestamp() {
    return baseTimestamp;
  }

  public long getClassId() {
    return this.getMetadata().getClassId();
  }

  public void setClassId(long classId) {
    this.getMetadata().setClassId(classId);
  }

  public long getLabelsId() {
    return this.getMetadata().getLabelsId();
  }

  public void setLabelsId(long labelsId) {
    this.getMetadata().setLabelsId(labelsId);
  }
  
  public String getName() {
    return this.getMetadata().getName();
  }

  public void setName(String name) {
    this.getMetadata().setName(name);
  }

  public Map<String, String> getLabels() {
    return Collections.unmodifiableMap(this.getMetadata().getLabels());
  }

  public void setLabels(Map<String, String> labels) {
    this.getMetadata().setLabels(new HashMap<String,String>(labels));
  }

  public void setLabel(String key, String value) {
    this.getMetadata().getLabels().put(key, value);
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = new Metadata(metadata);
  }

  /**
   * Package protected version of the above method which reuses the Metadata verbatim
   * This version is targeted to GTSDecoder to speed up the call to getEncoder
   * @param metadata
   */
  public void safeSetMetadata(Metadata metadata) {
    this.metadata = metadata;
  }
  
  public Metadata getMetadata() {
    if (null == this.metadata) {
      this.metadata = new Metadata();
    }
    
    if (null == this.metadata.getLabels()) {
      this.metadata.setLabels(new HashMap<String,String>());
    }
    
    if (null == this.metadata.getAttributes()) {
      this.metadata.setAttributes(new HashMap<String,String>());
    }
    
    return this.metadata;
  }
  
  public long getLastTimestamp() {
    return this.lastTimestamp;
  }
  
  /**
   * Disable delta encoding until the encoder has encountered a new
   * ts/location/elevation and longValue.
   * This is used when creating an encoder from the remaining of a decoder,
   * in this case we don't know the 'last' value and thus cannot delta encode the new value
   */
  public void safeDelta() {
    this.noDeltaMetaTimestamp = true;
    this.noDeltaMetaLocation = true;
    this.noDeltaMetaElevation = true;
    
    this.noDeltaValue = true;
  }
  
  public void setCount(long count) {
    this.count = count;
  }
  
  /**
   * Empty the output stream and disable delta encoding
   */
  public void flush() {
    // We allocate a new stream so we get rid of the potentially large underlying byte array
    this.stream = new ByteArrayOutputStream();
    this.safeDelta();
  }
  
  /**
   * Transform the current encoder into a storable block.
   * 
   * @param compress
   * @return
   */
  public byte[] toBlock(boolean compress) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(); 
    
    //
    // Reserve bytes for size + compress flag + base timestamp
    //
 
    baos.write(0);
    baos.write(0);
    baos.write(0);
    baos.write(0);

    byte[] payload = this.getBytes();

    if (payload.length < 128) {
      compress = false;
    }
    
    baos.write(compress ? 1 : 0);
    
    //
    // Write header indicating whether content is compressed or not
    //

    baos.write(Varint.encodeSignedLong(this.baseTimestamp));
    
    OutputStream out = baos;

    if (compress) {
      out = new GZIPOutputStream(out);
    }
    
    out.write(payload, 0, payload.length);
    
    out.flush();
    out.close();
   
    byte[] data = baos.toByteArray();

    //
    // Update length
    //
    
    int len = data.length;
    
    data[0] = (byte) ((len >>> 24) & 0xff);
    data[1] = (byte) ((len >>> 16) & 0xff);
    data[2] = (byte) ((len >>> 8) & 0xff);
    data[3] = (byte) (len & 0xff);
    
    return data;
  }
}
