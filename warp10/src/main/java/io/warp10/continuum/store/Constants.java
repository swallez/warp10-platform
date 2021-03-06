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

package io.warp10.continuum.store;

import io.warp10.WarpConfig;
import io.warp10.continuum.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Constants {
  
  //
  //  A T T E N T I O N
  //  
  //  Once the time_units and modulus have been set, their values must not be modified.
  //  
  //  Doing so would render the storage system unusable
  //
  
  private static boolean timeUnitsAlreadySet = false;
  
  /**
   * Number of continuum time units per millisecond
   * 1000000 means we store nanoseconds
   * 1000 means we store microseconds
   * 1 means we store milliseconds
   * 0.001 means we store seconds (N/A since we use a long for the constant)
   */
  public static final long TIME_UNITS_PER_MS;
  
  /**
   * Number of time units per second
   */
  public static final long TIME_UNITS_PER_S;

  /**
   * Number of nanoseconds per time unit
   */
  public static final long NS_PER_TIME_UNIT;
  
  /**
   * Row key time boundary in time units
   */
  public static final long DEFAULT_MODULUS = 1L;
  
  /**
   * Number of elevation units per meter.
   */
  public static final long ELEVATION_UNITS_PER_M = 1000L;
  
  /**
   * Name of the 'producer' label
   */
  public static final String PRODUCER_LABEL = ".producer";
  
  /**
   * Name of the 'owner' label
   */
  public static final String OWNER_LABEL = ".owner";
  
  /**
   * Name of the 'uuid' attribute
   */
  public static final String UUID_ATTRIBUTE = ".uuid";
  
  /**
   * Name of the 'application' label
   */
  public static final String APPLICATION_LABEL = ".app";

  private static final Map<String,String> HEADERS = new HashMap<String,String>();
  
  /**
   * Header containing the request UUID when calling the endpoint
   */
  public static final String HTTP_HEADER_WEBCALL_UUID_DEFAULT = "X-Warp10-WebCall";
    
  /**
   * HTTP Header for elapsed time of Einstein scripts
   */  
  public static final String HTTP_HEADER_ELAPSED_DEFAULT = "X-Warp10-Elapsed";
  
  /**
   * Script line where an error was encountered
   */
  public static final String HTTP_HEADER_ERROR_LINE_DEFAULT = "X-Warp10-Error-Line";
  
  /**
   * Message for the error that was encountered
   */
  public static final String HTTP_HEADER_ERROR_MESSAGE_DEFAULT = "X-Warp10-Error-Message";
  
  /**
   * HTTP Header for access tokens
   */
  public static final String HTTP_HEADER_TOKEN_DEFAULT = "X-Warp10-Token";

  /**
   * HTTP Header to provide the token for outgoing META requests
   */
  public static final String HTTP_HEADER_META_TOKEN_DEFAULT = "X-Warp10-Token";

  /**
   * HTTP Header to provide the token for outgoing UPDATE requests
   */
  public static final String HTTP_HEADER_UPDATE_TOKEN_DEFAULT = "X-Warp10-Token";

  /**
   * HTTP Header for access tokens used for archival
   */
  public static final String HTTP_HEADER_ARCHIVE_TOKEN_DEFAULT = "X-Warp10-ArchiveToken";
  
  /**
   * HTTP Header for setting the base timestamp for relative timestamps
   */
  public static final String HTTP_HEADER_NOW_HEADER_DEFAULT = "X-Warp10-Now";
  
  /**
   * HTTP Header for specifying the timespan for /sfetch requests
   */
  public static final String HTTP_HEADER_TIMESPAN_HEADER_DEFAULT = "X-Warp10-Timespan";
  
  /**
   * Name of header containing the signature of the token used for the fetch
   */
  public static String HTTP_HEADER_FETCH_SIGNATURE_DEFAULT = "X-Warp10-Fetch-Signature";

  /**
   * Name of header containing the signature of the token used for the update
   */
  public static String HTTP_HEADER_UPDATE_SIGNATURE_DEFAULT = "X-Warp10-Update-Signature";
  
  /**
   * Name of header containing the signature of streaming directory requests
   */
  public static String HTTP_HEADER_DIRECTORY_SIGNATURE_DEFAULT = "X-Warp10-Directory-Signature";

  /**
   * Empty column qualifier for HBase writes
   */
  public static final byte[] EMPTY_COLQ = new byte[0];
  
  /**
   * Endpoint for splits generation
   */
  public static final String API_ENDPOINT_SPLITS = "/api/v0/splits";
  
  /**
   * Endpoint for script submission
   */
  public static final String API_ENDPOINT_EXEC = "/api/v0/exec";
  
  /**
   * Update endpoint for the API
   */
  public static final String API_ENDPOINT_UPDATE = "/api/v0/update";
  
  /**
   * Find endpoint for the API
   */
  public static final String API_ENDPOINT_FIND = "/api/v0/find";
  
  /**
   * Fetch endpoint for the API
   */
  public static final String API_ENDPOINT_FETCH = "/api/v0/fetch";

  /**
   * Split fetch endpoint
   */
  public static final String API_ENDPOINT_SFETCH = "/api/v0/sfetch";
  
  /**
   * Archive Fetch endpoint for the API
   */
  public static final String API_ENDPOINT_AFETCH = "/api/v0/afetch";

  /**
   * Archive endpoint for the API
   */
  public static final String API_ENDPOINT_ARCHIVE = "/api/v0/archive";
  
  /**
   * Delete endpoint for the API
   */
  public static final String API_ENDPOINT_DELETE = "/api/v0/delete";
  
  /**
   * Plasma client endpoint for the API
   */
  public static final String API_ENDPOINT_PLASMA_CLIENT = "/api/v0/plasma/client";
  
  /**
   * Plasma server endpoint
   */
  public static final String API_ENDPOINT_PLASMA_SERVER = "/api/v0/plasma";

  /**
   * Plasma update endpoint
   */
  public static final String API_ENDPOINT_PLASMA_UPDATE = "/api/v0/streamupdate";
  
  /**
   * Mobius server endpoint
   */
  public static final String API_ENDPOINT_MOBIUS = "/api/v0/mobius";

  /**
   * Meta endpoint
   */
  public static final String API_ENDPOINT_META = "/api/v0/meta";
  
  /**
   * Geo root endpoint
   */
  public static final String API_ENDPOINT_GEO = "/api/v0/geo";
  
  /**
   * Geo endpoint subpath for 'list'
   */
  public static final String API_ENDPOINT_GEO_LIST = "/list";
  
  /**
   * Geo endpoint subpath for 'add'
   */
  public static final String API_ENDPOINT_GEO_ADD = "/add";
  
  /**
   * Geo endpoint subpath for 'remove'
   */
  public static final String API_ENDPOINT_GEO_REMOVE = "/remove";

  /**
   * Geo endpoint subpath for 'index'
   */
  public static final String API_ENDPOINT_GEO_INDEX = "/index";

  /**
   * Endpoint for internal directory streaming requests
   */
  public static final String API_ENDPOINT_DIRECTORY_STREAMING_INTERNAL = "/directory-streaming";
  
  /**
   * Header to extract POP from OVH CDN
   */
  public static final String OVH_CDN_GEO_HEADER = "X-CDN-Geo";
  
  public static final String HTTP_PARAM_TOKEN = "token";
  public static final String HTTP_PARAM_SELECTOR = "selector";
  public static final String HTTP_PARAM_START = "start";
  public static final String HTTP_PARAM_STOP = "stop";
  public static final String HTTP_PARAM_NOW = "now";
  public static final String HTTP_PARAM_TIMESPAN = "timespan";
  public static final String HTTP_PARAM_DEDUP = "dedup";
  public static final String HTTP_PARAM_FORMAT = "format";
  public static final String HTTP_PARAM_END = "end";
  public static final String HTTP_PARAM_DELETEALL = "deleteall";
  public static final String HTTP_PARAM_MINAGE = "minage";
  public static final String HTTP_PARAM_SHOWUUID = "showuuid";
  public static final String HTTP_PARAM_MINSPLITS = "minsplits";
  public static final String HTTP_PARAM_MAXSPLITS = "maxsplits";

  static {

    Properties props = null;

    try {
      props = WarpConfig.getProperties();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    String tu = props.getProperty(Configuration.WARP_TIME_UNITS);

    if (null == tu) {
      throw new RuntimeException("Missing time units.");
    } else if ("ms".equals(tu)) {
      TIME_UNITS_PER_MS = 1L;
    } else if ("us".equals(tu)) {
      TIME_UNITS_PER_MS = 1000L;
    } else if ("ns".equals(tu)) {
      TIME_UNITS_PER_MS = 1000000L;
    } else {
      throw new RuntimeException("Invalid time unit.");
    }

    TIME_UNITS_PER_S =  1000L * TIME_UNITS_PER_MS;
    NS_PER_TIME_UNIT = 1000000L / TIME_UNITS_PER_MS;
    //DEFAULT_MODULUS = 600L * TIME_UNITS_PER_S;
    
    System.out.println("########[ Initialized with " + TIME_UNITS_PER_MS + " time units per millisecond ]########");
    
    //
    // Initialize headers
    //
    
    HEADERS.put(Configuration.HTTP_HEADER_WEBCALL_UUIDX, props.getProperty(Configuration.HTTP_HEADER_WEBCALL_UUIDX, HTTP_HEADER_WEBCALL_UUID_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_ELAPSEDX, props.getProperty(Configuration.HTTP_HEADER_ELAPSEDX, HTTP_HEADER_ELAPSED_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_ERROR_LINEX, props.getProperty(Configuration.HTTP_HEADER_ERROR_LINEX, HTTP_HEADER_ERROR_LINE_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_ERROR_MESSAGEX, props.getProperty(Configuration.HTTP_HEADER_ERROR_MESSAGEX, HTTP_HEADER_ERROR_MESSAGE_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_TOKENX, props.getProperty(Configuration.HTTP_HEADER_TOKENX, HTTP_HEADER_TOKEN_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_META_TOKENX, props.getProperty(Configuration.HTTP_HEADER_META_TOKENX, HTTP_HEADER_META_TOKEN_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_UPDATE_TOKENX, props.getProperty(Configuration.HTTP_HEADER_UPDATE_TOKENX, HTTP_HEADER_UPDATE_TOKEN_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_ARCHIVE_TOKENX, props.getProperty(Configuration.HTTP_HEADER_ARCHIVE_TOKENX, HTTP_HEADER_ARCHIVE_TOKEN_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_NOW_HEADERX, props.getProperty(Configuration.HTTP_HEADER_NOW_HEADERX, HTTP_HEADER_NOW_HEADER_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_TIMESPAN_HEADERX, props.getProperty(Configuration.HTTP_HEADER_TIMESPAN_HEADERX, HTTP_HEADER_TIMESPAN_HEADER_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_FETCH_SIGNATURE, props.getProperty(Configuration.HTTP_HEADER_FETCH_SIGNATURE, HTTP_HEADER_FETCH_SIGNATURE_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_UPDATE_SIGNATURE, props.getProperty(Configuration.HTTP_HEADER_UPDATE_SIGNATURE, HTTP_HEADER_UPDATE_SIGNATURE_DEFAULT));
    HEADERS.put(Configuration.HTTP_HEADER_DIRECTORY_SIGNATURE, props.getProperty(Configuration.HTTP_HEADER_DIRECTORY_SIGNATURE, HTTP_HEADER_DIRECTORY_SIGNATURE_DEFAULT));
  }
  
  public static String getHeader(String name) {
    return HEADERS.get(name);
  }
}
