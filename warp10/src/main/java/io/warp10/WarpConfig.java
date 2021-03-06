package io.warp10;

import io.warp10.continuum.Configuration;
import io.warp10.continuum.Tokens;
import io.warp10.continuum.store.Constants;
import io.warp10.script.WarpScriptJarRepository;
import io.warp10.script.WarpScriptMacroRepository;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WarpConfig {
  
  private static Properties properties = null;
  
  public static void setProperties(String file) throws IOException {
    if (null != properties) {
      throw new RuntimeException("Properties already set.");
    }
    
    if (null != file) {
      properties = readConfig(new FileInputStream(file), null);
    } else {
      properties = readConfig(new StringReader(""), null);
    }

    //
    // Force a call to Constants.TIME_UNITS_PER_MS to check timeunits value is correct
    //
    long warpTimeunits = Constants.TIME_UNITS_PER_MS;

    //
    // Load tokens from file
    //
    
    //if (null != properties.getProperty(CONTINUUM_TOKEN_FILE)) {
    Tokens.init(properties.getProperty(Configuration.WARP_TOKEN_FILE));
    //}
    
    //
    // Initialize macro repository
    //
    
    WarpScriptMacroRepository.init(properties);
    
    //
    // Initialize jar repository
    //
    
    WarpScriptJarRepository.init(properties);
  }
  
  private static Properties readConfig(InputStream file, Properties properties) throws IOException {
    return readConfig(new InputStreamReader(file), properties);
  }
  
  static Properties readConfig(Reader reader, Properties properties) throws IOException {
    //
    // Read the properties in the config file
    //
    
    if (null == properties) {
      properties = new Properties();
    }
    
    BufferedReader br = new BufferedReader(reader);
    
    int lineno = 0;
    
    int errorcount = 0;
    
    while (true) {
      String line = br.readLine();
      
      if (null == line) {
        break;
      }
      
      line = line.trim();
      lineno++;
      
      // Skip comments and blank lines
      if ("".equals(line) || line.startsWith("//") || line.startsWith("#") || line.startsWith("--")) {
        continue;
      }
      
      // Lines not containing an '=' will emit warnings
      
      if (!line.contains("=")) {
        System.err.println("Line " + lineno + " is missing an '=' sign, skipping.");
        continue;
      }
      
      String[] tokens = line.split("=");
      
      if (tokens.length > 2) {
        System.err.println("Invalid syntax on line " + lineno + ", will force an abort.");
        errorcount++;
        continue;
      }
      
      if (tokens.length < 2) {
        System.err.println("Empty value for property '" + tokens[0] + "', ignoring.");
        continue;
      }

      // Remove URL encoding if a '%' sign is present in the token
      for (int i = 0; i < tokens.length; i++) {
        if (tokens[i].contains("%")) {
          tokens[i] = URLDecoder.decode(tokens[i], "UTF-8");
        }
        tokens[i] = tokens[i].trim();
      }
      
      //
      // Ignore empty properties
      //
      
      if ("".equals(tokens[1])) {
        continue;
      }
      
      //
      // Set property
      //
      
      properties.setProperty(tokens[0], tokens[1]);
    }
    
    br.close();
    
    if (errorcount > 0) {
      System.err.println("Aborting due to " + errorcount + " error" + (errorcount > 1 ? "s" : "") + ".");
      System.exit(-1);
    }
    
    //
    // Now override properties with system properties
    //

    Properties sysprops = System.getProperties();

    for (Entry<Object, Object> entry : sysprops.entrySet()) {
      String name = entry.getKey().toString();
      String value = entry.getValue().toString();

      // URL Decode name/value if needed
      if (name.contains("%")) {
        name = URLDecoder.decode(name, "UTF-8");
      }
      if (value.contains("%")) {
        value = URLDecoder.decode(value, "UTF-8");
      }

      // Override property
      properties.setProperty(name, value);
    }
    
    //
    // Now expand ${xxx} contstructs
    //
    
    Pattern VAR = Pattern.compile(".*\\$\\{([^}]+)\\}.*");
    
    Set<String> emptyProperties = new HashSet<String>();
    
    for (Entry<Object,Object> entry: properties.entrySet()) {
      String name = entry.getKey().toString();
      String value = entry.getValue().toString();
      
      //
      // Replace '' with the empty string
      //
      
      if ("''".equals(value)) {
        value = "";
      }
      
      int loopcount = 0;
      
      while(true) {
        Matcher m = VAR.matcher(value);
        
        if (m.matches()) {
          String var = m.group(1);
          
          if (properties.containsKey(var)) {
            value = value.replace("${" + var + "}", properties.getProperty(var));              
          } else {
            System.err.println("Property '" + var + "' referenced in property '" + name + "' is unset, unsetting '" + name + "'");
            value = null;
          }
        } else {
          break;
        }
        
        if (null == value) {
          break;
        }
        
        loopcount++;
        
        if (loopcount > 100) {
          System.err.println("Hmmm, that's embarassing, but I've been dereferencing variables " + loopcount + " times trying to set a value for '" + name + "'.");
          System.exit(-1);
        }
      }
      
      if (null == value) {
        emptyProperties.add(name);
      } else {
        properties.setProperty(name, value);
      }
    }
    
    //
    // Remove empty properties
    //
    
    for (String property: emptyProperties) {
      properties.remove(property);
    }
    
    return properties;
  }

  public static Properties getProperties() {
    if (null == properties) {
      return null;
    }
    return (Properties) properties.clone();
  }
}
