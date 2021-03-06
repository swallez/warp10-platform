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

package io.warp10.continuum.egress;

import io.warp10.continuum.Configuration;
import io.warp10.continuum.store.Constants;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class CORSHandler extends AbstractHandler {
  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    if ("OPTIONS".equals(baseRequest.getMethod())) {
      baseRequest.setHandled(true);
    } else {
      return;
    }    
    
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "OPTIONS,POST");
    response.setHeader("Access-Control-Allow-Headers", Constants.getHeader(Configuration.HTTP_HEADER_TOKENX));
    // Allow to cache preflight response for 30 days
    response.setHeader("Access-Control-Max-Age", "" + 24 * 3600 * 30);
    
    response.setStatus(HttpServletResponse.SC_OK);
  }
}
