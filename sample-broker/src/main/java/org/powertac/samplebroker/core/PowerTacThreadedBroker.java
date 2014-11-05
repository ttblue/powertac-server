
// the package this object/class belongs to
package org.powertac.samplebroker.core;

// the base class we are deriving from 
import java.lang.Thread;

// the logger instance
import org.apache.log4j.Logger;

// class definition for File
import java.io.File;


// begin the class definition
class PowerTacThreadedBroker extends Thread {

    // instance of the logger (time being, reuse the existing one)
    static private Logger log = Logger.getLogger(PowerTacBroker.class);

    // the instance of the broker
    private PowerTacBroker broker;

    //
    File configFile;
    String jmsUrl;
    boolean noNtp;
    String queueName;
    String serverQueue;
    long end;

    // default constructor is private here
    private PowerTacThreadedBroker() {
        super();
    }

    // public interfaces
    public PowerTacThreadedBroker(PowerTacBroker broker, File configFile, String jmsUrl, boolean noNtp, String queueName, String serverQueue, long end) {
        this.broker = broker;
        this.configFile = configFile;
        this.jmsUrl = jmsUrl;
        this.noNtp = noNtp;
        this.queueName = queueName;
        this.serverQueue = serverQueue;
        this.end = end;
    }

    // the run() method - this gets kicked off by calling the startThread method
    public void run() {

        // this blocks
        broker.startSession(configFile, jmsUrl, noNtp, queueName, serverQueue, end);
    }
    
};
