/*
 * Copyright 2010-2012 the original author or authors.
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
package org.powertac.householdcustomer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.Competition;
import org.powertac.common.CustomerInfo;
import org.powertac.common.RandomSeed;
import org.powertac.common.Tariff;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.interfaces.InitializationService;
import org.powertac.common.interfaces.NewTariffListener;
import org.powertac.common.interfaces.ServerConfiguration;
import org.powertac.common.interfaces.TariffMarket;
import org.powertac.common.interfaces.TimeslotPhaseProcessor;
import org.powertac.common.repo.RandomSeedRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.householdcustomer.configurations.VillageConstants;
import org.powertac.householdcustomer.customers.Village;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Implements the Household Consumer Model. It creates Household Consumers that
 * can subscribe to tariffs, evaluate them in order to choose the best one for
 * its interests, shift their load in order to minimize their costs and many
 * others. They contain different types of households with respect to the way
 * they choose the tariffs and they shift their loads.
 * 
 * @author Antonios Chrysopoulos
 * @version 1.5, Date: 2.25.12
 */
@Service
public class HouseholdCustomerService extends TimeslotPhaseProcessor
  implements NewTariffListener, InitializationService
{
  /**
   * logger for trace logging -- use log.info(), log.warn(), and log.error()
   * appropriately. Use log.debug() for output you want to see in testing or
   * debugging.
   */
  static private Logger log = Logger.getLogger(HouseholdCustomerService.class
          .getName());

  @Autowired
  private TariffMarket tariffMarketService;

  @Autowired
  private ServerConfiguration serverPropertiesService;

  @Autowired
  private RandomSeedRepo randomSeedRepo;

  @Autowired
  private TimeslotRepo timeslotRepo;

  /** Random Number Generator */
  private RandomSeed rs1;

  // read this from configurator
  private String configFile1 = null;
  private int daysOfCompetition = 0;

  /**
   * This is the configuration file that will be utilized to pass the parameters
   * that can be adjusted by user
   */
  Properties configuration = new Properties();

  /** List of the Household Customers in the competition */
  ArrayList<Village> villageList;

  int seedId = 1;

  /** This is the constructor of the Household Consumer Service. */
  public HouseholdCustomerService ()
  {
    super();
    villageList = new ArrayList<Village>();
  }

  /**
   * This function called once at the beginning of each game by the server
   * initialization service. Here is where you do pre-game setup. This will read
   * the server properties file to take the competition input variables needed
   * (configuration files, days of competition), create a listener for our
   * service, in order to get the new tariff, as well as create the household
   * Consumers that will be running in the game.
   */
  @Override
  public String
    initialize (Competition competition, List<String> completedInits)
  {
    int index = completedInits.indexOf("DefaultBroker");
    if (index == -1) {
      return null;
    }

    serverPropertiesService.configureMe(this);

    villageList.clear();

    tariffMarketService.registerNewTariffListener(this);
    rs1 =
      randomSeedRepo.getRandomSeed("HouseholdCustomerService", seedId++,
                                   "Household Customer Models");

    if (configFile1 == null) {
      log.info("No Config File for VillageType1 Taken");
      configFile1 = "VillageDefault.properties";
    }

    super.init();
    daysOfCompetition =
      Competition.currentCompetition().getExpectedTimeslotCount()
              / VillageConstants.HOURS_OF_DAY;
    VillageConstants.setDaysOfCompetition(daysOfCompetition);
    VillageConstants.setDaysOfWeek();
    daysOfCompetition = VillageConstants.DAYS_OF_COMPETITION;

    if (daysOfCompetition == 0) {
      log.info("No Days Of Competition Taken");
      daysOfCompetition = 63;
    }

    addVillages(configFile1, "1");

    return "HouseholdCustomer";
  }

  private void addVillages (String configFile, String type)
  {
    InputStream cfgFile =
      Thread.currentThread().getContextClassLoader()
              .getResourceAsStream(configFile);
    try {
      configuration.load(cfgFile);
      cfgFile.close();
    }
    catch (IOException e) {
      e.printStackTrace();
      return;
    }

    String[] types = { "NS", "RaS", "ReS", "SS" };
    String[] shifts = { "Base", "Controllable" };
    Map<String, Integer> houses = new TreeMap<String, Integer>();
    int numberOfVillages =
      Integer.parseInt(configuration.getProperty("NumberOfVillages"));
    int nshouses =
      Integer.parseInt(configuration.getProperty("NotShiftingCustomers"));
    houses.put("NS", nshouses);
    int rashouses =
      Integer.parseInt(configuration.getProperty("RandomlyShiftingCustomers"));
    houses.put("RaS", rashouses);
    int reshouses =
      Integer.parseInt(configuration.getProperty("RegularlyShiftingCustomers"));
    houses.put("ReS", reshouses);
    int sshouses =
      Integer.parseInt(configuration.getProperty("SmartShiftingCustomers"));
    houses.put("SS", sshouses);

    Comparator<CustomerInfo> comp = new Comparator<CustomerInfo>() {
      public int compare (CustomerInfo customer1, CustomerInfo customer2)
      {
        return customer1.getName().compareToIgnoreCase(customer2.getName());
      }
    };

    for (int i = 1; i < numberOfVillages + 1; i++) {
      Village village = new Village("Village " + i);
      Map<CustomerInfo, String> map = new TreeMap<CustomerInfo, String>(comp);

      for (String houseType: types) {
        for (String shifting: shifts) {

          CustomerInfo villageInfo =
            new CustomerInfo("Village " + i + " " + houseType + " " + shifting,
                             houses.get(houseType));
          if (shifting.equalsIgnoreCase("Base"))
            villageInfo.withPowerType(PowerType.CONSUMPTION);
          else
            villageInfo.withPowerType(PowerType.INTERRUPTIBLE_CONSUMPTION);

          map.put(villageInfo, houseType + " " + shifting);
          village.addCustomerInfo(villageInfo);
        }

      }

      village.initialize(configuration, seedId++, map);
      villageList.add(village);
      village.subscribeDefault();

    }
  }

  @Override
  public void publishNewTariffs (List<Tariff> tariffs)
  {
    // For each village of the server //
    for (Village village: villageList)
      village.evaluateNewTariffs();

  }

  // ----------------- Data access -------------------------

  /** Getter method for the days of competition */
  public int getDaysOfCompetition ()
  {
    return daysOfCompetition;
  }

  @ConfigurableValue(valueType = "Integer", description = "The competition duration in days")
  public
    void setDaysOfCompetition (int days)
  {
    daysOfCompetition = days;
  }

  /** Getter method for the first configuration file */
  public String getConfigFile1 ()
  {
    return configFile1;
  }

  @ConfigurableValue(valueType = "String", description = "first configuration file of the household customers")
  public
    void setConfigFile1 (String config)
  {
    configFile1 = config;
  }

  /**
   * This function returns the list of the villages created at the beginning of
   * the game by the service
   */
  public List<Village> getVillageList ()
  {
    return villageList;
  }

  /**
   * This function cleans the configuration files in case they have not been
   * cleaned at the beginning of the game
   */
  public void clearConfiguration ()
  {
    configFile1 = null;
  }

  /**
   * This function finds all the available Household Consumers in the
   * competition and creates a list of their customerInfo.
   * 
   * @return List<CustomerInfo>
   */
  public List<CustomerInfo> generateCustomerInfoList ()
  {
    ArrayList<CustomerInfo> result = new ArrayList<CustomerInfo>();
    for (Village village: villageList) {
      for (CustomerInfo customer: village.getCustomerInfo())
        result.add(customer);
    }
    return result;
  }

  @Override
  public void activate (Instant time, int phaseNumber)
  {
    log.info("Activate");
    if (villageList.size() > 0) {
      for (Village village: villageList) {
        village.step();
      }
    }
  }

  @Override
  public void setDefaults ()
  {
  }

}
