/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.util;

import java.io.IOException;
import java.util.Date;
import java.util.Calendar;

import org.apache.hadoop.util.ServletUtil;
import org.apache.hama.bsp.JobStatus;

public class BSPServletUtil extends ServletUtil {

  public static final String HTML_TAIL = "<hr />\n"
      + "<a href='http://incubator.apache.org/hama/'>Hama</a>, "
      + Calendar.getInstance().get(Calendar.YEAR) + ".\n" + "</body></html>";

  /**
   * HTML footer to be added in the jsps.
   * 
   * @return the HTML footer.
   */
  public static String htmlFooter() {
    return HTML_TAIL;
  }

  /**
   * Method used to generate the Job table for Job pages.
   * 
   * @param label display heading to be used in the job table.
   * @param jobs vector of jobs to be displayed in table.
   * @param refresh refresh interval to be used in jobdetails page.
   * @param rowId beginning row id to be used in the table.
   * @return generated HTML
   * @throws IOException
   */
  public static String generateJobTable(String label, JobStatus[] jobs,
      int refresh, int rowId) throws IOException {

    StringBuffer sb = new StringBuffer();

    if (jobs.length > 0) {
      sb.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");
      sb.append("<tr><th>Jobid</th>" + "<th>User</th>" + "<th>Name</th>"
          + "<th>SuperStep</th>" + "<th>Starttime</th>" + "</tr>\n");
      for (JobStatus status : jobs) {
        sb.append("<tr><td><a href=\"bspjob.jsp?jobid="+status.getJobID()+ "\">");
        sb.append(status.getJobID());
        sb.append("</a></td><td>");
        sb.append(status.getUsername());
        sb.append("</td><td>");
        sb.append(status.getName());
        sb.append("</td><td>");
        sb.append(status.getSuperstepCount());
        sb.append("</td><td>");
        sb.append(new Date(status.getStartTime()));
        sb.append("</td></tr>\n");
      }
      sb.append("</table>");
    } else {
      sb.append("No jobs found!");
    }

    return sb.toString();
  }

}