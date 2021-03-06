/*
 * Copyright 2011, 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.powertac.samplebroker.core;

//
import java.lang.Thread;

//import org.apache.log4j.Logger;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * This is the top level of the Power TAC server.
 * @author John Collins
 */
public class BrokerMain
{
  //static private Logger log = Logger.getLogger(BrokerMain.class);
    
  /**
   * Sets up the broker. Single command-line arg is the username
   */
  public static void main (String[] args)
      throws java.lang.InterruptedException
  {
      // replaced BrokerRunner with MultipleBrokerRunner
      MultipleBrokerRunner mbr = new MultipleBrokerRunner();
      mbr.processCmdLine(args);

      // sleep till the shutdown hook is enabled
      while(true) {
          Thread.sleep(1000);
          }
      
    // if we get here, it's time to exit
    //System.exit(0);
  }
}
