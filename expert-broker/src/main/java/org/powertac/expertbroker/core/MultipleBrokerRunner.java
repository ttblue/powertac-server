/*
 * Copyright 2012 the original author or authors.
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
package org.powertac.expertbroker.core;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;
import java.util.Map;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Multi-session, multiple broker runner. The Spring context is re-built for each
 * session.
 * Configuration for each broker is specified in separate broker___.properties file
 * By default, they will be searched for in the directory config under the PWD
 * unless the environment variable BROKER_CONFIG_DIR is specified
 * @author John Collins
 */
public class MultipleBrokerRunner
{
    private AbstractApplicationContext context;
 
    // the container that holds instances of multiple brokers
    private Vector<PowerTacBroker> brokerList;

    // the environment variable that points to the config directory
    private String brokerConfigDir="BROKER_CONFIG_DIR";

    // the list of config files (if present)
    File[] configFiles;
  
    public MultipleBrokerRunner ()
    {
        super();

        // grab a hold of the environment variables
        Map<String, String> env = System.getenv();

        // we default to the PWD 
        File dir = new File(env.get("PWD"));
    
        // if we have brokerConfigDir in the keyset
        if(env.containsKey(brokerConfigDir)) {
            // use the path defined in the environment
            dir = new File(env.get(brokerConfigDir));
        }
    
        // construct a FileNameFilter functor
        FilenameFilter filter = new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    if(name.endsWith(".properties")) {
                        return true;
                    }
                    return false;
                }
            };
    
        // gather all the configuration files
        configFiles = dir.listFiles(filter);

        // instantiate brokerList
        brokerList = new Vector<PowerTacBroker>();
    }
  
    public void processCmdLine (String[] args)
    {
        // complain and bail if no configuration files were found
        if (0 == configFiles.length) {
            System.out.println("No configuration files were found");
            System.exit(-1);
        }
      
        OptionParser parser = new OptionParser();
        OptionSpec<String> jmsUrlOption =
            parser.accepts("jms-url").withRequiredArg().ofType(String.class);
        OptionSpec<File> configOption = 
            parser.accepts("config").withRequiredArg().ofType(File.class);
        OptionSpec<Integer> repeatCountOption = 
            parser.accepts("repeat-count").withRequiredArg().ofType(Integer.class);
        OptionSpec<Integer> repeatHoursOption = 
            parser.accepts("repeat-hours").withRequiredArg().ofType(Integer.class);
        OptionSpec<String> queueNameOption =
            parser.accepts("queue-name").withRequiredArg().ofType(String.class);
        OptionSpec<String> serverQueueOption =
            parser.accepts("server-queue").withRequiredArg().ofType(String.class);
        parser.accepts("no-ntp");
 
        // do the parse
        OptionSet options = parser.parse(args);

        File configFile = null;
        String jmsUrl = null;
        boolean noNtp = false;
        String queueName = null;
        String serverQueue = null;
        Integer repeatCount = 1;
        long end = 0l;
    
        try {
            // process broker options
            System.out.println("Options: ");
            if (options.has(configOption)) {
                configFile = options.valueOf(configOption);
                System.out.println("  config=" + configFile.getName());
            }
            if (options.has(jmsUrlOption)) {
                jmsUrl = options.valueOf(jmsUrlOption);
                System.out.println("  jms-url=" + jmsUrl);
            }
            if (options.has("no-ntp")) {
                noNtp = true;
                System.out.println("  no ntp - estimate offset");
            }
            if (options.has(repeatCountOption)) {
                repeatCount = options.valueOf(repeatCountOption);
                System.out.println("  repeat " + repeatCount + " times");
            }
            else if (options.has(repeatHoursOption)) {
                Integer repeatHours = options.valueOf(repeatCountOption);
                System.out.println("  repeat for " + repeatHours + " hours");
                long now = new Date().getTime();
                end = now + 1000 * 3600 * repeatHours;
            }
            if (options.has(queueNameOption)) {
                queueName = options.valueOf(queueNameOption);
                System.out.println("  queue-name=" + queueName);
            }
            if (options.has(serverQueueOption)) {
                serverQueue = options.valueOf(serverQueueOption);
                System.out.println("  server-queue=" + serverQueue);
            }
      
            // at this point, we are either done, or we need to repeat
            int counter = 0;
            while ((null != repeatCount && repeatCount > 0) ||
                   (new Date().getTime() < end)) {
                counter += 1;

                // Re-open the logfiles
                reopenLogs(counter);
        
                // initialize and run
                if (null == context) {
                    context = new ClassPathXmlApplicationContext("broker.xml");
                }
                else {
                    context.close();
                    context.refresh();
                }
                // get the broker reference and delegate the rest
                context.registerShutdownHook();

                // here we iterate through the list of configuration files, create a PowerTacBroker instance
                // for each of them and kick them off
                brokerList.clear();
                for(int idx = 0; idx < configFiles.length; ++idx) {
                    PowerTacBroker broker = (PowerTacBroker)context.getBeansOfType(PowerTacBroker.class).values().toArray()[0];
                    System.out.println("Starting session " + counter + " for broker " + idx);
                    broker.startSession(configFiles[idx], jmsUrl, noNtp, queueName, serverQueue, end);
                    brokerList.addElement(broker);
                }
        
                if (null != repeatCount)
                    repeatCount -= 1;
            }
        }
        catch (OptionException e) {
            System.err.println("Bad command argument: " + e.toString());
        }
    }

    // reopen the logfiles for each session
    private void reopenLogs(int counter)
    {
        Logger root = Logger.getRootLogger();
        @SuppressWarnings("unchecked")
            Enumeration<Appender> rootAppenders = root.getAllAppenders();
        FileAppender logOutput = (FileAppender) rootAppenders.nextElement();
        // assume there's only the one, and that it's a file appender
        logOutput.setFile("log/broker" + counter + ".trace");
        logOutput.activateOptions();
    
        Logger state = Logger.getLogger("State");
        @SuppressWarnings("unchecked")
            Enumeration<Appender> stateAppenders = state.getAllAppenders();
        FileAppender stateOutput = (FileAppender) stateAppenders.nextElement();
        // assume there's only the one, and that it's a file appender
        stateOutput.setFile("log/broker" + counter + ".state");
        stateOutput.activateOptions();
    }
}
