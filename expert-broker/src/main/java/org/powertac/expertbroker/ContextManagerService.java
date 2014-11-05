/*
 * Copyright (c) 2012 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.expertbroker;

import java.util.HashMap;

import org.apache.log4j.Logger;
import org.apache.log4j.Level;
import org.joda.time.Instant;
import org.powertac.common.Broker;
import org.powertac.common.BankTransaction;
import org.powertac.common.CashPosition;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.Timeslot;
import org.powertac.common.msg.BrokerAccept;
import org.powertac.common.msg.DistributionReport;
import org.powertac.common.msg.SimEnd;
import org.powertac.common.msg.SimPause;
import org.powertac.common.msg.SimResume;
import org.powertac.common.msg.SimStart;
import org.powertac.common.msg.TimeslotComplete;
import org.powertac.common.msg.TimeslotUpdate;
import org.powertac.expertbroker.core.MessageDispatcher;
import org.powertac.expertbroker.core.PowerTacBroker;
import org.powertac.expertbroker.interfaces.BrokerContext;
import org.powertac.expertbroker.interfaces.Initializable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles incoming context and bank messages with example behaviors. 
 * @author John Collins
 */
@Service
public class ContextManagerService
implements Initializable
{
	static private Logger log = Logger.getLogger(ContextManagerService.class);

	BrokerContext master;
	
	// Broker profits for other brokers and self.
	private HashMap<Broker, Double> brokerCashPositions;
	private HashMap<Broker, Double> lastBrokerProfits;
	
	// current cash balance
	private double myCashPosition = 0;
	private double myLastProfit;


	//  @SuppressWarnings("unchecked")
	@Override
	public void initialize (BrokerContext broker)
	{
		log.setLevel(Level.INFO);
		master = broker;
		// --- no longer needed ---
		//    for (Class<?> clazz: Arrays.asList(BankTransaction.class,
		//                                       CashPosition.class,
		//                                       DistributionReport.class,
		//                                       Competition.class,
		//                                       java.util.Properties.class)) {
		//      broker.registerMessageHandler(this, clazz);
		//    }    
	}

	// -------------------- message handlers ---------------------
	//
	// Note that these arrive in JMS threads; If they share data with the
	// agent processing thread, they need to be synchronized.

	/**
	 * BankTransaction represents an interest payment. Value is positive for 
	 * credit, negative for debit. 
	 */
	public void handleMessage (BankTransaction btx)
	{
		// TODO - handle this
	}

	/**
	 * CashPosition updates our current bank balance.
	 */
	public void handleMessage (CashPosition cp)
	{	
		Broker theBroker = cp.getBroker();
		if (master.getBrokerUsername().equals(theBroker.getUsername())) {
			if (theBroker != master.getBroker())
				// strange bug, seems harmless for now
				log.info("Resolution failed for broker " + theBroker.getUsername());
			myLastProfit = cp.getBalance() - myCashPosition;
			myCashPosition = cp.getBalance();
			log.info("Cash position: " + myCashPosition);
		}
		else {
			
			if (lastBrokerProfits.get(theBroker) == null)
				// It seems that for the first time step,
				// whoever has the most money will have the least loss.
				lastBrokerProfits.put(theBroker, 0.0);
			else
				lastBrokerProfits.put(theBroker, cp.getBalance() - brokerCashPositions.get(theBroker));
			brokerCashPositions.put(cp.getBroker(), cp.getBalance());
		}
	}
	
	/**
	 * @param broker
	 * @return the cash position of broker.
	 */
	public double getCashPosition(Broker broker) {

		if (master.getBrokerUsername().equals(broker.getUsername())) {
			if (broker != master.getBroker())
				// strange bug, seems harmless for now
				log.info("Resolution failed for broker " + broker.getUsername());
			return myCashPosition;
		}
		else {
			if (brokerCashPositions.get(broker) == null) {
				//log.error("This broker doesn't exist in my database");
				log.warn("This broker doesn't exist in my database");
				return 0.0;
			}
			return brokerCashPositions.get(broker);
		}
	}
	
	/**
	 * @param broker
	 * @return the last profit of broker.
	 */
	public double getLastProfit(Broker broker) {

		if (master.getBrokerUsername().equals(broker.getUsername())) {
			if (broker != master.getBroker())
				// strange bug, seems harmless for now
				log.info("Resolution failed for broker " + broker.getUsername());
			return myLastProfit;
		}
		else {
			if (lastBrokerProfits.get(broker) == null) {
				//log.error("This broker doesn't exist in my database");
				log.warn("This broker doesn't exist in my database");
				return 0.0;
			}
			return lastBrokerProfits.get(broker);
		}
	}
	
	/**
	 * @return the cash positions of all broker.
	 */
	public HashMap<Broker, Double> getAllCashPosition() {
		return brokerCashPositions;
	}
	
	/**
	 * @return the last profits of all broker.
	 */
	public HashMap<Broker, Double> getAllLastProfits() {
		return lastBrokerProfits;
	}	

	/**
	 * DistributionReport gives total consumption and production for the timeslot,
	 * summed across all brokers.
	 */
	public void handleMessage (DistributionReport dr)
	{
		// TODO - use this data
	}

	/**
	 * Handles the Competition instance that arrives at beginning of game.
	 * Here we capture all the customer records so we can keep track of their
	 * subscriptions and usage profiles.
	 */
	public void handleMessage (Competition comp)
	{
		// TODO - process competition properties
	}

	/**
	 * Receives the server configuration properties.
	 */
	public void handleMessage (java.util.Properties serverProps)
	{
		// TODO - adapt to the server setup.
	}
}
