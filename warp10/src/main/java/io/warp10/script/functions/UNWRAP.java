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

package io.warp10.script.functions;

import io.warp10.continuum.gts.GTSWrapperHelper;
import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.continuum.store.thrift.data.GTSWrapper;
import io.warp10.crypto.OrderPreservingBase64;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;

import java.util.ArrayList;
import java.util.List;

import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;

import com.google.common.base.Charsets;

/**
 * Unwrap a GTS from GTSWrapper
 */
public class UNWRAP extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public UNWRAP(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();
    
    if (!(top instanceof String) && !(top instanceof List)) {
      throw new WarpScriptException(getName() + " operates on a string or a list thereof.");
    }
    
    List<String> inputs = new ArrayList<String>();
    
    if (top instanceof String) {
      inputs.add(top.toString());
    } else {
      for (Object o: (List) top) {
        if (!(o instanceof String)) {
          throw new WarpScriptException(getName() + " operates on a string or a list thereof.");
        }
        inputs.add(o.toString());
      }
    }
    
    List<Object> outputs = new ArrayList<Object>();
    
    for (String s: inputs) {
      byte[] bytes = OrderPreservingBase64.decode(s.getBytes(Charsets.US_ASCII));
      
      TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory());
      
      try {
        GTSWrapper wrapper = new GTSWrapper();
        
        deser.deserialize(wrapper, bytes);
        
        GeoTimeSerie gts = GTSWrapperHelper.fromGTSWrapperToGTS(wrapper);
        
        outputs.add(gts);
      } catch (TException te) {
        throw new WarpScriptException(getName() + " failed to unwrap GTS.");
      }      
    }
    
    if (top instanceof String) {
      stack.push(outputs.get(0));      
    } else {
      stack.push(outputs);
    }
    
    return stack;
  }  
}
