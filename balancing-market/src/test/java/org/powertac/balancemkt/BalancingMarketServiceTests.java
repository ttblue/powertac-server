package org.powertac.balancemkt;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import static org.powertac.util.ListTools.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powertac.balancemkt.BalancingMarketService;
import org.powertac.common.config.Configurator;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.interfaces.Accounting;
import org.powertac.common.interfaces.ServerConfiguration;
import org.powertac.common.msg.BalancingOrder;
import org.powertac.common.Broker;
import org.powertac.common.Competition;
import org.powertac.common.Rate;
import org.powertac.common.Tariff;
import org.powertac.common.TariffSpecification;
import org.powertac.common.TimeService;
import org.powertac.common.Timeslot;
import org.powertac.common.repo.BrokerRepo;
import org.powertac.common.repo.OrderbookRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.util.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-config.xml" })
public class BalancingMarketServiceTests
{

  @Autowired
  private TimeService timeService;

  @Autowired
  private TimeslotRepo timeslotRepo;

  @Autowired
  private BrokerRepo brokerRepo;

  @Autowired
  private TariffRepo tariffRepo;

  @Autowired
  private OrderbookRepo orderbookRepo;
  
  @Autowired
  private Accounting accountingService;
  
  @Autowired
  private ServerConfiguration serverPropertiesService;

  @Autowired
  private BalancingMarketService balancingMarketService;

  private Competition comp;
  private Configurator config;
  private List<Broker> brokerList = new ArrayList<Broker>();
  private List<TariffSpecification> tariffSpecList = new ArrayList<TariffSpecification>();
  private List<Tariff> tariffList = new ArrayList<Tariff>();
  private DateTime start;

  @Before
  public void setUp ()
  {
    // create a Competition, needed for initialization
    comp = Competition.newInstance("du-test");
    Competition.setCurrent(comp);
    
    Instant base =
            Competition.currentCompetition().getSimulationBaseTime().plus(TimeService.DAY);
    start = new DateTime(start, DateTimeZone.UTC);
    timeService.setCurrentTime(base);
    timeslotRepo.makeTimeslot(base);
    //timeslotRepo.currentTimeslot().disable();// enabled: false);
    reset(accountingService);

    // Create 3 test brokers
    Broker broker1 = new Broker("testBroker1");
    brokerRepo.add(broker1);
    brokerList.add(broker1);

    Broker broker2 = new Broker("testBroker2");
    brokerRepo.add(broker2);
    brokerList.add(broker2);

    Broker broker3 = new Broker("testBroker3");
    brokerRepo.add(broker3);
    brokerList.add(broker3);

    // Set up serverProperties mock
    config = new Configurator();
    doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) {
        Object[] args = invocation.getArguments();
        config.configureSingleton(args[0]);
        return null;
      }
    }).when(serverPropertiesService).configureMe(anyObject());
  }

  @After
  public void tearDown ()
  {
    // clear all repos
    timeslotRepo.recycle();
    brokerRepo.recycle();
    tariffRepo.recycle();
    orderbookRepo.recycle();

    // clear member lists
    brokerList.clear();
    tariffSpecList.clear();
    tariffList.clear();
  }

  private void initializeService ()
  {
    balancingMarketService.setDefaults();
    TreeMap<String, String> map = new TreeMap<String, String>();
    map.put("balancemkt.balancingMarketService.balancingCostMin", "-0.06");
    map.put("balancemkt.balancingMarketService.balancingCostMax", "-0.06");
    Configuration mapConfig = new MapConfiguration(map);
    config.setConfiguration(mapConfig);
    balancingMarketService.initialize(comp, new ArrayList<String>());
    assertEquals("correct setting", -0.06,
                 balancingMarketService.getBalancingCostMin(), 1e-6);
    assertEquals("correct setting", -0.06,
                 balancingMarketService.getBalancingCostMax(), 1e-6);
    assertEquals("correct setting", -0.06,
                 balancingMarketService.getBalancingCost(), 1e-6);
  }

  @Test
  public void testGetMarketBalance ()
  {
    initializeService();
    when(accountingService.getCurrentMarketPosition((Broker) anyObject())).thenReturn(1.0);
    when(accountingService.getCurrentNetLoad((Broker) anyObject())).thenReturn(-500.0);    
    assertEquals("correct balance",
                 500.0,
                 balancingMarketService.getMarketBalance(brokerList.get(0)),
                 1e-6);
    when(accountingService.getCurrentNetLoad((Broker) anyObject())).thenReturn(-1000.0);    
    assertEquals("correct balance",
                 0.0,
                 balancingMarketService.getMarketBalance(brokerList.get(0)),
                 1e-6);
    when(accountingService.getCurrentNetLoad((Broker) anyObject())).thenReturn(-1500.0);    
    assertEquals("correct balance",
                 -500.0,
                 balancingMarketService.getMarketBalance(brokerList.get(0)),
                 1e-6);
  }

  @Test
  public void testNegImbalancedMarket ()
  {
    initializeService();
    when(accountingService.getCurrentMarketPosition((Broker) anyObject())).thenReturn(0.0);
    when(accountingService.getCurrentNetLoad((Broker) anyObject())).thenReturn(-50.0);    
    double marketBalance = -150.0; // Compute market balance
    Map<Broker, ChargeInfo> theChargeInfoList =
        balancingMarketService.balanceTimeslot(timeslotRepo.currentTimeslot(),
                                                   brokerList);

    assertEquals("correct number of balance tx", 3, theChargeInfoList.size());
    for (ChargeInfo ci : theChargeInfoList.values()) {
      marketBalance -= ci.getNetLoadKWh();
    }
    assertEquals("correct balancing transactions", 0.0, marketBalance, 1e-6);
  }

  @Test
  public void TestPosImbalancedMarket ()
  {
    initializeService();
    when(accountingService.getCurrentMarketPosition((Broker) anyObject())).thenReturn(0.0);
    when(accountingService.getCurrentNetLoad((Broker) anyObject())).thenReturn(50.0);    
    double marketBalance = 150.0; // Compute market balance

    Map<Broker, ChargeInfo> theChargeInfoList = balancingMarketService.balanceTimeslot(timeslotRepo.currentTimeslot(),
                                                                                    brokerList);

    assertEquals("correct number of balance tx", 3, theChargeInfoList.size());
    for (ChargeInfo ci : theChargeInfoList.values()) {
      marketBalance -= ci.getNetLoadKWh();
    }
    assertEquals("correct balancing transactions", 0.0, marketBalance, 1e-6);
  }

  @Test
  public void testIndividualBrokerBalancing ()
  {
    initializeService();
    double balance = 0.0;

    when(accountingService.getCurrentMarketPosition((Broker) anyObject())).thenReturn(0.0);
    when(accountingService.getCurrentNetLoad(brokerList.get(0))).thenReturn(-19599990.0);    
    when(accountingService.getCurrentNetLoad(brokerList.get(1))).thenReturn(0.0);
    when(accountingService.getCurrentNetLoad(brokerList.get(2))).thenReturn(8791119.0);    

    // Compute market balance
    for (Broker b : brokerList) {
      balance += balancingMarketService.getMarketBalance(b);
    }

    Map<Broker, ChargeInfo> chargeInfos = balancingMarketService.balanceTimeslot(timeslotRepo.currentTimeslot(),
                                                                                    brokerList);

    // ensure each broker was balanced correctly
    for (Broker broker : brokerList) {
      ChargeInfo ci = chargeInfos.get(broker);
      assertNotNull("found ChargeInfo", ci);
      assertEquals("broker correctly balanced",
                   0.0,
                   (balancingMarketService.getMarketBalance(broker)
                           - ci.getNetLoadKWh()),
                   1e-6);
      balance -= ci.getNetLoadKWh();
    }
    assertEquals("market fully balanced", 0.0, balance, 1e-6);
  }

  @Test
  public void testScenario1BalancingCharges ()
  {
    initializeService();

    when(accountingService.getCurrentMarketPosition((Broker) anyObject())).thenReturn(0.0);
    when(accountingService.getCurrentNetLoad(brokerList.get(0))).thenReturn(200.0);    
    when(accountingService.getCurrentNetLoad(brokerList.get(1))).thenReturn(-400.0);
    when(accountingService.getCurrentNetLoad(brokerList.get(2))).thenReturn(0.0);    

    // List solution =
    // balancingMarketService.computeNonControllableBalancingCharges(brokerList)
    Map<Broker, ChargeInfo> chargeInfos =
        balancingMarketService.balanceTimeslot(timeslotRepo.currentTimeslot(),
                                                   brokerList);

    // Correct solution list is [-4, 14, 2] (but negated)
    ChargeInfo ci = chargeInfos.get(brokerList.get(0)); // BalancingTransaction.findByBroker(brokerList.get(0));
    assertNotNull("non-null btx, broker 1", ci);
    assertEquals("correct balancing charge broker1",
                 4.0, ci.getBalanceCharge(), 1e-6);
    ci = chargeInfos.get(brokerList.get(1)); // BalancingTransaction.findByBroker(brokerList.get(1));
    assertNotNull("non-null btx, broker 2", ci);
    assertEquals("correct balancing charge broker2",
                 -14.0, ci.getBalanceCharge(), 1e-6);
    ci = chargeInfos.get(brokerList.get(2)); // BalancingTransaction.findByBroker(brokerList.get(2));
    assertNotNull("non-null btx, broker 3", ci);
    assertEquals("correct balancing charge broker3",
                 -2.0, ci.getBalanceCharge(), 1e-6);
  }

  @Test
  public void testSpotPrice ()
  {
    initializeService();
    updatePrices();

    // make sure we can retrieve current spot price
    assertEquals("correct spot price", 0.0201,
                 balancingMarketService.getSpotPrice(), 1e-6);
    assertEquals("correct pMinus", -0.0198,
                 balancingMarketService.getPMinus(), 1e-6);
    assertEquals("correct pPlus", 0.0212,
                 balancingMarketService.getPPlus(), 1e-6);
  }

  private void updatePrices ()
  {
    // add some new timeslots
    Timeslot ts0 = timeslotRepo.currentTimeslot();
    long start = timeService.getCurrentTime().getMillis();
    Timeslot ts1 = timeslotRepo.findByInstant(new Instant(start - TimeService.HOUR * 3));
    Timeslot ts2 = timeslotRepo.findByInstant(new Instant(start - TimeService.HOUR * 2));
    Timeslot ts3 = timeslotRepo.findByInstant(new Instant(start - TimeService.HOUR));

    // add some orderbooks
    orderbookRepo.makeOrderbook(ts3, 33.0);
    orderbookRepo.makeOrderbook(ts3, 32.0);
    orderbookRepo.makeOrderbook(ts0, 20.2);
    orderbookRepo.makeOrderbook(ts0, 21.2);
    orderbookRepo.makeOrderbook(ts0, 19.8);
    // this should be the spot price
    orderbookRepo.makeOrderbook(ts0, 20.1);
  }
  
  // make sure balancing orders are correctly allocated
  @Test
  public void testBalancingOrderAllocation ()
  {
    initializeService();
    final Broker b1 = brokerRepo.findByUsername("testBroker1");
    TariffSpecification b1ts1 =
            new TariffSpecification(b1, PowerType.INTERRUPTIBLE_CONSUMPTION);
    b1ts1.addRate(new Rate().withValue(0.12).withMaxCurtailment(0.3));
    tariffRepo.addSpecification(b1ts1);
    TariffSpecification b1ts2 =
            new TariffSpecification(b1, PowerType.INTERRUPTIBLE_CONSUMPTION);
    b1ts1.addRate(new Rate().withValue(0.10).withMaxCurtailment(0.5));
    tariffRepo.addSpecification(b1ts2);

    final Broker b2 = brokerRepo.findByUsername("testBroker2");
    TariffSpecification b2ts1 =
            new TariffSpecification(b2, PowerType.INTERRUPTIBLE_CONSUMPTION);
    b1ts1.addRate(new Rate().withValue(0.13).withMaxCurtailment(0.2));
    tariffRepo.addSpecification(b2ts1);
    
    BalancingOrder bo1t1 = new BalancingOrder(b1, b1ts1, 0.8, 0.2);
    tariffRepo.addBalancingOrder(bo1t1);
    BalancingOrder bo1t2 = new BalancingOrder(b1, b1ts2, 0.6, 0.15);
    tariffRepo.addBalancingOrder(bo1t2);
    BalancingOrder bo2t1 = new BalancingOrder(b2, b2ts1, 0.7, 0.1);
    tariffRepo.addBalancingOrder(bo2t1);
    
    assertEquals("correct number of bo", 3,
                 tariffRepo.getBalancingOrders().size());

    Map<Broker, ChargeInfo> chargeInfos =
            balancingMarketService.balanceTimeslot(timeslotRepo.currentTimeslot(),
                                                       brokerList);
    assertEquals("correct count", 3, chargeInfos.size());
    
    ChargeInfo c1b1 = findFirst(chargeInfos.values(),
                                new Predicate<ChargeInfo>() {
      @Override
      public boolean apply (ChargeInfo item)
      {
        return (item.getBroker() == b1);
      }
    });
    assertNotNull("found correct chargeInfo", c1b1);
    List<BalancingOrder> orders = c1b1.getBalancingOrders();
    assertEquals("found 2 balancing orders", 2, orders.size());
    assertTrue("contains bo1t1", orders.contains(bo1t1));
    assertTrue("contains bo1t2", orders.contains(bo1t2));
    
    ChargeInfo c1b2 = findFirst(chargeInfos.values(),
                                new Predicate<ChargeInfo>() {
      @Override
      public boolean apply (ChargeInfo item)
      {
        return (item.getBroker() == b2);
      }
    });
    assertNotNull("found correct chargeInfo", c1b2);
    orders = c1b2.getBalancingOrders();
    assertEquals("found 1 balancing order", 1, orders.size());
    assertTrue("contains bo2t1", orders.contains(bo2t1));
  }
}
