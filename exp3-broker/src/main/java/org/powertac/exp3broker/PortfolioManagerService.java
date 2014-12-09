/*
 * Copyright (c) 2012-2013 by the original author
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
package org.powertac.exp3broker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.Math;
import org.javatuples.Pair;

import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.Broker;
import org.powertac.common.CashPosition;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.Rate;
import org.powertac.common.RegulationRate;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TariffTransaction;
import org.powertac.common.TimeService;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.msg.BalancingControlEvent;
import org.powertac.common.msg.BalancingOrder;
import org.powertac.common.msg.CustomerBootstrapData;
import org.powertac.common.msg.TariffRevoke;
import org.powertac.common.msg.TariffStatus;
import org.powertac.common.repo.CustomerRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.exp3broker.core.BrokerPropertiesService;
import org.powertac.exp3broker.interfaces.Activatable;
import org.powertac.exp3broker.interfaces.BrokerContext;
import org.powertac.exp3broker.interfaces.Initializable;
import org.powertac.exp3broker.interfaces.MarketManager;
import org.powertac.exp3broker.interfaces.PortfolioManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles portfolio-management responsibilities for the broker. This
 * includes composing and offering tariffs, keeping track of customers and their
 * usage, monitoring tariff offerings from competing brokers.
 * 
 * A more complete broker implementation might split this class into two or
 * more classes; the keys are to decide which messages each class handles,
 * what each class does on the activate() method, and what data needs to be
 * managed and shared.
 * 
 * @author John Collins
 */
@Service // Spring creates a single instance at startup
public class PortfolioManagerService 
implements PortfolioManager, Initializable, Activatable
{
	static private Logger log = Logger.getLogger(PortfolioManagerService.class);

	private BrokerContext brokerContext; // master

	// Spring fills in Autowired dependencies through a naming convention
	@Autowired
	private BrokerPropertiesService propertiesService;

	@Autowired
	private TimeslotRepo timeslotRepo;

	@Autowired
	private TariffRepo tariffRepo;

	@Autowired
	private CustomerRepo customerRepo;

	// @Autowired
	// private MarketManager marketManager;

	@Autowired
	private TimeService timeService;

	// ---- Portfolio records -----
	// Customer records indexed by power type and by tariff. Note that the
	// CustomerRecord instances are NOT shared between these structures, because
	// we need to keep track of subscriptions by tariff.
	private HashMap<PowerType,
	HashMap<CustomerInfo, CustomerRecord>> customerProfiles;
	private HashMap<TariffSpecification, 
	HashMap<CustomerInfo, CustomerRecord>> customerSubscriptions;
	private HashMap<PowerType, List<TariffSpecification>> competingTariffs;

	// Configurable parameters for tariff composition
	// Override defaults in src/main/resources/config/broker.config
	// or in top-level config file
	@ConfigurableValue(valueType = "Double",
			description = "target profit margin")
	private double defaultMargin = 0.5;

	@ConfigurableValue(valueType = "Double",
			description = "Fixed cost/kWh")
	private double fixedPerKwh = -0.06;

	@ConfigurableValue(valueType = "Double",
			description = "Default daily meter charge")
	private double defaultPeriodicPayment = -1.0;

	// Expert variables
	private int numExperts = 100;
	private int bestExpert = 0;
	private int currentExpert = 0;
	private double currentQWeight = 1.0/numExperts;

	// Consumption variables
	private ArrayList<Double> expertConsumptionWeights;
	private ArrayList<Double> expertConsumptionPrices;

	// Production variables
	private ArrayList<Double> expertProductionWeights;
	private ArrayList<Double> expertProductionPrices;

	// current cash balance and last profit
	private double cash = 0.0;
	private double profit = 0.0;
	private double bestProfit = 1.0;
	private double worstLoss = -1.0;
	
	// Probability of randomly exploring
	private double gamma = 0.9;

	// the price at which we got the best profit
	private double pricePointForBestProfit = 0.0;	

	// keep track of timeslot
	private int firstTimeslot = 0;
	
	private HashMap<TariffSpecification, Double> currentProfit;
	
	public double meanMarketPrice = 0.0;
	public double estimatedEnergyCost = 0.0;

	/**
	 * Default constructor registers for messages, must be called after 
	 * message router is available.
	 */
	public PortfolioManagerService ()
	{
		super();

		expertConsumptionPrices 	= new ArrayList<Double>();
		expertConsumptionWeights 	= new ArrayList<Double>();

		expertProductionWeights 	= new ArrayList<Double>();
		expertProductionPrices 		= new ArrayList<Double>();

		currentProfit = new HashMap<TariffSpecification, Double>();
	}

	/**
	 * Per-game initialization. Configures parameters and registers
	 * message handlers.
	 */
	@Override // from Initializable
	//  @SuppressWarnings("unchecked")
	public void initialize (BrokerContext context)
	{
		this.brokerContext = context;
		propertiesService.configureMe(this);
		customerProfiles = new HashMap<PowerType,
				HashMap<CustomerInfo, CustomerRecord>>();
		customerSubscriptions = new HashMap<TariffSpecification,
				HashMap<CustomerInfo, CustomerRecord>>();
		competingTariffs = new HashMap<PowerType, List<TariffSpecification>>();
	}

	// -------------- data access ------------------

	/**
	 * Returns the CustomerRecord for the given type and customer, creating it
	 * if necessary.
	 */
	CustomerRecord getCustomerRecordByPowerType (PowerType type,
			CustomerInfo customer)
	{
		HashMap<CustomerInfo, CustomerRecord> customerMap =
				customerProfiles.get(type);
		if (customerMap == null) {
			customerMap = new HashMap<CustomerInfo, CustomerRecord>();
			customerProfiles.put(type, customerMap);
		}
		CustomerRecord record = customerMap.get(customer);
		if (record == null) {
			record = new CustomerRecord(customer);
			customerMap.put(customer, record);
		}
		return record;
	}

	/**
	 * Returns the customer record for the given tariff spec and customer,
	 * creating it if necessary. 
	 */
	CustomerRecord getCustomerRecordByTariff (TariffSpecification spec,
			CustomerInfo customer)
	{
		HashMap<CustomerInfo, CustomerRecord> customerMap =
				customerSubscriptions.get(spec);
		if (customerMap == null) {
			customerMap = new HashMap<CustomerInfo, CustomerRecord>();
			customerSubscriptions.put(spec, customerMap);
		}
		CustomerRecord record = customerMap.get(customer);
		if (record == null) {
			// seed with the generic record for this customer
			record =
					new CustomerRecord(getCustomerRecordByPowerType(spec.getPowerType(),
							customer));
			customerMap.put(customer, record);
		}
		return record;
	}

	/**
	 * Finds the list of competing tariffs for the given PowerType.
	 */
	List<TariffSpecification> getCompetingTariffs (PowerType powerType)
	{
		List<TariffSpecification> result = competingTariffs.get(powerType);
		if (result == null) {
			result = new ArrayList<TariffSpecification>();
			competingTariffs.put(powerType, result);
		}
		return result;
	}

	/**
	 * Adds a new competing tariff to the list.
	 */
	private void addCompetingTariff (TariffSpecification spec)
	{
		getCompetingTariffs(spec.getPowerType()).add(spec);
	}

	/**
	 * Returns total usage for a given timeslot (represented as a simple index).
	 */
	@Override
	public double collectUsage (int index)
	{
		double result = 0.0;
		for (HashMap<CustomerInfo, CustomerRecord> customerMap : customerSubscriptions.values()) {
			for (CustomerRecord record : customerMap.values()) {
				result += record.getUsage(index);
			}
		}
		return -result; // convert to needed energy account balance
	}

	// -------------- Message handlers -------------------
	/**
	 * Handles CustomerBootstrapData by populating the customer model 
	 * corresponding to the given customer and power type. This gives the
	 * broker a running start.
	 */
	public void handleMessage (CustomerBootstrapData cbd)
	{
		CustomerInfo customer =
				customerRepo.findByNameAndPowerType(cbd.getCustomerName(),
						cbd.getPowerType());
		CustomerRecord record = getCustomerRecordByPowerType(cbd.getPowerType(), customer);
		int offset = (timeslotRepo.currentTimeslot().getSerialNumber()
				- cbd.getNetUsage().length);
		int subs = record.subscribedPopulation;
		record.subscribedPopulation = customer.getPopulation();
		for (int i = 0; i < cbd.getNetUsage().length; i++) {
			record.produceConsume(cbd.getNetUsage()[i], i);
		}
		record.subscribedPopulation = subs;
	}

	/**
	 * CashPosition updates our current bank balance.
	 */
	public void handleMessage (CashPosition cp)
	{
		profit = cp.getBalance() - cash; 
		cash += profit;
		log.info("Cash position: " + cash);
		log.info("Profit: " + profit);

		// cannot update bestProfit here - it should be updated in improveTariffs method
	}

	/**
	 * Handles a TariffSpecification. These are sent by the server when new tariffs are
	 * published. If it's not ours, then it's a competitor's tariff. We keep track of 
	 * competing tariffs locally, and we also store them in the tariffRepo.
	 */
	public synchronized void handleMessage (TariffSpecification spec)
	{
		Broker theBroker = spec.getBroker();
		if (brokerContext.getBrokerUsername().equals(theBroker.getUsername())) {
			if (theBroker != brokerContext.getBroker())
				// strange bug, seems harmless for now
				log.info("Resolution failed for broker " + theBroker.getUsername());
			// if it's ours, just log it, because we already put it in the repo
			TariffSpecification original =
					tariffRepo.findSpecificationById(spec.getId());
			if (null == original)
				log.error("Spec " + spec.getId() + " not in local repo");
			log.info("published " + spec);
		}
		else {
			// otherwise, keep track of competing tariffs, and record in the repo
			addCompetingTariff(spec);
			tariffRepo.addSpecification(spec);
		}
	}

	/**
	 * Handles a TariffStatus message. This should do something when the status
	 * is not SUCCESS.
	 */
	public synchronized void handleMessage (TariffStatus ts)
	{
		log.info("TariffStatus: " + ts.getStatus());
	}

	/**
	 * Handles a TariffTransaction. We only care about certain types: PRODUCE,
	 * CONSUME, SIGNUP, and WITHDRAW.
	 */
	public synchronized void handleMessage(TariffTransaction ttx)
	{
		// make sure we have this tariff
		TariffSpecification newSpec = ttx.getTariffSpec();
		if (newSpec == null) {
			log.error("TariffTransaction type=" + ttx.getTxType()
					+ " for unknown spec");
		}
		else {
			TariffSpecification oldSpec =
					tariffRepo.findSpecificationById(newSpec.getId());
			if (oldSpec != newSpec) {
				log.error("Incoming spec " + newSpec.getId() + " not matched in repo");
			}
		}
		TariffTransaction.Type txType = ttx.getTxType();
		CustomerRecord record = getCustomerRecordByTariff(ttx.getTariffSpec(),
				ttx.getCustomerInfo());

		if (TariffTransaction.Type.SIGNUP == txType) {
			// keep track of customer counts
			record.signup(ttx.getCustomerCount());
		}
		else if (TariffTransaction.Type.WITHDRAW == txType) {
			// customers presumably found a better deal
			record.withdraw(ttx.getCustomerCount());
		}
		else if (TariffTransaction.Type.PRODUCE == txType) {
			// if ttx count and subscribe population don't match, it will be hard
			// to estimate per-individual production
			if (ttx.getCustomerCount() != record.subscribedPopulation) {
				log.warn("production by subset " + ttx.getCustomerCount() +
						" of subscribed population " + record.subscribedPopulation);
			}
			record.produceConsume(ttx.getKWh(), ttx.getPostedTime());
		}
		else if (TariffTransaction.Type.CONSUME == txType) {
			if (ttx.getCustomerCount() != record.subscribedPopulation) {
				log.warn("consumption by subset " + ttx.getCustomerCount() +
						" of subscribed population " + record.subscribedPopulation);
			}
			record.produceConsume(ttx.getKWh(), ttx.getPostedTime());      
		}
	}

	/**
	 * Handles a TariffRevoke message from the server, indicating that some
	 * tariff has been revoked.
	 */
	public synchronized void handleMessage (TariffRevoke tr)
	{
		Broker source = tr.getBroker();
		log.info("Revoke tariff " + tr.getTariffId()
				+ " from " + tr.getBroker().getUsername());
		// if it's from some other broker, we need to remove it from the
		// tariffRepo, and from the competingTariffs list
		if (!(source.getUsername().equals(brokerContext.getBrokerUsername()))) {
			log.info("clear out competing tariff");
			TariffSpecification original =
					tariffRepo.findSpecificationById(tr.getTariffId());
			if (null == original) {
				log.warn("Original tariff " + tr.getTariffId() + " not found");
				return;
			}
			tariffRepo.removeSpecification(original.getId());
			List<TariffSpecification> candidates =
					competingTariffs.get(original.getPowerType());
			if (null == candidates) {
				log.warn("Candidate list is null");
				return;
			}
			candidates.remove(original);
		}
	}

	/**
	 * Handles a BalancingControlEvent, sent when a BalancingOrder is
	 * exercised by the DU.
	 */
	public synchronized void handleMessage (BalancingControlEvent bce)
	{
		log.info("BalancingControlEvent " + bce.getKwh());
	}

	// --------------- activation -----------------
	/**
	 * Called after TimeslotComplete msg received. Note that activation order
	 * among modules is non-deterministic.
	 */
	@Override // from Activatable
	public synchronized void activate (int timeslotIndex)
	{
		if (firstTimeslot == 0) { 
			firstTimeslot = timeslotIndex;
		}
		else if ((timeslotIndex - firstTimeslot) % 6 != 0) {
			waitToPublishTariffs();
			return;
		}

		//log.info("Time slot index: " + timeslotIndex);
		if (customerSubscriptions.size() == 0) {
			// we (most likely) have no tariffs
			log.info("Time slot index first: " + timeslotIndex);
			createInitialTariffs();
		}
		else if ((timeslotIndex - firstTimeslot) > 0){
			
			if (gamma > 0.5) {
				gamma = 0.9 - (timeslotIndex - firstTimeslot)*0.4/5/6;
			}
			
			// we have some, are they good enough?
			log.info("Time slot index after: " + timeslotIndex);
			waitToPublishTariffs();
			improveTariffs(timeslotIndex);
		}
	}
	
	// Creates initial tariffs for the main power types. These are simple
	// fixed-rate two-part tariffs that give the broker a fixed margin.
	private void createInitialTariffs ()
	{
		// remember that market prices are per mwh, but tariffs are by kwh
		double marketPrice = meanMarketPrice / 1000.0;
		double upperBound = 9;
		double lowerBound = 0.25;
		double startPrice = (1 + upperBound) * marketPrice;
		double endPrice = (1 - lowerBound) * marketPrice;
		double price = startPrice;
		
		double initPrice = (1 + upperBound/2) * marketPrice;

		while(price < endPrice) {
			// Initialize consumption variables
			expertConsumptionPrices.add(price);
			expertConsumptionWeights.add(Math.log(1.0 / (double) numExperts));

			// Initialize production variables
			expertProductionPrices.add(price);
			expertProductionWeights.add(Math.log(1.0 / (double) numExperts));
			
			price += (endPrice - startPrice) / numExperts;
		}

		log.warn("startPrice " + startPrice + " endPrice " + endPrice);

		log.info("ExpertConsumptionPrices.size() " + expertConsumptionPrices.size());

		// for each power type representing a customer population,
		// create a tariff that's better than what's available

		currentExpert = numExperts;
		for (int i=0; i < numExperts; i ++) {
			if (startPrice >= expertConsumptionPrices.get(i) && startPrice < expertConsumptionPrices.get(i+1)) {
				currentExpert = i;
				bestExpert = i;
				break;
			}
		}

		for (PowerType pt : customerProfiles.keySet()) {
			// we'll just do fixed-rate tariffs for now
			double rateValue = 0.0;
			if (pt.isConsumption()) {
				rateValue = ((expertConsumptionPrices.get(currentExpert) + fixedPerKwh) * (1 + defaultMargin));
			}
			else {
				rateValue = -2.0 * expertConsumptionPrices.get(currentExpert);
				continue;
			}
			//      if (pt.isInterruptible()) {
			//        rateValue *= 0.7; // Magic number!! price break for interruptible
			//      }

			TariffSpecification spec =
					new TariffSpecification(brokerContext.getBroker(), pt)
			.withPeriodicPayment(defaultPeriodicPayment);
			Rate rate = new Rate().withValue(rateValue);

			spec.addRate(rate);
			customerSubscriptions.put(spec, new HashMap<CustomerInfo, CustomerRecord>());
			tariffRepo.addSpecification(spec);
			currentProfit.put(spec, 0.0);
			brokerContext.sendMessage(spec);
			break;
		}
	}
	
	private void waitToPublishTariffs() {
		for (TariffSpecification spec :
			tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker())) {
//			PowerType pt = spec.getPowerType();
			

			// Method 1: Estimate profit from subscription and market manager data 
//			HashMap<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
//			
//			double totalUsage = 0.0;
//			int numSubscribers = 0;
//			
//			for (CustomerRecord cr : customerMap.values())
//			{
//				totalUsage += cr.getUsage(0);
//				numSubscribers += cr.subscribedPopulation;
//			}
//			
//			double totalRevenue = totalUsage*spec.getRates().get(0).getValue();
//			
//			currentProfit.put(spec, currentProfit.get(spec) + totalRevenue - estimatedEnergyCost*totalUsage);
//			
//			System.out.println("Number of subscribers: " + numSubscribers);
//			System.out.println("Total Usage: " + totalUsage + "\nRate" + spec.getRates().get(0).getValue());
//			System.out.println("Total Revenue: " + totalRevenue + "\nEnergy Cost: " + estimatedEnergyCost*totalUsage + "\nCurrent profit: " + currentProfit.get(spec));
			

			// Method 2: If we're only using one tariff, we can just use our cash position
			currentProfit.put(spec, currentProfit.get(spec) + profit);

			return;		
		}
	}

	private void renormalizeWeights () {
		double totalConsumptionWeight = 0.0;
		for (double w : expertConsumptionWeights) {
//			System.out.println("Log Weight: " + w + " Weight: " + Math.exp(w));
			totalConsumptionWeight += Math.exp(w);
//			System.out.println("Total so far: " + totalConsumptionWeight);
		}
		
		double totLogWeight = Math.log(totalConsumptionWeight);
		for (int i = 0; i < numExperts; i++) {
//			System.out.println("TotalConsumptionWeight: " +  totalConsumptionWeight + " TotalLogWeight: " + totLogWeight);
//			System.out.println("Set Consumption Weight : " + i + " to: " +  (expertConsumptionWeights.get(i) - totLogWeight) );
			expertConsumptionWeights.set(i, expertConsumptionWeights.get(i) - totLogWeight);
		}
		
		double totalProductionWeight = 0.0;
		for (double w : expertProductionWeights) {
			totalProductionWeight += Math.exp(w);
		}
		
		totLogWeight = Math.log(totalProductionWeight);
		for (int i = 0; i < numExperts; i++)
		{
			expertProductionWeights.set(i, expertProductionWeights.get(i) - totLogWeight);
		}
			
	}
	
	public Pair<Integer, Double> drawFromQDistribution (ArrayList<Double> logWeights, double gamma) {

		ArrayList<Double> pdf = new ArrayList<Double> ();
		
		double sum_wts = 0.0;
		for (double w : logWeights) {
			pdf.add(Math.exp(w));
			sum_wts += Math.exp(w);
		}
		System.out.println("Sum weights: " + sum_wts);

		int n = pdf.size();
		
		ArrayList<Double> cdf = new ArrayList<Double> ();

		cdf.add(0.0);
		for (int i=1; i < n+1; i ++) {
			cdf.add(cdf.get(i-1) + (1-gamma)*(pdf.get(i-1)/sum_wts) + gamma*(1 / (double) n));
		}

		double r = Math.random();

		for (int i=0; i < n; i ++) {
			if (r >= cdf.get(i) && r < cdf.get(i+1)) {
				System.out.println("P weight: " + pdf.get(i)/sum_wts);
				return new Pair<Integer, Double>(i, cdf.get(i+1) - cdf.get(i));
			}
		}
		System.out.println("P weight: " + pdf.get(n-1)/sum_wts);
		return new Pair<Integer, Double>(n-1, cdf.get(n) - cdf.get(n-1));
	}

	
	public int drawFromDistribution (ArrayList<Double> logWeights) {

		ArrayList<Double> cdf = new ArrayList<Double> ();
		cdf.add(0.0);
		for (double w : logWeights)
			cdf.add(cdf.get(cdf.size()-1) + Math.exp(w));

		double r = Math.random()*cdf.get(cdf.size()-1);

		for (int i=0; i < cdf.size() - 1; i ++) {
			if (r >= cdf.get(i) && r < cdf.get(i+1))
				return i;
		}
		return logWeights.size() - 1;

	}

	private void exp3(TariffSpecification tariffSpec, int t) {

		double currentGain = currentProfit.get(tariffSpec);
		System.out.println("Current profit:" + currentGain);
		if (currentGain < worstLoss) {
			worstLoss = currentGain;
			currentGain = -1.0;
		} 
		else if (currentGain < 0) {
			currentGain = -1.0 - (worstLoss - currentGain)/worstLoss;
		}
		else if (currentGain > bestProfit) {
			bestProfit = currentGain;
			bestExpert = currentExpert;
			currentGain = 1.0;
		}
		else currentGain = 1.0 - (bestProfit - currentGain)/bestProfit;

		double weightedCurrentGain =  currentGain / currentQWeight;
		
		currentProfit.put(tariffSpec, 0.0);
		
		double epsilon = Math.sqrt(2*Math.log(numExperts)/(numExperts*t));
		System.out.println("Current Gain: " + currentGain + " weightedCurrentGain: " + weightedCurrentGain + " Epsilon: " + epsilon);
		// TODO: not sure about this
		expertConsumptionWeights.set(currentExpert, expertConsumptionWeights.get(currentExpert) + epsilon*weightedCurrentGain);
	}

	private void improveTariffs (int timeSlotIndex) {

		double currentPrice = 0.0;
		// Convert meanMarketPrice to $/kWh
		double marketPrice = meanMarketPrice / 1000.0;
		// double penaltyFactor = 0.9;

		ArrayList<TariffSpecification> newSpecifications = new ArrayList<TariffSpecification> ();

		//System.out.println("****** SIZE: " + tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker()).size() );

		int idx=0;
		for (TariffSpecification spec :
			tariffRepo.findTariffSpecificationsByBroker(brokerContext.getBroker())) {
			PowerType pt = spec.getPowerType();
			// if the current tariff's power type is consumption, we need to 
			// adjust the rates based on the previous time step's profit and the
			// best profit
			if (pt.isConsumption()) {
				//System.out.println("*** NEXT SPEC ***" + idx++);

				// double loss = calculateLoss();
				exp3(spec, timeSlotIndex - firstTimeslot);
				renormalizeWeights();

				System.out.println("**********************");
				// Updated EXP3 for gain rather than loss
				Pair<Integer, Double> sample = drawFromQDistribution(expertConsumptionWeights, gamma);
				currentExpert = sample.getValue0();
				currentQWeight = sample.getValue1();

				double suggestedPrice = expertConsumptionPrices.get(currentExpert);
				System.out.println("Current expert: " + currentExpert);
				System.out.println("Qweight: " + currentQWeight);// + "\nCurrent expert weight: " + expertConsumptionWeights.get(currentExpert));
				System.out.println("Gamma: " + gamma);
				
				// Update the tariff and push it out
				TariffSpecification newSpec =
						new TariffSpecification(brokerContext.getBroker(),
								PowerType.CONSUMPTION)
				.withPeriodicPayment(defaultPeriodicPayment * 1.1);
				newSpec.addRate(new Rate().withValue(suggestedPrice));
				newSpec.addSupersedes(spec.getId());

				TariffRevoke revoke = new TariffRevoke(brokerContext.getBroker(), spec);
				brokerContext.sendMessage(revoke);
				
				tariffRepo.removeSpecification(spec.getId());
				currentProfit.remove(spec);
				
				newSpecifications.add(newSpec);
			}
		}
		for (TariffSpecification spec : newSpecifications) {
			tariffRepo.addSpecification(spec);
			brokerContext.sendMessage(spec);
			currentProfit.put(spec, 0.0);
		}
	}

	public void setMeanMarketPrice (double mmp) {
		meanMarketPrice = mmp;
	}

	public double getMeanMarketPrice () {
		return meanMarketPrice;
	}

	public void setEstimatedEnergyCost (double eec) {
		estimatedEnergyCost = eec;
	}

	public double getEstimatedEnergyCost () {
		return estimatedEnergyCost;
	}

	// ------------- test-support methods ----------------
	double getUsageForCustomer (CustomerInfo customer,
			TariffSpecification tariffSpec,
			int index)
	{
		CustomerRecord record = getCustomerRecordByTariff(tariffSpec, customer);
		return record.getUsage(index);
	}

	// test-support method
	HashMap<PowerType, double[]> getRawUsageForCustomer (CustomerInfo customer)
	{
		HashMap<PowerType, double[]> result = new HashMap<PowerType, double[]>();
		for (PowerType type : customerProfiles.keySet()) {
			CustomerRecord record = customerProfiles.get(type).get(customer);
			if (record != null) {
				result.put(type, record.usage);
			}
		}
		return result;
	}

	// test-support method
	HashMap<String, Integer> getCustomerCounts()
	{
		HashMap<String, Integer> result = new HashMap<String, Integer>();
		for (TariffSpecification spec : customerSubscriptions.keySet()) {
			HashMap<CustomerInfo, CustomerRecord> customerMap = customerSubscriptions.get(spec);
			for (CustomerRecord record : customerMap.values()) {
				result.put(record.customer.getName() + spec.getPowerType(), 
						record.subscribedPopulation);
			}
		}
		return result;
	}

	//-------------------- Customer-model recording ---------------------
	/**
	 * Keeps track of customer status and usage. Usage is stored
	 * per-customer-unit, but reported as the product of the per-customer
	 * quantity and the subscribed population. This allows the broker to use
	 * historical usage data as the subscribed population shifts.
	 */
	class CustomerRecord
	{
		CustomerInfo customer;
		int subscribedPopulation = 0;
		double[] usage;
		double alpha = 0.3;

		/**
		 * Creates an empty record
		 */
		CustomerRecord (CustomerInfo customer)
		{
			super();
			this.customer = customer;
			this.usage = new double[brokerContext.getUsageRecordLength()];
		}

		CustomerRecord (CustomerRecord oldRecord)
		{
			super();
			this.customer = oldRecord.customer;
			this.usage = Arrays.copyOf(oldRecord.usage, brokerContext.getUsageRecordLength());
		}

		// Returns the CustomerInfo for this record
		CustomerInfo getCustomerInfo ()
		{
			return customer;
		}

		// Adds new individuals to the count
		void signup (int population)
		{
			subscribedPopulation = Math.min(customer.getPopulation(),
					subscribedPopulation + population);
		}

		// Removes individuals from the count
		void withdraw (int population)
		{
			subscribedPopulation -= population;
		}

		// Customer produces or consumes power. We assume the kwh value is negative
		// for production, positive for consumption
		void produceConsume (double kwh, Instant when)
		{
			int index = getIndex(when);
			produceConsume(kwh, index);
		}

		// store profile data at the given index
		void produceConsume (double kwh, int rawIndex)
		{
			int index = getIndex(rawIndex);
			double kwhPerCustomer = 0.0;
			if (subscribedPopulation > 0) {
				kwhPerCustomer = kwh / (double)subscribedPopulation;
			}
			double oldUsage = usage[index];
			if (oldUsage == 0.0) {
				// assume this is the first time
				usage[index] = kwhPerCustomer;
			}
			else {
				// exponential smoothing
				usage[index] = alpha * kwhPerCustomer + (1.0 - alpha) * oldUsage;
			}
			log.debug("consume " + kwh + " at " + index +
					", customer " + customer.getName());
		}

		double getUsage (int index)
		{
			if (index < 0) {
				log.warn("usage requested for negative index " + index);
				index = 0;
			}
			return (usage[getIndex(index)] * (double)subscribedPopulation);
		}

		// we assume here that timeslot index always matches the number of
		// timeslots that have passed since the beginning of the simulation.
		int getIndex (Instant when)
		{
			int result = (int)((when.getMillis() - timeService.getBase()) /
					(Competition.currentCompetition().getTimeslotDuration()));
			return result;
		}

		private int getIndex (int rawIndex)
		{
			return rawIndex % usage.length;
		}
	}
}
