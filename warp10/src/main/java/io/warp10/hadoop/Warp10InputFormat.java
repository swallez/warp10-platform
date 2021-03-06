package io.warp10.hadoop;

import io.warp10.continuum.store.Constants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import com.fasterxml.sort.SortConfig;
import com.fasterxml.sort.std.TextFileSorter;

public class Warp10InputFormat implements InputFormat<Text, BytesWritable> {
  
  /**
   * URL of split endpoint
   */
  public static final String PROPERTY_WARP10_SPLITS_ENDPOINT = "warp10.splits.endpoint";
  
  /**
   * List of fallback fetchers
   */
  public static final String PROPERTY_WARP10_FETCHER_FALLBACKS = "warp10.fetcher.fallbacks";
  
  /**
   * Protocol to use when contacting the fetcher (http or https), defaults to http
   */
  public static final String PROPERTY_WARP10_FETCHER_PROTOCOL = "warp10.fetcher.protocol";
  public static final String DEFAULT_WARP10_FETCHER_PROTOCOL = "http";
  
  /**
   * Port to use when contacting the fetcher, defaults to 8881
   */
  public static final String PROPERTY_WARP10_FETCHER_PORT = "warp10.fetcher.port";
  public static final String DEFAULT_WARP10_FETCHER_PORT = "8881";
  
  /**
   * URL Path of the fetcher, defaults to "/api/v0/sfetch"
   */
  public static final String PROPERTY_WARP10_FETCHER_PATH = "warp10.fetcher.path";
  public static final String DEFAULT_WARP10_FETCHER_PATH = Constants.API_ENDPOINT_SFETCH;

  /**
   * GTS Selector
   */
  public static final String PROPERTY_WARP10_SPLITS_SELECTOR = "warp10.splits.selector";
  
  /**
   * Token to use for selecting GTS
   */
  public static final String PROPERTY_WARP10_SPLITS_TOKEN = "warp10.splits.token";
  
  @Override
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    
    List<String> fallbacks = new ArrayList<String>();
    
    if (null != job.get(PROPERTY_WARP10_FETCHER_FALLBACKS)) {
      String[] servers = job.get(PROPERTY_WARP10_FETCHER_FALLBACKS).split(",");
      for (String server: servers) {
        fallbacks.add(server);
      }
    }
    
    //
    // Issue a call to the /splits endpoint to retrieve the individual splits
    //

    StringBuilder sb = new StringBuilder();
    sb.append(job.get(PROPERTY_WARP10_SPLITS_ENDPOINT));
    sb.append("?");
    sb.append(Constants.HTTP_PARAM_SELECTOR);
    sb.append("=");
    sb.append(URLEncoder.encode(job.get(PROPERTY_WARP10_SPLITS_SELECTOR), "UTF-8"));
    sb.append("&");
    sb.append(Constants.HTTP_PARAM_TOKEN);
    sb.append("=");
    sb.append(job.get(PROPERTY_WARP10_SPLITS_TOKEN));
    
    URL url = new URL(sb.toString());
    
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    
    conn.setDoInput(true);
    
    InputStream in = conn.getInputStream();
    
    File tmpfile = File.createTempFile("Warp10InputFormat-", "-in");
    System.out.println(tmpfile);
    tmpfile.deleteOnExit();
    
    OutputStream out = new FileOutputStream(tmpfile);
    
    BufferedReader br = new BufferedReader(new InputStreamReader(in));
    PrintWriter pw = new PrintWriter(out);
    
    int count = 0;
    
    Map<String,AtomicInteger> perServer = new HashMap<String,AtomicInteger>();
    
    while(true) {
      String line = br.readLine();
      if (null == line) {
        break;
      }
      // Count the total number of splits
      count++;
      // Count the number of splits per RS
      String server = line.substring(0, line.indexOf(' '));
      
      AtomicInteger scount = perServer.get(server);
      if (null == scount) {
        scount = new AtomicInteger(0);
        perServer.put(server, scount);
      }
      scount.addAndGet(1);
      
      pw.println(line);
    }
    
    out.close();
    br.close();
    in.close();
    conn.disconnect();
    
    TextFileSorter sorter = new TextFileSorter(new SortConfig().withMaxMemoryUsage(64000000L));
    
    File outfile = File.createTempFile("Warp10InfputFormat-", "-out");
    outfile.deleteOnExit();
    
    in = new FileInputStream(tmpfile);
    out = new FileOutputStream(outfile);
    
    sorter.sort(in, out);
    
    in.close();
    out.close();
    
    //
    // Do a naive split generation, using the RegionServer as the ideal fetcher. We will need
    // to adapt this later so we ventilate the splits on all fetchers if we notice that a single
    // fetcher gets pounded too much
    //
    
    // Compute the average number of splits per combined split
    int avgsplitcount = (int) Math.ceil((double) count / numSplits);
    
    List<Warp10InputSplit> splits = new ArrayList<Warp10InputSplit>();
    
    br = new BufferedReader(new FileReader(outfile));
    
    Warp10InputSplit split = new Warp10InputSplit();
    String lastserver = null;
    int subsplits = 0;
    
    while(true) {
      String line = br.readLine();
      
      if (null == line) {
        break;
      }
      
      String[] tokens = line.split("\\s+");
      
      // If the server changed or we've reached the maximum split size, flush the current split.
      
      if (null != lastserver && !lastserver.equals(tokens[0]) || avgsplitcount == subsplits) {
        // Add fallback fetchers, shuffle them first
        Collections.shuffle(fallbacks);
        for (String fallback: fallbacks) {
          split.addFetcher(fallback);
        }
        splits.add(split.build());
        split = new Warp10InputSplit();
        subsplits = 0;
      }
      
      subsplits++;
      split.addEntry(tokens[0], tokens[2]);
    }
    
    br.close();
    
    if (subsplits > 0) {
      // Add fallback fetchers, shuffle them first
      Collections.shuffle(fallbacks);
      for (String fallback: fallbacks) {
        split.addFetcher(fallback);
      }      
      splits.add(split.build());
    }
    
    return splits.toArray(new Warp10InputSplit[0]);
//    //
//    // We know we have 'count' splits to combine and we know how many splits are hosted on each
//    // server
//    //
//    
//    // Compute the average number of splits per combined split
//    int avgsplitcount = (int) Math.ceil((double) count / numSplits);
//    
//    // Compute the average number of splits per server
//    int avgsplitpersrv = (int) Math.ceil((double) count / perServer.size());
//    
//    //
//    // Determine the number of ideal (i.e. associated with the right server) combined splits
//    // per server
//    //
//    
//    Map<String,AtomicInteger> idealcount = new HashMap<String,AtomicInteger>();
//    
//    for (Entry<String,AtomicInteger> entry: perServer.entrySet()) {
//      idealcount.put(entry.getKey(), new AtomicInteger(Math.min((int) Math.ceil(entry.getValue().doubleValue() / avgsplitcount), avgsplitpersrv)));
//    }
//    
//    //
//    // Compute the number of available slots per server after the maximum ideal combined splits
//    // have been allocated
//    //
//    
//    Map<String,AtomicInteger> freeslots = new HashMap<String,AtomicInteger>();
//    
//    for (Entry<String,AtomicInteger> entry: perServer.entrySet()) {
//      if (entry.getValue().get() < avgsplitpersrv) {
//        freeslots.put(entry.getKey(), new AtomicInteger(avgsplitpersrv - entry.getValue().get()));
//      }
//    }
//
//    //
//    // Generate splits
//    // We know the input file is sorted by server then region
//    //
//    
//    br = new BufferedReader(new FileReader(outfile));
//    
//    Warp10InputSplit split = null;
//    String lastsrv = null;
//    int subsplits = 0;
//    
//    List<Warp10InputSplit> splits = new ArrayList<Warp10InputSplit>();
//    
//    while(true) {
//      String line = br.readLine();
//      
//      if (null == line) {
//        break;
//      }
//      
//      // Split line into tokens
//      String[] tokens = line.split("\\s+");
//      
//      // If the srv changed, flush the split
//      if (null != lastsrv && lastsrv != tokens[0]) {
//        splits.add(split);
//        split = null;
//      }
//      
//      
//      if (null == splitsrv) {
//        splitsrv = tokens[0];
//        // Check if 'splitsrv' can host more splits
//        if (idealcount.get(splitsrv))
//      }
//      // Emit current split if it is full
//      
//      if (avgsplitcount == subsplits) {
//        
//      }
//    }
//    
//    System.out.println("NSPLITS=" + count);
//    
//    System.out.println("AVG=" + avgsplit);
//    System.out.println(perServer);
//    return null;
  }
  
  @Override
  public RecordReader<Text, BytesWritable> getRecordReader(InputSplit split, JobConf job, Reporter reporter) throws IOException {
    if (!(split instanceof Warp10InputSplit)) {
      throw new IOException("Invalid split type.");
    }
    
    return new Warp10RecordReader((Warp10InputSplit) split, job, reporter);
  }
}
